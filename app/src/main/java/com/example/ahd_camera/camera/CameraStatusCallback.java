package com.example.ahd_camera.camera;

public interface CameraStatusCallback {
    void onCameraError();

    void onCameraTopologyChanged();

    void onCameraDisconnected(String cameraId);
}
