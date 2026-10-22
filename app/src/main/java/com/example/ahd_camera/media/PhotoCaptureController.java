package com.example.ahd_camera.media;

import com.example.ahd_camera.camera.PreviewFrameComposer;
import com.example.ahd_camera.storage.StorageUtils;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PhotoCaptureController {

    public interface Callback {
        void onPhotoSaved(Uri uri);

        void onPhotoSaveFailed(String error);
    }

    private static final String TAG = "PhotoCaptureController";

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    public PhotoCaptureController(Context context) {
        this.context = context.getApplicationContext();
    }

    // 在主线程抓取当前预览视图位图，再异步写入系统图片库。
    public void capture(View sourceView, View overlayView, Callback callback) {
        if (sourceView == null || sourceView.getWidth() <= 0 || sourceView.getHeight() <= 0) {
            Log.e(TAG, "capture: invalid sourceView");
            callback.onPhotoSaveFailed("preview view is not ready");
            return;
        }

        try {
            Bitmap bitmap = PreviewFrameComposer.compose(sourceView, overlayView);
            Log.d(TAG, "capture: bitmap created size=" + bitmap.getWidth() + "x" + bitmap.getHeight());
            ioExecutor.execute(() -> saveBitmap(bitmap, callback));
        } catch (RuntimeException e) {
            Log.e(TAG, "capture: create bitmap failed", e);
            callback.onPhotoSaveFailed(e.getMessage() == null ? "capture failed" : e.getMessage());
        }
    }

    private void saveBitmap(Bitmap bitmap, Callback callback) {
        String volumeName = StorageUtils.getPreferredRemovableVolumeName(context);
        if (volumeName == null) {
            Log.e(TAG, "saveBitmap: no removable storage");
            mainHandler.post(() -> callback.onPhotoSaveFailed("please insert usb storage"));
            bitmap.recycle();
            return;
        }
        String fileName = buildFileName("IMG_", ".jpg");
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AHD_Camera");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = null;
        try {
            uri = context.getContentResolver().insert(MediaStore.Images.Media.getContentUri(volumeName), values);
            if (uri == null) {
                throw new IOException("insert returned null");
            }
            try (OutputStream outputStream = context.getContentResolver().openOutputStream(uri, "w")) {
                if (outputStream == null) {
                    throw new IOException("openOutputStream returned null");
                }
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, outputStream)) {
                    throw new IOException("bitmap compress failed");
                }
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);
            Log.i(TAG, "saveBitmap: saved uri=" + uri);
            Uri savedUri = uri;
            mainHandler.post(() -> callback.onPhotoSaved(savedUri));
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "saveBitmap: failed", e);
            if (uri != null) {
                context.getContentResolver().delete(uri, null, null);
            }
            String error = e.getMessage() == null ? "save photo failed" : e.getMessage();
            mainHandler.post(() -> callback.onPhotoSaveFailed(error));
        } finally {
            bitmap.recycle();
        }
    }

    private String buildFileName(String prefix, String suffix) {
        return prefix + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + suffix;
    }
}
