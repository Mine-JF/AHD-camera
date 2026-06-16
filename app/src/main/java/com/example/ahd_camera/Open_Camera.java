package com.example.ahd_camera;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.Comparator;

/**
 * File: Open_Camera.java
 * Author: mine
 * Create: 2026/6/16 16:37
 * Changes (from 2026/6/16)
 */
public class Open_Camera {

    public interface StatusCallback {
        void onCameraError();
    }

    private final CameraManager cameraManager;
    private final StatusCallback statusCallback;

    private HandlerThread cameraThread;
    private Handler cameraHandler;

    private final CameraSlot leftSlot = new CameraSlot();
    private final CameraSlot rightSlot = new CameraSlot();

    public Open_Camera(Context context, StatusCallback statusCallback) {
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.statusCallback = statusCallback;
    }

    // 绑定左右两个预览窗口，并在 Surface 可用时尝试打开对应摄像头。
    public void bindPreviews(TextureView leftPreview, TextureView rightPreview) {
        leftSlot.previewView = leftPreview;
        rightSlot.previewView = rightPreview;

        leftPreview.setSurfaceTextureListener(buildSurfaceListener(leftSlot));
        rightPreview.setSurfaceTextureListener(buildSurfaceListener(rightSlot));
    }

    // 启动相机专用后台线程，避免相机回调阻塞主线程。
    public void startBackgroundThread() {
        if (cameraThread != null) {
            return;
        }
        cameraThread = new HandlerThread("dual-camera-thread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    // 关闭相机后台线程，通常在页面进入 onPause 时调用。
    public void stopBackgroundThread() {
        if (cameraThread == null) {
            return;
        }
        cameraThread.quitSafely();
        try {
            cameraThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cameraThread = null;
        cameraHandler = null;
    }

    // 获取最多两路可用摄像头，优先返回外接摄像头，其次回退到系统普通摄像头。
    public String[] getSelectedCameraIds() {
        java.util.List<String> externalIds = new java.util.ArrayList<>();
        java.util.List<String> fallbackIds = new java.util.ArrayList<>();

        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                try {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                    StreamConfigurationMap map =
                            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map == null || map.getOutputSizes(SurfaceTexture.class) == null) {
                        continue;
                    }

                    Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                        externalIds.add(cameraId);
                    } else {
                        fallbackIds.add(cameraId);
                    }
                } catch (CameraAccessException | IllegalArgumentException e) {
                    // Skip bad camera ids so the page can still open.
                }
            }
        } catch (CameraAccessException e) {
            return new String[0];
        }

        java.util.List<String> selected = !externalIds.isEmpty() ? externalIds : fallbackIds;
        int count = Math.min(selected.size(), 2);
        return selected.subList(0, count).toArray(new String[0]);
    }

    // 按照传入的 cameraId 列表打开对应摄像头，并分别绑定到左右预览窗口。
    public void openSelectedCameras(String[] cameraIds) {
        closeAllCameras();

        if (cameraIds.length > 0) {
            leftSlot.cameraId = cameraIds[0];
            maybeOpenCamera(leftSlot);
        }

        if (cameraIds.length > 1) {
            rightSlot.cameraId = cameraIds[1];
            maybeOpenCamera(rightSlot);
        }
    }

    // 关闭当前已经打开的全部摄像头和预览会话。
    public void closeAllCameras() {
        closeCamera(leftSlot);
        closeCamera(rightSlot);
    }

    private TextureView.SurfaceTextureListener buildSurfaceListener(CameraSlot slot) {
        return new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                maybeOpenCamera(slot);
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                closeCamera(slot);
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            }
        };
    }

    // 只有在预览窗口可用、线程已启动且 cameraId 已设置时才真正发起打开动作。
    private void maybeOpenCamera(CameraSlot slot) {
        if (slot.cameraId == null || slot.previewView == null || !slot.previewView.isAvailable()) {
            return;
        }
        if (slot.cameraDevice != null || cameraHandler == null) {
            return;
        }
        openCamera(slot);
    }

    // 打开单路摄像头，打开成功后继续创建预览会话。
    private void openCamera(CameraSlot slot) {
        try {
            cameraManager.openCamera(slot.cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    slot.cameraDevice = camera;
                    createPreviewSession(slot);
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    slot.cameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    slot.cameraDevice = null;
                    notifyCameraError();
                }
            }, cameraHandler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            notifyCameraError();
        }
    }

    // 为当前摄像头创建预览会话，并持续输出画面到 TextureView。
    private void createPreviewSession(CameraSlot slot) {
        try {
            SurfaceTexture texture = slot.previewView.getSurfaceTexture();
            if (texture == null || slot.cameraDevice == null) {
                return;
            }

            Size previewSize = choosePreviewSize(slot.cameraId, slot.previewView.getWidth(), slot.previewView.getHeight());
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(texture);
            slot.previewSurface = surface;

            CaptureRequest.Builder previewRequestBuilder =
                    slot.cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            slot.cameraDevice.createCaptureSession(
                    Arrays.asList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (slot.cameraDevice == null) {
                                return;
                            }
                            slot.captureSession = session;
                            try {
                                previewRequestBuilder.set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                                );
                                session.setRepeatingRequest(previewRequestBuilder.build(), null, cameraHandler);
                            } catch (CameraAccessException e) {
                                notifyCameraError();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            notifyCameraError();
                        }
                    },
                    cameraHandler
            );
        } catch (CameraAccessException | IllegalArgumentException e) {
            notifyCameraError();
        }
    }

    // 根据预览窗口尺寸选择一个尽量接近的输出分辨率，减少画面拉伸。
    private Size choosePreviewSize(String cameraId, int viewWidth, int viewHeight) throws CameraAccessException {
        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] outputSizes = map != null ? map.getOutputSizes(SurfaceTexture.class) : null;

        if (outputSizes == null || outputSizes.length == 0) {
            return new Size(1920, 1080);
        }

        float targetRatio = viewHeight == 0 ? (16f / 9f) : (float) viewWidth / (float) viewHeight;
        return Arrays.stream(outputSizes)
                .min(Comparator
                        .comparingDouble((Size size) -> Math.abs(((float) size.getWidth() / size.getHeight()) - targetRatio))
                .thenComparingInt(size -> Math.abs(size.getWidth() - viewWidth) + Math.abs(size.getHeight() - viewHeight)))
                .orElse(outputSizes[0]);
    }

    // 关闭单路摄像头相关资源，包含会话、设备句柄和预览 Surface。
    private void closeCamera(CameraSlot slot) {
        if (slot.captureSession != null) {
            slot.captureSession.close();
            slot.captureSession = null;
        }
        if (slot.cameraDevice != null) {
            slot.cameraDevice.close();
            slot.cameraDevice = null;
        }
        if (slot.previewSurface != null) {
            slot.previewSurface.release();
            slot.previewSurface = null;
        }
        slot.cameraId = null;
    }

    // 统一上报打开失败或预览失败，交给页面层决定如何提示用户。
    private void notifyCameraError() {
        if (statusCallback != null) {
            statusCallback.onCameraError();
        }
    }

    private static final class CameraSlot {
        private TextureView previewView;
        private String cameraId;
        private CameraDevice cameraDevice;
        private CameraCaptureSession captureSession;
        private Surface previewSurface;
    }
}
