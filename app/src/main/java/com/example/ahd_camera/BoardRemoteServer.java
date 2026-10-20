package com.example.ahd_camera;

import android.os.Handler;
import android.util.Log;
import android.view.View;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** First LAN prototype: one TCP control client and one H.264 video client. */
final class BoardRemoteServer {
    interface CommandListener {
        void onCommand(String command);
    }

    static final int CONTROL_PORT = 39001;
    static final int VIDEO_PORT = 39002;
    private static final String TAG = "BoardRemoteServer";
    private static final long HEARTBEAT_TIMEOUT_MS = 10000;

    private final Handler mainHandler;
    private volatile View previewRoot;
    private volatile View overlay;
    private volatile int previewRotationDegrees;
    private final CommandListener listener;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean videoPaused = new AtomicBoolean(true);
    private ServerSocket controlServer;
    private ServerSocket videoServer;
    private volatile Socket controlClient;
    private volatile Socket videoClient;

    BoardRemoteServer(Handler mainHandler, CommandListener listener) {
        this.mainHandler = mainHandler;
        this.listener = listener;
    }

    void setPreviewViews(View previewRoot, View overlay) {
        this.previewRoot = previewRoot;
        this.overlay = overlay;
    }

    void setPreviewRotation(int degrees) {
        previewRotationDegrees = degrees;
    }

    void start() {
        if (!running.compareAndSet(false, true)) {
            Log.i(TAG, "start ignored: already running");
            return;
        }
        Log.i(TAG, "starting LAN server: control=" + CONTROL_PORT + ", video=" + VIDEO_PORT);
        new Thread(this::serveControl, "remote-control-server").start();
        new Thread(this::serveVideo, "remote-video-server").start();
    }

    boolean isRunning() {
        return running.get();
    }

    void setVideoPaused(boolean paused) {
        videoPaused.set(paused);
        Log.i(TAG, "video paused=" + paused);
        if (paused) {
            // A paused stream leaves the decoder displaying its final frame. Closing both
            // channels tells the phone immediately that the board has no foreground UI.
            close(videoClient);
            close(controlClient);
            Log.i(TAG, "board backgrounded: remote clients disconnected");
        }
    }

    void stop() {
        Log.i(TAG, "stopping LAN server");
        running.set(false);
        close(controlClient);
        close(videoClient);
        close(controlServer);
        close(videoServer);
    }

    private void serveControl() {
        Log.i(TAG, "binding control listener on 0.0.0.0:" + CONTROL_PORT);
        try (ServerSocket server = new ServerSocket(CONTROL_PORT)) {
            controlServer = server;
            Log.i(TAG, "control listener ready: " + server.getLocalSocketAddress());
            while (running.get()) {
                try {
                    Socket socket = server.accept();
                    if (videoPaused.get()) {
                        Log.i(TAG, "control rejected while board is backgrounded: "
                                + socket.getInetAddress());
                        close(socket);
                        continue;
                    }
                    Socket previous = controlClient;
                    controlClient = socket;
                    if (previous != null) {
                        close(previous);
                        mainHandler.post(() -> listener.onCommand("STOP_ALL"));
                    }
                    socket.setSoTimeout(1000);
                    socket.setTcpNoDelay(true);
                    Log.i(TAG, "control connected: " + socket.getInetAddress());
                    new Thread(() -> serveControlClient(socket), "remote-control-client").start();
                } catch (IOException error) {
                    if (running.get()) Log.w(TAG, "control accept failed", error);
                }
            }
        } catch (IOException error) {
            if (running.get()) Log.e(TAG, "control server failed", error);
        } finally {
            Log.i(TAG, "control listener stopped");
            controlServer = null;
        }
    }

    private void serveControlClient(Socket socket) {
        try (Socket client = socket) {
            handleControlClient(client);
        } catch (IOException error) {
            if (running.get() && controlClient == socket) {
                Log.w(TAG, "control connection ended", error);
            }
        } finally {
            Log.i(TAG, "control client disconnected");
            if (controlClient == socket) {
                controlClient = null;
                if (running.get()) mainHandler.post(() -> listener.onCommand("STOP_ALL"));
            }
        }
    }

    private void handleControlClient(Socket socket) throws IOException {
        BufferedReader input = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                socket.getOutputStream(), StandardCharsets.UTF_8));
        long lastHeartbeat = android.os.SystemClock.elapsedRealtime();
        while (running.get() && controlClient == socket) {
            try {
                String line = input.readLine();
                if (line == null) {
                    Log.i(TAG, "control client closed stream");
                    break;
                }
                if ("PING".equals(line)) {
                    lastHeartbeat = android.os.SystemClock.elapsedRealtime();
                    output.write("PONG\n");
                    output.flush();
                } else if (isAllowed(line)) {
                    Log.i(TAG, "control command received: " + line);
                    lastHeartbeat = android.os.SystemClock.elapsedRealtime();
                    mainHandler.post(() -> listener.onCommand(line));
                    output.write("OK\n");
                    output.flush();
                } else {
                    Log.w(TAG, "control command rejected: " + line);
                    output.write("ERR\n");
                    output.flush();
                }
            } catch (SocketTimeoutException timeout) {
                if (android.os.SystemClock.elapsedRealtime() - lastHeartbeat > HEARTBEAT_TIMEOUT_MS) {
                    Log.w(TAG, "control heartbeat expired");
                    break;
                }
            }
        }
    }

    private static boolean isAllowed(String command) {
        switch (command) {
            case "UP_DOWN": case "UP_UP":
            case "DOWN_DOWN": case "DOWN_UP":
            case "LEFT_DOWN": case "LEFT_UP":
            case "RIGHT_DOWN": case "RIGHT_UP":
            case "FOCUS_PLUS_DOWN": case "FOCUS_PLUS_UP":
            case "FOCUS_MINUS_DOWN": case "FOCUS_MINUS_UP":
            case "PHOTO": case "RECORD": case "STOP_ALL":
            case "RESET_DISTANCE": case "LIGHT":
            case "MENU": case "BACK": case "BACK_LONG": case "ROTATE": case "OK": case "HOME":
                return true;
            default:
                return false;
        }
    }

    private void serveVideo() {
        Log.i(TAG, "binding video listener on 0.0.0.0:" + VIDEO_PORT);
        try (ServerSocket server = new ServerSocket(VIDEO_PORT)) {
            videoServer = server;
            Log.i(TAG, "video listener ready: " + server.getLocalSocketAddress());
            while (running.get()) {
                try {
                    Socket socket = server.accept();
                    if (videoPaused.get()) {
                        Log.i(TAG, "video rejected while board is backgrounded: "
                                + socket.getInetAddress());
                        close(socket);
                        continue;
                    }
                    Socket previous = videoClient;
                    videoClient = socket;
                    close(previous);
                    socket.setTcpNoDelay(true);
                    Log.i(TAG, "video connected: " + socket.getInetAddress());
                    new Thread(() -> streamVideoClient(socket), "remote-video-client").start();
                } catch (IOException error) {
                    if (running.get()) Log.w(TAG, "video accept failed", error);
                }
            }
        } catch (IOException error) {
            if (running.get()) Log.e(TAG, "video server failed", error);
        } finally {
            Log.i(TAG, "video listener stopped");
            videoServer = null;
        }
    }

    private void streamVideoClient(Socket socket) {
        try (Socket client = socket) {
            new H264PreviewStreamer(mainHandler, () -> previewRoot, () -> overlay,
                    () -> previewRotationDegrees)
                    .stream(client, running, videoPaused);
        } catch (IOException | RuntimeException error) {
            if (running.get() && videoClient == socket) {
                Log.w(TAG, "video connection ended", error);
            }
        } finally {
            Log.i(TAG, "video client disconnected");
            if (videoClient == socket) videoClient = null;
        }
    }

    private static void close(java.io.Closeable target) {
        if (target == null) return;
        try { target.close(); } catch (IOException ignored) { }
    }
}
