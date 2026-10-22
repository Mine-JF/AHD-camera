package com.example.ahd_camera;

import com.example.ahd_camera.camera.RemoteCameraFrames;

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

    public static void setRemoteFrameSource(long handle) {
        if (AVAILABLE) nativeSetRemoteFrameSource(handle);
    }

    public static void setRemoteFramesEnabled(boolean enabled) {
        if (AVAILABLE) nativeSetRemoteFramesEnabled(enabled);
    }

    // Called by the V4L2 capture thread; never touches an Activity or the UI thread.
    private static void onRemoteFrame(byte[] nv12) {
        RemoteCameraFrames.offer(nv12);
    }

    public static boolean isPreviewActive(long handle) {
        return AVAILABLE && handle != 0L && nativeIsPreviewActive(handle);
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

    /** Opens the hardware RS485 UART used by the camera pan/tilt controller. */
    public static boolean openMotorController(String devicePath) {
        if (!AVAILABLE) {
            Log.w(TAG, "openMotorController skipped: " + getAvailabilitySummary());
            return false;
        }
        boolean opened = nativeMotorOpen(devicePath);
        if (!opened) {
            Log.e(TAG, "openMotorController failed: " + getLastNativeError());
        }
        return opened;
    }

    /** Sends one Motorcontrl_sensor command. Commands 0x05/0x06 and lamp-on commands carry a level. */
    public static boolean sendMotorCommand(int command, int level) {
        if (!AVAILABLE) {
            return false;
        }
        boolean sent = nativeMotorSend(command, level);
        if (!sent) {
            Log.e(TAG, "sendMotorCommand failed: command=" + command
                    + ", error=" + getLastNativeError());
        }
        return sent;
    }

    public static void closeMotorController() {
        if (AVAILABLE) {
            nativeMotorClose();
        }
    }

    /** Sends the DnkCounter (RS485 device id 0x02) reset command. */
    public static boolean resetDistanceCounter() {
        if (!AVAILABLE) {
            return false;
        }
        boolean sent = nativeCounterReset();
        if (!sent) {
            Log.e(TAG, "resetDistanceCounter failed: " + getLastNativeError());
        }
        return sent;
    }

    /** Reads a signed DnkCounter active-report frame without sending CMD_QUERY. */
    public static Integer readDistanceCounter() {
        if (!AVAILABLE) {
            return null;
        }
        long value = nativeCounterRead();
        if (value == Long.MIN_VALUE) {
            // 主动上报模式下，静止时没有新帧属于正常状态。
            return null;
        }
        return (int) value;
    }

    /**
     * Returns 1 when the AHD input has a valid signal, 0 when it is unplugged,
     * and -1 when its status cannot be read.
     *
     * The currently deployed ARK7128 driver reports RKMODULE hotplug bits in
     * the opposite direction, so the native implementation normalizes it here.
     */
    public static int getAhdInputPresence(String subDevicePath) {
        if (!AVAILABLE || subDevicePath == null || subDevicePath.isEmpty()) {
            return -1;
        }
        return nativeGetAhdInputPresence(subDevicePath);
    }

    private static native long nativeOpen(String devicePath, int width, int height, int pixelFormat);

    private static native int nativeStartPreview(long handle, Surface surface);
    private static native void nativeSetRemoteFrameSource(long handle);
    private static native void nativeSetRemoteFramesEnabled(boolean enabled);

    private static native boolean nativeIsPreviewActive(long handle);

    private static native void nativeStopPreview(long handle);

    private static native void nativeClose(long handle);

    private static native String nativeGetLastError();

    private static native boolean nativeMotorOpen(String devicePath);

    private static native boolean nativeMotorSend(int command, int level);

    private static native void nativeMotorClose();

    private static native boolean nativeCounterReset();

    private static native long nativeCounterRead();

    private static native int nativeGetAhdInputPresence(String subDevicePath);
}
