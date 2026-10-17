package com.example.ahd_camera;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.util.Log;
import android.view.View;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.function.Supplier;
import java.nio.ByteBuffer;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Encodes native camera frames for the phone; other pages use composed screen frames. */
final class H264PreviewStreamer {
    private static final String TAG = "H264PreviewStreamer";
    static final int MAGIC = 0x41484431; // AHD1
    static final int WIDTH = 640;
    static final int HEIGHT = 360;
    private static final int FPS = 30;

    private final Handler mainHandler;
    private final Supplier<View> previewRoot;
    private final Supplier<View> overlay;

    H264PreviewStreamer(Handler mainHandler, Supplier<View> previewRoot, Supplier<View> overlay) {
        this.mainHandler = mainHandler;
        this.previewRoot = previewRoot;
        this.overlay = overlay;
    }

    void stream(Socket socket, AtomicBoolean running, AtomicBoolean paused) throws IOException {
        MediaCodec codec = null;
        boolean frameSourceActive = false;
        try {
            Log.i(TAG, "starting H.264 stream: " + WIDTH + "x" + HEIGHT + " @ " + FPS + "fps");
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", WIDTH, HEIGHT);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 1_200_000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            codec = MediaCodec.createEncoderByType("video/avc");
            Log.i(TAG, "encoder=" + codec.getName());
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            Log.i(TAG, "encoder started");

            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeInt(MAGIC);
            output.writeInt(WIDTH);
            output.writeInt(HEIGHT);
            output.flush();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long packets = 0;
            long nextFrameAt = android.os.SystemClock.elapsedRealtime();
            long lastDirectFrameAt = 0;
            long lastSequence = -1;
            long streamStartNs = android.os.SystemClock.elapsedRealtimeNanos();
            long statsStartMs = android.os.SystemClock.elapsedRealtime();
            int directFrames = 0;
            int captureFrames = 0;
            RemoteCameraFrames.setActive(true);
            frameSourceActive = true;
            while (running.get() && !socket.isClosed()) {
                if (paused.get()) {
                    try { Thread.sleep(100); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    nextFrameAt = android.os.SystemClock.elapsedRealtime();
                    continue;
                }
                RemoteCameraFrames.Frame direct = null;
                View root = previewRoot.get();
                if (root != null && root.getId() == R.id.camera_container) {
                    try {
                        direct = RemoteCameraFrames.waitForNext(lastSequence, 80);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                byte[] yuv;
                long now = android.os.SystemClock.elapsedRealtime();
                if (direct != null) {
                    lastSequence = direct.sequence;
                    lastDirectFrameAt = now;
                    yuv = direct.nv12;
                    directFrames++;
                } else {
                    // Settings and other non-camera pages still need screen capture.
                    if (now - lastDirectFrameAt < 300 || now < nextFrameAt) continue;
                    nextFrameAt = now + 100;
                    Bitmap bitmap = capture();
                    if (bitmap == null) continue;
                    try {
                        yuv = toNv12(bitmap);
                        captureFrames++;
                    } finally {
                        bitmap.recycle();
                    }
                }
                int index = codec.dequeueInputBuffer(10_000);
                if (index >= 0) {
                    ByteBuffer input = codec.getInputBuffer(index);
                    if (input != null && input.capacity() >= yuv.length) {
                        input.clear();
                        input.put(yuv);
                        long ptsUs = (android.os.SystemClock.elapsedRealtimeNanos()
                                - streamStartNs) / 1000;
                        codec.queueInputBuffer(index, 0, yuv.length, ptsUs, 0);
                    }
                }
                while (true) {
                    int outputIndex = codec.dequeueOutputBuffer(info, 0);
                    if (outputIndex < 0) break;
                    ByteBuffer encoded = codec.getOutputBuffer(outputIndex);
                    if (encoded != null && info.size > 0) {
                        byte[] data = new byte[info.size];
                        encoded.position(info.offset);
                        encoded.limit(info.offset + info.size);
                        encoded.get(data);
                        output.writeInt(data.length);
                        output.writeInt(info.flags);
                        output.writeLong(info.presentationTimeUs);
                        output.write(data);
                        output.flush();
                        if (++packets == 1 || packets % 100 == 0) {
                            Log.i(TAG, "encoded packets sent=" + packets + ", bytes=" + data.length
                                    + ", flags=" + info.flags);
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false);
                }
                long elapsedMs = android.os.SystemClock.elapsedRealtime() - statsStartMs;
                if (elapsedMs >= 5000) {
                    Log.i(TAG, "stream input fps: direct=" + directFrames * 1000f / elapsedMs
                            + ", capture=" + captureFrames * 1000f / elapsedMs
                            + ", page=" + (root == null ? "none" : root.getId())
                            + ", encodedPackets=" + packets);
                    statsStartMs = android.os.SystemClock.elapsedRealtime();
                    directFrames = 0;
                    captureFrames = 0;
                }
            }
        } catch (Exception error) {
            Log.e(TAG, "H.264 stream failed", error);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("H.264 streaming failed", error);
        } finally {
            if (frameSourceActive) RemoteCameraFrames.setActive(false);
            Log.i(TAG, "H.264 stream stopped");
            if (codec != null) {
                try { codec.stop(); } catch (RuntimeException ignored) { }
                codec.release();
            }
        }
    }

    private Bitmap capture() {
        FutureTask<Bitmap> task = new FutureTask<>(() -> {
            View root = previewRoot.get();
            if (root == null || !root.isAttachedToWindow() || root.getWidth() == 0) return null;
            Bitmap full = PreviewFrameComposer.compose(root, overlay.get());
            Bitmap scaled = Bitmap.createScaledBitmap(full, WIDTH, HEIGHT, true);
            if (scaled != full) full.recycle();
            return scaled;
        });
        mainHandler.post(task);
        try {
            return task.get(700, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            task.cancel(false);
            return null;
        }
    }

    private static byte[] toNv12(Bitmap bitmap) {
        int[] pixels = new int[WIDTH * HEIGHT];
        bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
        byte[] yuv = new byte[WIDTH * HEIGHT * 3 / 2];
        int uv = WIDTH * HEIGHT;
        for (int row = 0; row < HEIGHT; row++) {
            for (int col = 0; col < WIDTH; col++) {
                int color = pixels[row * WIDTH + col];
                int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
                yuv[row * WIDTH + col] = (byte) clamp(((66*r + 129*g + 25*b + 128) >> 8) + 16);
                if ((row & 1) == 0 && (col & 1) == 0) {
                    yuv[uv++] = (byte) clamp(((-38*r - 74*g + 112*b + 128) >> 8) + 128);
                    yuv[uv++] = (byte) clamp(((112*r - 94*g - 18*b + 128) >> 8) + 128);
                }
            }
        }
        return yuv;
    }

    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }
}
