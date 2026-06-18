package com.example.ahd_camera;

import android.util.Log;
import android.view.Surface;

public final class V4l2CameraNative {

    private static final String TAG = "V4l2CameraNative";
    private static final String LIB_NAME = "ahd_v4l2";
    private static final boolean AVAILABLE;
    private static final Throwable LOAD_ERROR;

    static {
        boolean loaded = false;
        Throwable error = null;
        try {
            System.loadLibrary(LIB_NAME);
            loaded = true;
        } catch (Throwable t) {
            error = t;
        }
        AVAILABLE = loaded;
        LOAD_ERROR = error;
    }

    private V4l2CameraNative() {
    }

    public static boolean isAvailable() {
        Log.d(TAG, "isAvailable: " + AVAILABLE);
        return AVAILABLE;
    }

    public static String getLoadErrorMessage() {
        if (LOAD_ERROR != null) {
            Log.w(TAG, "getLoadErrorMessage: " + LOAD_ERROR.getMessage());
        }
        return LOAD_ERROR == null ? "" : LOAD_ERROR.getMessage();
    }

    public static String getAvailabilitySummary() {
        if (AVAILABLE) {
            return LIB_NAME + " loaded";
        }
        String message = getLoadErrorMessage();
        if (message == null || message.isEmpty()) {
            return LIB_NAME + " unavailable";
        }
        return LIB_NAME + " unavailable: " + message;
    }

    public static String getLastNativeError() {
        if (!AVAILABLE) {
            return getAvailabilitySummary();
        }
        String error = nativeGetLastError();
        return error == null ? "" : error;
    }

    public static long open(String devicePath, int width, int height, int pixelFormat) {
        if (!AVAILABLE) {
            Log.w(TAG, "open skipped: " + getAvailabilitySummary());
            return 0L;
        }
        Log.d(TAG, "open: devicePath=" + devicePath
                + ", width=" + width
                + ", height=" + height
                + ", pixelFormat=" + pixelFormat);
        long handle = nativeOpen(devicePath, width, height, pixelFormat);
        if (handle == 0L) {
            Log.e(TAG, "open failed: devicePath=" + devicePath + ", error=" + getLastNativeError());
        } else {
            Log.d(TAG, "open result: handle=" + handle + ", devicePath=" + devicePath);
        }
        return handle;
    }

    public static int startPreview(long handle, Surface surface) {
        if (!AVAILABLE) {
            Log.w(TAG, "startPreview skipped: " + getAvailabilitySummary());
            return -1;
        }
        if (handle == 0L || surface == null) {
            Log.w(TAG, "startPreview skipped: invalid args, handle=" + handle + ", surface=" + surface);
            return -1;
        }
        Log.d(TAG, "startPreview: handle=" + handle + ", surface=" + surface);
        int result = nativeStartPreview(handle, surface);
        Log.d(TAG, "startPreview result: handle=" + handle + ", result=" + result);
        return result;
    }

    public static void stopPreview(long handle) {
        if (!AVAILABLE || handle == 0L) {
            Log.d(TAG, "stopPreview skipped: available=" + AVAILABLE + ", handle=" + handle);
            return;
        }
        Log.d(TAG, "stopPreview: handle=" + handle);
        nativeStopPreview(handle);
    }

    public static void close(long handle) {
        if (!AVAILABLE || handle == 0L) {
            Log.d(TAG, "close skipped: available=" + AVAILABLE + ", handle=" + handle);
            return;
        }
        Log.d(TAG, "close: handle=" + handle);
        nativeClose(handle);
    }

    private static native long nativeOpen(String devicePath, int width, int height, int pixelFormat);

    private static native int nativeStartPreview(long handle, Surface surface);

    private static native void nativeStopPreview(long handle);

    private static native void nativeClose(long handle);

    private static native String nativeGetLastError();
}
