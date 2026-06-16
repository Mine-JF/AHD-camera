package com.example.ahd_camera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class Camera_view extends AppCompatActivity implements Open_Camera.StatusCallback {

    private FrameLayout leftCameraContainer;
    private FrameLayout rightCameraContainer;
    private TextView statusText;
    private Open_Camera openCameraHelper;

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    startCameraFlow();
                } else {
                    showStatus(getString(R.string.camera_permission_required));
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Utils.prepareFullscreenWindow(getWindow());
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_view);
        openCameraHelper = new Open_Camera(this, this);

        leftCameraContainer = findViewById(R.id.left_camera_container);
        rightCameraContainer = findViewById(R.id.right_camera_container);
        statusText = findViewById(R.id.status_text);

        TextureView leftPreview = findViewById(R.id.left_camera_preview);
        TextureView rightPreview = findViewById(R.id.right_camera_preview);
        openCameraHelper.bindPreviews(leftPreview, rightPreview);
    }

    // 页面回到前台时恢复沉浸式，并启动相机线程和预览流程。
    @Override
    protected void onResume() {
        super.onResume();
        Utils.hideSystemUI(getWindow());
        openCameraHelper.startBackgroundThread();
        ensureCameraPermissionAndStart();
    }

    // 页面离开前台时释放摄像头，避免资源占用和重复打开。
    @Override
    protected void onPause() {
        openCameraHelper.closeAllCameras();
        openCameraHelper.stopBackgroundThread();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            Utils.hideSystemUI(getWindow());
        }
    }

    // 先检查相机权限，已有权限则直接开始预览，没有则发起权限申请。
    private void ensureCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCameraFlow();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    // 根据当前检测到的摄像头数量切换布局，并触发实际的打开动作。
    private void startCameraFlow() {
        String[] selectedCameraIds = openCameraHelper.getSelectedCameraIds();
        applyLayoutForCameraCount(selectedCameraIds.length);

        if (selectedCameraIds.length == 0) {
            hideStatus();
            Toast.makeText(this, R.string.camera_not_found, Toast.LENGTH_SHORT).show();
            return;
        }

        hideStatus();
        openCameraHelper.openSelectedCameras(selectedCameraIds);
    }

    // 1 路摄像头时左侧全屏显示，2 路摄像头时左右等宽平分。
    private void applyLayoutForCameraCount(int count) {
        LinearLayout.LayoutParams leftParams =
                (LinearLayout.LayoutParams) leftCameraContainer.getLayoutParams();
        LinearLayout.LayoutParams rightParams =
                (LinearLayout.LayoutParams) rightCameraContainer.getLayoutParams();

        leftParams.width = 0;
        leftParams.weight = 1f;
        leftCameraContainer.setLayoutParams(leftParams);

        if (count > 1) {
            rightParams.width = 0;
            rightParams.weight = 1f;
            rightCameraContainer.setLayoutParams(rightParams);
            rightCameraContainer.setVisibility(View.VISIBLE);
        } else {
            rightCameraContainer.setVisibility(View.GONE);
        }
    }

    // 在页面中央显示状态文字，例如权限缺失或摄像头打开失败。
    private void showStatus(String message) {
        runOnUiThread(() -> {
            statusText.setText(message);
            statusText.setVisibility(View.VISIBLE);
        });
    }

    // 隐藏中央状态文字，保持纯预览或纯黑屏显示。
    private void hideStatus() {
        runOnUiThread(() -> statusText.setVisibility(View.GONE));
    }

    // 相机控制层回调到页面层的统一错误入口。
    @Override
    public void onCameraError() {
        showStatus(getString(R.string.camera_open_failed));
    }
}
