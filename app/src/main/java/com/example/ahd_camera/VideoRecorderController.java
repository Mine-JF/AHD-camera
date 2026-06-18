package com.example.ahd_camera;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class VideoRecorderController {

    interface Callback {
        void onRecordingStarted(Uri uri);

        void onRecordingStopped(Uri uri);

        void onRecordingFailed(String error);
    }

    private static final String TAG = "VideoRecorderController";
    private static final String MIME_TYPE = "video/avc";
    private static final int FRAME_RATE = 10;
    private static final int I_FRAME_INTERVAL = 1;
    private static final long FRAME_INTERVAL_MS = 1000L / FRAME_RATE;

    private final Context context;
    private final Handler mainHandler = new Handler(android.os.Looper.getMainLooper());

    private HandlerThread recorderThread;
    private Handler recorderHandler;
    private boolean recording;
    private MediaCodec encoder;
    private MediaMuxer muxer;
    private ParcelFileDescriptor fileDescriptor;
    private Uri outputUri;
    private int trackIndex = -1;
    private long frameIndex;
    private int videoWidth;
    private int videoHeight;
    private View sourceView;
    private View overlayView;
    private Callback callback;
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    VideoRecorderController(Context context) {
        this.context = context.getApplicationContext();
    }

    void toggleRecording(View sourceView, View overlayView, Callback callback) {
        if (recording) {
            stopRecording();
        } else {
            start(sourceView, overlayView, callback);
        }
    }

    boolean isRecording() {
        return recording;
    }

    private void start(View sourceView, View overlayView, Callback callback) {
        this.sourceView = sourceView;
        this.overlayView = overlayView;
        this.callback = callback;
        if (sourceView == null || sourceView.getWidth() <= 0 || sourceView.getHeight() <= 0) {
            notifyRecordingFailed("preview view is not ready");
            return;
        }

        try {
            videoWidth = even(sourceView.getWidth());
            videoHeight = even(sourceView.getHeight());
            outputUri = createVideoUri();
            fileDescriptor = context.getContentResolver().openFileDescriptor(outputUri, "rw");
            if (fileDescriptor == null) {
                throw new IOException("openFileDescriptor returned null");
            }

            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, videoWidth, videoHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_BIT_RATE, videoWidth * videoHeight * 4);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);

            encoder = MediaCodec.createEncoderByType(MIME_TYPE);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            muxer = new MediaMuxer(
                    fileDescriptor.getFileDescriptor(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            );

            recorderThread = new HandlerThread("video-recorder-thread");
            recorderThread.start();
            recorderHandler = new Handler(recorderThread.getLooper());
            trackIndex = -1;
            frameIndex = 0;
            recording = true;

            Log.i(TAG, "start: uri=" + outputUri + ", size=" + videoWidth + "x" + videoHeight);
            notifyRecordingStarted(outputUri);
            recorderHandler.post(captureFrameRunnable);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "start: failed", e);
            releaseResources(true);
            notifyRecordingFailed(e.getMessage() == null ? "start recording failed" : e.getMessage());
        }
    }

    private final Runnable captureFrameRunnable = new Runnable() {
        @Override
        public void run() {
            if (!recording || sourceView == null) {
                return;
            }
            mainHandler.post(() -> {
                try {
                    Bitmap bitmap = PreviewFrameComposer.compose(sourceView, overlayView);
                    if (recorderHandler != null) {
                        recorderHandler.post(() -> encodeBitmap(bitmap));
                    } else {
                        bitmap.recycle();
                    }
                } catch (RuntimeException e) {
                    Log.e(TAG, "captureFrameRunnable: capture failed", e);
                    stopRecordingWithError(e.getMessage() == null ? "capture frame failed" : e.getMessage());
                }
            });
            if (recorderHandler != null) {
                recorderHandler.postDelayed(this, FRAME_INTERVAL_MS);
            }
        }
    };

    private void encodeBitmap(Bitmap bitmap) {
        if (!recording || encoder == null) {
            bitmap.recycle();
            return;
        }
        try {
            int inputIndex = encoder.dequeueInputBuffer(10_000);
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer = encoder.getInputBuffer(inputIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    byte[] yuvData = argbToNv12(bitmap, videoWidth, videoHeight);
                    inputBuffer.put(yuvData);
                    long ptsUs = frameIndex * 1_000_000L / FRAME_RATE;
                    encoder.queueInputBuffer(inputIndex, 0, yuvData.length, ptsUs, 0);
                    frameIndex++;
                }
            }
            bitmap.recycle();
            drainEncoder(false);
        } catch (RuntimeException e) {
            bitmap.recycle();
            Log.e(TAG, "encodeBitmap: failed", e);
            stopRecordingWithError(e.getMessage() == null ? "encode frame failed" : e.getMessage());
        }
    }

    void stopRecording() {
        if (!recording) {
            Log.d(TAG, "stopRecording: not recording");
            return;
        }
        recording = false;
        if (recorderHandler != null) {
            recorderHandler.removeCallbacksAndMessages(null);
            recorderHandler.post(() -> {
                drainEncoder(true);
                releaseResources(false);
                notifyRecordingStopped(outputUri);
            });
        } else {
            releaseResources(false);
            notifyRecordingStopped(outputUri);
        }
    }

    private void stopRecordingWithError(String error) {
        Log.e(TAG, "stopRecordingWithError: " + error);
        recording = false;
        releaseResources(true);
        notifyRecordingFailed(error);
    }

    private void drainEncoder(boolean endOfStream) {
        if (encoder == null) {
            return;
        }
        if (endOfStream) {
            int inputIndex = encoder.dequeueInputBuffer(10_000);
            if (inputIndex >= 0) {
                encoder.queueInputBuffer(inputIndex, 0, 0,
                        frameIndex * 1_000_000L / FRAME_RATE,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
        }

        while (true) {
            int outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) {
                    break;
                }
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxer != null && trackIndex == -1) {
                    trackIndex = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    Log.d(TAG, "drainEncoder: muxer started, trackIndex=" + trackIndex);
                }
            } else if (outputIndex >= 0) {
                ByteBuffer outputBuffer = encoder.getOutputBuffer(outputIndex);
                if (outputBuffer != null && bufferInfo.size > 0 && muxer != null && trackIndex >= 0) {
                    outputBuffer.position(bufferInfo.offset);
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                    muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo);
                }
                encoder.releaseOutputBuffer(outputIndex, false);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }
    }

    private Uri createVideoUri() throws IOException {
        String volumeName = StorageUtils.getPreferredRemovableVolumeName(context);
        if (volumeName == null) {
            throw new IOException("no removable storage");
        }
        String fileName = "VID_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date()) + ".mp4";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AHD_Camera");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(MediaStore.Video.Media.getContentUri(volumeName), values);
        if (uri == null) {
            throw new IOException("createVideoUri insert returned null");
        }
        return uri;
    }

    private void releaseResources(boolean deleteOutput) {
        Log.d(TAG, "releaseResources: deleteOutput=" + deleteOutput + ", uri=" + outputUri);
        if (encoder != null) {
            try {
                encoder.stop();
            } catch (RuntimeException ignored) {
            }
            encoder.release();
            encoder = null;
        }
        if (muxer != null) {
            try {
                if (trackIndex >= 0) {
                    muxer.stop();
                }
            } catch (RuntimeException ignored) {
            }
            muxer.release();
            muxer = null;
        }
        if (fileDescriptor != null) {
            try {
                fileDescriptor.close();
            } catch (IOException ignored) {
            }
            fileDescriptor = null;
        }
        if (outputUri != null) {
            if (deleteOutput) {
                context.getContentResolver().delete(outputUri, null, null);
            } else {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
                context.getContentResolver().update(outputUri, values, null, null);
            }
        }
        if (recorderHandler != null) {
            recorderHandler.removeCallbacksAndMessages(null);
            recorderHandler = null;
        }
        if (recorderThread != null) {
            recorderThread.quitSafely();
            recorderThread = null;
        }
        trackIndex = -1;
        frameIndex = 0;
        sourceView = null;
        overlayView = null;
    }

    private int even(int value) {
        return value % 2 == 0 ? value : value - 1;
    }

    private byte[] argbToNv12(Bitmap bitmap, int width, int height) {
        int[] argb = new int[width * height];
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);
        byte[] yuv = new byte[width * height * 3 / 2];
        int yIndex = 0;
        int uvIndex = width * height;

        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int color = argb[j * width + i];
                int r = (color >> 16) & 0xFF;
                int g = (color >> 8) & 0xFF;
                int b = color & 0xFF;

                int y = clamp((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                int u = clamp(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
                int v = clamp(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);

                yuv[yIndex++] = (byte) clamp(y);
                if (j % 2 == 0 && i % 2 == 0) {
                    yuv[uvIndex++] = (byte) u;
                    yuv[uvIndex++] = (byte) v;
                }
            }
        }
        return yuv;
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private void notifyRecordingStarted(Uri uri) {
        if (callback == null || uri == null) {
            return;
        }
        mainHandler.post(() -> callback.onRecordingStarted(uri));
    }

    private void notifyRecordingStopped(Uri uri) {
        if (callback == null || uri == null) {
            return;
        }
        mainHandler.post(() -> callback.onRecordingStopped(uri));
    }

    private void notifyRecordingFailed(String error) {
        if (callback == null) {
            return;
        }
        mainHandler.post(() -> callback.onRecordingFailed(error));
    }
}
