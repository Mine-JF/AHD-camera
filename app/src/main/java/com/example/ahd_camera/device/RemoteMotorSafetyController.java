package com.example.ahd_camera.device;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Failsafe for phone-controlled pan/tilt: a lost release must never run the motor forever. */
public final class RemoteMotorSafetyController {
    private static final String TAG = "RemoteMotorSafety";
    private static final long LEASE_MS = 650L;
    private static final long MIN_MOVE_MS = 180L;
    private static final long STOP_RETRY_MS = 150L;

    private final MotorController motor;
    private final HandlerThread worker = new HandlerThread("remote-motor-control",
            Process.THREAD_PRIORITY_DISPLAY);
    private final Handler handler;
    private final Axis tilt = new Axis();
    private final Axis pan = new Axis();

    private final class Axis {
        int activeCommand;
        int generation;
        long expiresAt;
        long startedAt;
        Runnable pendingStop;
        final Runnable watchdog = new Runnable() {
            @Override
            public void run() {
                if (activeCommand == 0) return;
                long remaining = expiresAt - SystemClock.uptimeMillis();
                if (remaining > 0) {
                    handler.postDelayed(this, remaining);
                    return;
                }
                Log.w(TAG, "motor lease expired, stopping command=" + activeCommand);
                release(Axis.this, activeCommand);
            }
        };
    }

    public RemoteMotorSafetyController(MotorController motor) {
        this.motor = motor;
        worker.start();
        handler = new Handler(worker.getLooper());
    }

    public void start(int command) {
        long queuedAt = SystemClock.elapsedRealtimeNanos();
        handler.post(() -> startOnWorker(command, queuedAt));
    }

    private void startOnWorker(int command, long queuedAt) {
        long startedAt = SystemClock.elapsedRealtimeNanos();
        Axis axis = axisFor(command);
        if (axis.pendingStop != null) {
            handler.removeCallbacks(axis.pendingStop);
            axis.pendingStop = null;
        }
        axis.generation++;
        axis.activeCommand = command;
        motor.move(command);
        Log.i(TAG, "motor timing start command=" + command + ": queueMs="
                + (startedAt - queuedAt) / 1_000_000f + ", serialMs="
                + (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000f);
        axis.startedAt = SystemClock.uptimeMillis();
        renew(axis);
    }

    public void keepAlive(int command) {
        handler.post(() -> keepAliveOnWorker(command));
    }

    private void keepAliveOnWorker(int command) {
        Axis axis = axisFor(command);
        if (axis.activeCommand == command) renew(axis);
    }

    public void stop(int command) {
        handler.post(() -> stopOnWorker(command));
    }

    private void stopOnWorker(int command) {
        Axis axis = axisFor(command);
        if (axis.activeCommand != command || axis.pendingStop != null) return;
        long remaining = MIN_MOVE_MS - (SystemClock.uptimeMillis() - axis.startedAt);
        if (remaining <= 0) {
            release(axis, command);
        } else {
            int generation = axis.generation;
            axis.pendingStop = () -> {
                axis.pendingStop = null;
                if (axis.generation == generation && axis.activeCommand == command) {
                    release(axis, command);
                }
            };
            handler.postDelayed(axis.pendingStop, remaining);
        }
    }

    public void stopAllAndClose() {
        if (Looper.myLooper() == handler.getLooper()) {
            stopAllOnWorker();
            return;
        }
        CountDownLatch stopped = new CountDownLatch(1);
        if (!handler.post(() -> {
            try {
                stopAllOnWorker();
            } finally {
                stopped.countDown();
            }
        })) return;
        try {
            if (!stopped.await(1, TimeUnit.SECONDS)) {
                Log.e(TAG, "timed out waiting for motor stop");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void shutdown() {
        stopAllAndClose();
        worker.quitSafely();
    }

    private void stopAllOnWorker() {
        cancel(tilt);
        cancel(pan);
        motor.stopAndClose();
    }

    private void renew(Axis axis) {
        axis.expiresAt = SystemClock.uptimeMillis() + LEASE_MS;
        handler.removeCallbacks(axis.watchdog);
        handler.postDelayed(axis.watchdog, LEASE_MS);
    }

    private void release(Axis axis, int command) {
        cancel(axis);
        int generation = axis.generation;
        motor.stopForMove(command);
        // The MCU can miss a stop sent immediately after a short start. Retry twice,
        // unless a newer press has started on the same axis in the meantime.
        Runnable retryStop = () -> {
            if (axis.generation == generation && axis.activeCommand == 0) {
                motor.stopForMove(command);
            }
        };
        handler.postDelayed(retryStop, STOP_RETRY_MS);
        handler.postDelayed(retryStop, STOP_RETRY_MS * 2);
    }

    private void cancel(Axis axis) {
        axis.generation++;
        axis.activeCommand = 0;
        handler.removeCallbacks(axis.watchdog);
        if (axis.pendingStop != null) {
            handler.removeCallbacks(axis.pendingStop);
            axis.pendingStop = null;
        }
    }

    private Axis axisFor(int command) {
        return command == MotorController.COMMAND_UP || command == MotorController.COMMAND_DOWN
                ? tilt : pan;
    }
}
