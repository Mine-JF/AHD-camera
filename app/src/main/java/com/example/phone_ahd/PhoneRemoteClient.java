package com.example.phone_ahd;

import android.graphics.SurfaceTexture;
import android.graphics.Matrix;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Phone side of the simple LAN protocol. Network and codec work never run on the UI thread. */
final class PhoneRemoteClient {
    interface StatusListener { void onStatus(String status); }
    interface RoleListener { void onRoleChanged(boolean canControl); }
    interface StateListener { void onStateChanged(boolean lightOn, boolean recording); }
    private static final String TAG = "PhoneRemoteClient";
    private static final int MAGIC = 0x41484431;
    private static final int ROTATION_MARKER = 0x41484452;
    private static final int CONTROL_PORT = 39001;
    private static final int VIDEO_PORT = 39002;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService commands = Executors.newSingleThreadExecutor(task ->
            new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
                task.run();
            }, "phone-control-send"));
    private final TextureView preview;
    private final StatusListener listener;
    private final RoleListener roleListener;
    private final StateListener stateListener;
    private volatile boolean running;
    private volatile boolean canControl;
    private volatile long connectionGeneration;
    private volatile Socket controlSocket;
    private volatile Socket videoSocket;
    private volatile BufferedWriter commandWriter;
    private Thread videoThread;
    private Thread heartbeatThread;
    private int videoWidth;
    private int videoHeight;
    private volatile int videoRotationDegrees;
    private volatile long lastPingSentAt;
    private int pingSamples;

    PhoneRemoteClient(TextureView preview, StatusListener listener, RoleListener roleListener,
            StateListener stateListener) {
        this.preview = preview;
        this.listener = listener;
        this.roleListener = roleListener;
        this.stateListener = stateListener;
        preview.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> applyPreviewFit());
    }

    boolean isRunning() {
        return running;
    }

    boolean canControl() {
        return canControl;
    }

    void connect(String host) {
        disconnect();
        long generation = ++connectionGeneration;
        running = true;
        canControl = false;
        mainHandler.post(() -> {
            if (connectionGeneration == generation) stateListener.onStateChanged(false, false);
        });
        videoRotationDegrees = 0;
        Log.i(TAG, "connect requested: host=" + host + ", controlPort=" + CONTROL_PORT
                + ", videoPort=" + VIDEO_PORT);
        commands.execute(() -> {
            if (connectionGeneration != generation) return;
            Socket socket = null;
            try {
                socket = new Socket();
                Log.i(TAG, "connecting control socket to " + host + ":" + CONTROL_PORT);
                socket.connect(new InetSocketAddress(host, CONTROL_PORT), 3000);
                if (!running || connectionGeneration != generation) {
                    close(socket);
                    return;
                }
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(3500); // PING is sent every second; detect a dead board.
                Log.i(TAG, "control connected: local=" + socket.getLocalSocketAddress()
                        + ", remote=" + socket.getRemoteSocketAddress());
                controlSocket = socket;
                commandWriter = new BufferedWriter(new OutputStreamWriter(
                        socket.getOutputStream(), StandardCharsets.UTF_8));
                // Drain board acknowledgements so its TCP send buffer never fills.
                Socket connectedSocket = socket;
                new Thread(() -> readResponses(connectedSocket, generation), "phone-control-replies").start();
                status("控制已连接，正在等待画面…");
                heartbeatThread = new Thread(() -> heartbeatLoop(generation), "phone-control-heartbeat");
                heartbeatThread.start();
                videoThread = new Thread(() -> receiveVideo(host, generation), "phone-h264-video");
                videoThread.start();
            } catch (IOException error) {
                close(socket);
                if (connectionGeneration != generation) return;
                Log.e(TAG, "control connect failed: host=" + host + ":" + CONTROL_PORT, error);
                connectionLost(connectFailure("控制", CONTROL_PORT, error), generation);
            }
        });
    }

    void send(String command) {
        if (!"PING".equals(command) && !canControl) return;
        long queuedAt = SystemClock.elapsedRealtimeNanos();
        commands.execute(() -> {
            if (!running || commandWriter == null
                    || (!"PING".equals(command) && !canControl)) return;
            long generation = connectionGeneration;
            try {
                long writeStartedAt = SystemClock.elapsedRealtimeNanos();
                if (!"PING".equals(command)) Log.i(TAG, "send command: " + command);
                else lastPingSentAt = writeStartedAt;
                commandWriter.write(command);
                commandWriter.write('\n');
                commandWriter.flush();
                if (isDirectionEdge(command)) {
                    long sentAt = SystemClock.elapsedRealtimeNanos();
                    Log.i(TAG, "control timing " + command + ": queueMs="
                            + (writeStartedAt - queuedAt) / 1_000_000f
                            + ", writeMs=" + (sentAt - writeStartedAt) / 1_000_000f);
                }
            } catch (IOException error) {
                Log.e(TAG, "control send failed: command=" + command, error);
                connectionLost("主板控制连接断开", generation);
            }
        });
    }

    private static boolean isDirectionEdge(String command) {
        return (command.startsWith("UP_") || command.startsWith("DOWN_")
                || command.startsWith("LEFT_") || command.startsWith("RIGHT_"))
                && !command.endsWith("_KEEPALIVE");
    }

    void disconnect() {
        connectionGeneration++;
        if (running) Log.i(TAG, "disconnect requested");
        running = false;
        canControl = false;
        close(controlSocket);
        close(videoSocket);
        controlSocket = null;
        videoSocket = null;
        commandWriter = null;
        long clearedGeneration = connectionGeneration;
        mainHandler.post(() -> {
            if (connectionGeneration == clearedGeneration) {
                preview.setAlpha(0f);
                roleListener.onRoleChanged(false);
                stateListener.onStateChanged(false, false);
                Log.i(TAG, "preview hidden after disconnect");
            }
        });
    }

    private void connectionLost(String message, long generation) {
        if (connectionGeneration != generation) return;
        Log.w(TAG, "connection lost: " + message);
        disconnect();
        status(message);
    }

    void shutdown() {
        disconnect();
        commands.shutdownNow();
    }

    private void heartbeatLoop(long generation) {
        while (running && connectionGeneration == generation) {
            send("PING");
            try { Thread.sleep(1000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void readResponses(Socket socket, long generation) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            String reply;
            while (running && connectionGeneration == generation
                    && (reply = reader.readLine()) != null) {
                if ("ROLE:CONTROL".equals(reply)) {
                    setControlRole(true, generation);
                } else if ("ROLE:VIEW_ONLY".equals(reply) || "READ_ONLY".equals(reply)) {
                    setControlRole(false, generation);
                } else if (reply.startsWith("STATE:LIGHT=")) {
                    String[] fields = reply.substring("STATE:".length()).split(";");
                    if (fields.length == 2 && ("LIGHT=0".equals(fields[0]) || "LIGHT=1".equals(fields[0]))
                            && ("RECORD=0".equals(fields[1]) || "RECORD=1".equals(fields[1]))) {
                        boolean lightOn = "LIGHT=1".equals(fields[0]);
                        boolean recording = "RECORD=1".equals(fields[1]);
                        mainHandler.post(() -> {
                            if (connectionGeneration == generation) {
                                stateListener.onStateChanged(lightOn, recording);
                            }
                        });
                    }
                } else if ("PONG".equals(reply)) {
                    long sentAt = lastPingSentAt;
                    if (sentAt != 0 && ++pingSamples % 5 == 0) {
                        Log.i(TAG, "control ping rttMs="
                                + (SystemClock.elapsedRealtimeNanos() - sentAt) / 1_000_000f);
                    }
                } else {
                    Log.i(TAG, "control reply: " + reply);
                }
            }
        } catch (IOException error) {
            if (running) Log.w(TAG, "control reply stream failed", error);
        }
        if (running && connectionGeneration == generation && controlSocket == socket) {
            Log.w(TAG, "control connection closed by board");
            connectionLost("主板控制连接断开", generation);
        }
    }

    private void setControlRole(boolean controller, long generation) {
        if (connectionGeneration != generation) return;
        canControl = controller;
        Log.i(TAG, "control role=" + (controller ? "CONTROL" : "VIEW_ONLY"));
        mainHandler.post(() -> {
            if (connectionGeneration == generation) {
                roleListener.onRoleChanged(controller);
                listener.onStatus(controller ? "已连接：可控制主板" : "已连接：仅观看，另一台手机正在控制");
            }
        });
    }

    private void receiveVideo(String host, long generation) {
        MediaCodec decoder = null;
        Surface surface = null;
        Socket socket = null;
        try {
            socket = new Socket();
            Log.i(TAG, "connecting video socket to " + host + ":" + VIDEO_PORT);
            socket.connect(new InetSocketAddress(host, VIDEO_PORT), 3000);
            if (!running || connectionGeneration != generation) return;
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(5000);
            Log.i(TAG, "video connected: local=" + socket.getLocalSocketAddress()
                    + ", remote=" + socket.getRemoteSocketAddress());
            videoSocket = socket;
            DataInputStream input = new DataInputStream(socket.getInputStream());
            if (input.readInt() != MAGIC) throw new IOException("视频协议不匹配");
            int width = input.readInt(), height = input.readInt();
            socket.setSoTimeout(5000); // A stopped video stream must not leave the last frame visible.
            Log.i(TAG, "video header: size=" + width + "x" + height);
            if (width <= 0 || width > 4096 || height <= 0 || height > 2160) {
                throw new IOException("视频尺寸异常");
            }
            videoWidth = width;
            videoHeight = height;
            mainHandler.post(this::applyPreviewFit);
            FutureTask<Surface> surfaceTask = new FutureTask<>(() -> {
                SurfaceTexture texture = preview.getSurfaceTexture();
                return texture == null ? null : new Surface(texture);
            });
            mainHandler.post(surfaceTask);
            surface = surfaceTask.get(2, TimeUnit.SECONDS);
            if (surface == null) throw new IOException("手机预览画布未就绪");
            decoder = MediaCodec.createDecoderByType("video/avc");
            Log.i(TAG, "video decoder=" + decoder.getName());
            decoder.configure(MediaFormat.createVideoFormat("video/avc", width, height),
                    surface, null, 0);
            decoder.start();
            Log.i(TAG, "video decoder started");
            status(canControl ? "已连接主板画面（可控制）" : "已连接主板画面（仅观看）");
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long frameCount = 0;
            long receivedFrames = 0;
            long displayedFrames = 0;
            boolean firstFrameShown = false;
            long statsStartMs = android.os.SystemClock.elapsedRealtime();
            while (running && connectionGeneration == generation) {
                int length = input.readInt();
                int flags = input.readInt();
                long pts = input.readLong();
                if (length == 0 && flags == ROTATION_MARKER) {
                    if (pts == 0 || pts == 90 || pts == 180 || pts == 270) {
                        videoRotationDegrees = (int) pts;
                        mainHandler.post(this::applyPreviewFit);
                        Log.i(TAG, "video rotation=" + pts);
                    }
                    continue;
                }
                if (length <= 0 || length > 2_000_000) throw new IOException("视频帧长度异常");
                byte[] data = new byte[length];
                input.readFully(data);
                if (++frameCount == 1 || frameCount % 100 == 0) {
                    Log.i(TAG, "video packets received=" + frameCount + ", lastBytes=" + length
                            + ", flags=" + flags);
                }
                if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) receivedFrames++;
                int index = decoder.dequeueInputBuffer(100_000);
                if (index >= 0) {
                    ByteBuffer buffer = decoder.getInputBuffer(index);
                    if (buffer != null) buffer.clear();
                    if (buffer != null && buffer.remaining() >= length) {
                        buffer.put(data);
                        decoder.queueInputBuffer(index, 0, length, pts, flags);
                    } else {
                        decoder.queueInputBuffer(index, 0, 0, pts, 0);
                    }
                }
                while (true) {
                    int output = decoder.dequeueOutputBuffer(info, 0);
                    if (output < 0) break;
                    boolean display = info.size > 0
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
                    decoder.releaseOutputBuffer(output, display);
                    if (display) {
                        displayedFrames++;
                        if (!firstFrameShown) {
                            firstFrameShown = true;
                            mainHandler.post(() -> {
                                if (running && connectionGeneration == generation) preview.setAlpha(1f);
                            });
                        }
                    }
                }
                long nowMs = android.os.SystemClock.elapsedRealtime();
                long elapsedMs = nowMs - statsStartMs;
                if (elapsedMs >= 5000) {
                    float receivedFps = receivedFrames * 1000f / elapsedMs;
                    float displayedFps = displayedFrames * 1000f / elapsedMs;
                    Log.i(TAG, "video fps: received=" + receivedFps
                            + ", displayed=" + displayedFps
                            + ", intervalMs=" + elapsedMs);
                    statsStartMs = nowMs;
                    receivedFrames = 0;
                    displayedFrames = 0;
                }
            }
        } catch (Exception error) {
            if (running && connectionGeneration == generation) {
                Log.e(TAG, "video connection or decoder failed: host=" + host, error);
                connectionLost(error instanceof IOException
                        ? connectFailure("视频", VIDEO_PORT, (IOException) error)
                        : "视频解码失败，查看 PhoneRemoteClient 日志", generation);
            }
        } finally {
            Log.i(TAG, "video receiver stopped");
            if (decoder != null) {
                try { decoder.stop(); } catch (RuntimeException ignored) { }
                decoder.release();
            }
            if (surface != null) surface.release();
            close(socket);
            if (videoSocket == socket) videoSocket = null;
        }
    }

    private void status(String message) {
        Log.i(TAG, "status: " + message);
        long generation = connectionGeneration;
        mainHandler.post(() -> {
            if (connectionGeneration == generation) listener.onStatus(message);
        });
    }

    private void applyPreviewFit() {
        int viewWidth = preview.getWidth();
        int viewHeight = preview.getHeight();
        if (videoWidth <= 0 || videoHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return;
        int rotation = videoRotationDegrees;
        boolean quarterTurn = rotation == 90 || rotation == 270;
        float displayWidth = quarterTurn ? videoHeight : videoWidth;
        float displayHeight = quarterTurn ? videoWidth : videoHeight;
        float fit = Math.min(viewWidth / displayWidth, viewHeight / displayHeight);
        float targetWidth = displayWidth * fit;
        float targetHeight = displayHeight * fit;
        float scaleX = (quarterTurn ? targetHeight : targetWidth) / viewWidth;
        float scaleY = (quarterTurn ? targetWidth : targetHeight) / viewHeight;
        Matrix transform = new Matrix();
        transform.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f);
        transform.postRotate(rotation, viewWidth / 2f, viewHeight / 2f);
        preview.setTransform(transform);
        Log.i(TAG, "preview fit: view=" + viewWidth + "x" + viewHeight
                + ", video=" + videoWidth + "x" + videoHeight + ", rotation=" + rotation);
    }

    private static String connectFailure(String channel, int port, IOException error) {
        if (error instanceof ConnectException) {
            return channel + "连接失败：主板 " + port + " 端口拒绝连接，请检查主板应用和监听端口";
        }
        if (error instanceof SocketTimeoutException) {
            return channel + "连接超时：请检查主板 IP、网络和端口 " + port;
        }
        return channel + "连接失败：" + error.getMessage();
    }

    private static void close(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) { }
    }
}
