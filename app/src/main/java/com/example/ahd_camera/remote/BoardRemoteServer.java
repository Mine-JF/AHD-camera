package com.example.ahd_camera.remote;

import android.os.Handler;
import android.os.Process;
import android.os.SystemClock;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** One control owner; additional phones may watch and are promoted when the owner leaves. */
final class BoardRemoteServer {
    interface CommandListener {
        void onCommand(String command);
    }

    interface ControlStateProvider {
        String getState();
    }

    static final int CONTROL_PORT = 39001;
    static final int VIDEO_PORT = 39002;
    private static final String TAG = "BoardRemoteServer";
    private static final long HEARTBEAT_TIMEOUT_MS = 10000;
    private static final int MAX_VIDEO_VIEWERS = 4;

    private final Handler mainHandler;
    private volatile View previewRoot;
    private volatile View overlay;
    private volatile int previewRotationDegrees;
    private final CommandListener listener;
    private final ControlStateProvider stateProvider;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean videoPaused = new AtomicBoolean(true);
    private ServerSocket controlServer;
    private ServerSocket videoServer;
    private final Object controlLock = new Object();
    private final Set<ControlSession> controlSessions = new LinkedHashSet<>();
    private final Set<Socket> videoClients = ConcurrentHashMap.newKeySet();
    private ControlSession controlOwner;

    private static final class ControlSession {
        final Socket socket;
        final BufferedWriter output;
        final Object writeLock = new Object();
        volatile boolean controller;

        ControlSession(Socket socket) throws IOException {
            this.socket = socket;
            output = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        void reply(String line) throws IOException {
            synchronized (writeLock) {
                output.write(line);
                output.write('\n');
                output.flush();
            }
        }
    }

    BoardRemoteServer(Handler mainHandler, CommandListener listener,
            ControlStateProvider stateProvider) {
        this.mainHandler = mainHandler;
        this.listener = listener;
        this.stateProvider = stateProvider;
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
            closeAllClients();
            Log.i(TAG, "board backgrounded: remote clients disconnected");
        }
    }

    void stop() {
        Log.i(TAG, "stopping LAN server");
        running.set(false);
        closeAllClients();
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
                    socket.setSoTimeout(1000);
                    socket.setTcpNoDelay(true);
                    ControlSession session = new ControlSession(socket);
                    synchronized (controlLock) {
                        controlSessions.add(session);
                        if (controlOwner == null) {
                            controlOwner = session;
                            session.controller = true;
                        }
                    }
                    try {
                        session.reply(session.controller ? "ROLE:CONTROL" : "ROLE:VIEW_ONLY");
                        session.reply(stateProvider.getState());
                        Log.i(TAG, "control connected: " + socket.getInetAddress()
                                + ", role=" + (session.controller ? "CONTROL" : "VIEW_ONLY"));
                        new Thread(() -> {
                            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
                            serveControlClient(session);
                        },
                                "remote-control-client").start();
                    } catch (IOException error) {
                        removeControlSession(session);
                        close(socket);
                        throw error;
                    }
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

    private void serveControlClient(ControlSession session) {
        try (Socket client = session.socket) {
            handleControlClient(session);
        } catch (IOException error) {
            if (running.get() && !videoPaused.get()) {
                Log.w(TAG, "control connection ended", error);
            }
        } finally {
            Log.i(TAG, "control client disconnected: " + session.socket.getInetAddress());
            removeControlSession(session);
        }
    }

    private void handleControlClient(ControlSession session) throws IOException {
        Socket socket = session.socket;
        AtomicBoolean uiTransitionPending = new AtomicBoolean();
        BufferedReader input = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.UTF_8));
        long lastHeartbeat = android.os.SystemClock.elapsedRealtime();
        while (running.get() && !socket.isClosed()) {
            try {
                String line = input.readLine();
                if (line == null) {
                    Log.i(TAG, "control client closed stream");
                    break;
                }
                if ("PING".equals(line)) {
                    lastHeartbeat = android.os.SystemClock.elapsedRealtime();
                    session.reply("PONG");
                    session.reply(stateProvider.getState());
                } else if (isAllowed(line)) {
                    long receivedAt = SystemClock.elapsedRealtimeNanos();
                    lastHeartbeat = android.os.SystemClock.elapsedRealtime();
                    if (!session.controller) {
                        session.reply("READ_ONLY");
                        continue;
                    }
                    if (!line.endsWith("_KEEPALIVE")) {
                        Log.i(TAG, "control command received: " + line);
                    }
                    if (isDirectionCommand(line) && !uiTransitionPending.get()) {
                        listener.onCommand(line);
                        if (!line.endsWith("_KEEPALIVE")) {
                            Log.i(TAG, "control timing " + line + ": dispatchMs="
                                    + (SystemClock.elapsedRealtimeNanos() - receivedAt) / 1_000_000f);
                        }
                    } else {
                        if ("MENU".equals(line)) uiTransitionPending.set(true);
                        mainHandler.post(() -> {
                            try {
                                listener.onCommand(line);
                            } finally {
                                if ("MENU".equals(line)) uiTransitionPending.set(false);
                            }
                        });
                    }
                    session.reply("OK");
                } else {
                    Log.w(TAG, "control command rejected: " + line);
                    session.reply("ERR");
                }
            } catch (SocketTimeoutException timeout) {
                if (android.os.SystemClock.elapsedRealtime() - lastHeartbeat > HEARTBEAT_TIMEOUT_MS) {
                    Log.w(TAG, "control heartbeat expired");
                    break;
                }
            }
        }
    }

    private void removeControlSession(ControlSession session) {
        ControlSession promoted = null;
        boolean ownerLeft;
        synchronized (controlLock) {
            if (!controlSessions.remove(session)) return;
            ownerLeft = controlOwner == session;
            if (ownerLeft) {
                controlOwner = null;
                session.controller = false;
                if (running.get() && !videoPaused.get()) {
                    for (ControlSession candidate : controlSessions) {
                        if (!candidate.socket.isClosed()) {
                            controlOwner = candidate;
                            candidate.controller = true;
                            promoted = candidate;
                            break;
                        }
                    }
                }
            }
        }
        if (ownerLeft && running.get()) {
            mainHandler.post(() -> listener.onCommand("STOP_ALL"));
        }
        if (promoted != null) {
            try {
                promoted.reply("ROLE:CONTROL");
                Log.i(TAG, "promoted viewer to controller: "
                        + promoted.socket.getInetAddress());
            } catch (IOException error) {
                close(promoted.socket);
            }
        }
    }

    private void closeAllClients() {
        List<Socket> clients = new ArrayList<>();
        boolean hadController;
        synchronized (controlLock) {
            hadController = controlOwner != null;
            for (ControlSession session : controlSessions) clients.add(session.socket);
            controlSessions.clear();
            controlOwner = null;
        }
        clients.addAll(videoClients);
        for (Socket socket : clients) close(socket);
        if (hadController && running.get()) {
            mainHandler.post(() -> listener.onCommand("STOP_ALL"));
        }
    }

    private static boolean isAllowed(String command) {
        switch (command) {
            case "UP_DOWN": case "UP_UP":
            case "UP_KEEPALIVE":
            case "DOWN_DOWN": case "DOWN_UP":
            case "DOWN_KEEPALIVE":
            case "LEFT_DOWN": case "LEFT_UP":
            case "LEFT_KEEPALIVE":
            case "RIGHT_DOWN": case "RIGHT_UP":
            case "RIGHT_KEEPALIVE":
            case "FOCUS_PLUS_DOWN": case "FOCUS_PLUS_UP":
            case "FOCUS_MINUS_DOWN": case "FOCUS_MINUS_UP":
            case "PHOTO": case "RECORD": case "STOP_ALL":
            case "RESET_DISTANCE": case "LIGHT":
            case "MENU": case "BACK": case "BACK_LONG": case "MIC":
            case "ROTATE": case "OK": case "HOME":
                return true;
            default:
                return false;
        }
    }

    private static boolean isDirectionCommand(String command) {
        return command.startsWith("UP_") || command.startsWith("DOWN_")
                || command.startsWith("LEFT_") || command.startsWith("RIGHT_");
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
                    if (videoClients.size() >= MAX_VIDEO_VIEWERS) {
                        Log.w(TAG, "video viewer limit reached; reject " + socket.getInetAddress());
                        close(socket);
                        continue;
                    }
                    videoClients.add(socket);
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
            if (running.get() && videoClients.contains(socket)) {
                Log.w(TAG, "video connection ended", error);
            }
        } finally {
            Log.i(TAG, "video client disconnected");
            videoClients.remove(socket);
        }
    }

    private static void close(java.io.Closeable target) {
        if (target == null) return;
        try { target.close(); } catch (IOException ignored) { }
    }
}
