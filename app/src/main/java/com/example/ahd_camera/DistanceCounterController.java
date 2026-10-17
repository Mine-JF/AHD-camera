package com.example.ahd_camera;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Receives DnkCounter active-report frames without blocking the UI thread. */
final class DistanceCounterController {

    interface Listener {
        void onCounterValue(int value);
    }

    private static final String TAG = "DistanceCounter";
    private static final long POLL_INTERVAL_MS = 250L;

    private final MotorController motorController;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private Thread workerThread;

    DistanceCounterController(MotorController motorController, Listener listener) {
        this.motorController = motorController;
        this.listener = listener;
    }

    synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        workerThread = new Thread(this::pollLoop, "distance-counter-thread");
        workerThread.start();
    }

    synchronized void stop() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
    }

    private void pollLoop() {
        while (running) {
            Integer value = motorController.readDistanceCounter();
            if (value != null) {
                mainHandler.post(() -> {
                    if (running) {
                        listener.onCounterValue(value);
                    }
                });
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        Log.d(TAG, "pollLoop stopped");
    }
}
