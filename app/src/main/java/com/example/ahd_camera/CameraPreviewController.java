package com.example.ahd_camera;

import android.view.TextureView;

public interface CameraPreviewController {
    // Bind preview views before any camera open attempt.
    void bindPreviews(TextureView leftPreview, TextureView rightPreview);

    // Start/stop controller-owned background resources.
    void startBackgroundThread();

    void stopBackgroundThread();

    // Monitor camera topology changes such as hot-plug/unplug.
    void startAvailabilityMonitor();

    void stopAvailabilityMonitor();

    // Query up to two preferred camera identifiers/device nodes.
    String[] getSelectedCameraIds();

    // Open the selected camera set and attach them to the bound previews.
    void openSelectedCameras(String[] cameraIds);

    // Release all opened camera resources.
    void closeAllCameras();

    // Report the number of active opened cameras.
    int getOpenCameraCount();
}
