package com.example.ahd_camera;

import android.Manifest;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.BroadcastReceiver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.Color;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.Arrays;

public class Camera_view extends AppCompatActivity implements CameraStatusCallback {

    private static final String TAG = "Camera_view";
    private static final long BACK_KEY_MIC_LONG_PRESS_MS = 2_000L;
    private static final long STATUS_MESSAGE_DURATION_MS = 2_000L;
    // DnkCounter protocol reports signed steps. Adjust this after wheel calibration if needed.
    private static final float METERS_PER_COUNTER_STEP = 1.0f;
    public static final String ACTION_DISTANCE_UPDATE = "com.example.ahd_camera.action.DISTANCE_UPDATE";
    public static final String EXTRA_DISTANCE_METERS = "distance_meters";

    private FrameLayout leftCameraContainer;
    private FrameLayout rightCameraContainer;
    private TextView statusText;
    private View cameraContainer;
    private View cameraHudView;
    private CameraPreviewController cameraController;
    private CameraHudController hudController;
    private MicrophoneController microphoneController;
    private PhotoCaptureController photoCaptureController;
    private VideoRecorderController videoRecorderController;
    private MotorController motorController;
    private DistanceCounterController distanceCounterController;
    private String[] lastSelectedCameraIds = new String[0];
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int cameraFlowGeneration = 0;
    private boolean nativeBackendAvailable;
    private boolean distanceReceiverRegistered;
    private String nativeBackendSummary = "";
    private String currentStatusMessage = "";
    private TextureView leftPreview;
    private TextureView rightPreview;
    private View leftBlackOverlay;
    private View rightBlackOverlay;
    private int previewRotationDegrees = 0;
    private boolean backKeyDown;
    private boolean backKeyLongPressHandled;
    private boolean lightEnabled;
    // 定时刷新顶部时间、电量、Wi‑Fi 和存储状态，避免每个状态都自己开轮询。
    private final Runnable hudRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            hudController.refresh();
            mainHandler.postDelayed(this, 1000);
        }
    };
    private final Runnable refreshCameraRunnable = () -> {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            android.util.Log.d(TAG, "onCameraTopologyChanged: skip because permission is missing");
            return;
        }

        refreshCameraFlow();
    };
    private final Runnable backKeyLongPressRunnable = () -> {
        if (!backKeyDown) {
            return;
        }
        backKeyLongPressHandled = true;
        boolean enableMicrophone = !microphoneController.isEnabled();
        android.util.Log.i(TAG, "BACK long press: set microphone enabled=" + enableMicrophone);
        microphoneController.setEnabled(enableMicrophone);
        hudController.refresh();
    };
    private final Runnable hideStatusRunnable = this::hideStatus;
    private final BroadcastReceiver distanceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            if (intent == null || !ACTION_DISTANCE_UPDATE.equals(intent.getAction())) {
                android.util.Log.w(TAG, "distanceReceiver: ignore unrelated intent");
                return;
            }
            float distanceMeters = intent.getFloatExtra(EXTRA_DISTANCE_METERS, 0.0f);
            android.util.Log.d(TAG, "distanceReceiver: distanceMeters=" + distanceMeters);
            hudController.setDistanceMeters(distanceMeters);
            hudController.refresh();
        }
    };
    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    queryAndStartCameraFlow();
                } else {
                    showStatus(getString(R.string.camera_permission_required));
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        android.util.Log.d(TAG, "onCreate: enter");
        Utils.prepareFullscreenWindow(getWindow());
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_view);
        nativeBackendAvailable = V4l2CameraNative.isAvailable();
        nativeBackendSummary = V4l2CameraNative.getAvailabilitySummary();
        android.util.Log.d(TAG, "onCreate: nativeBackendAvailable=" + nativeBackendAvailable
                + ", summary=" + nativeBackendSummary);
        microphoneController = new MicrophoneController(this);
        hudController = new CameraHudController(this, microphoneController);
        photoCaptureController = new PhotoCaptureController(this);
        videoRecorderController = new VideoRecorderController(this);
        motorController = new MotorController();
        distanceCounterController = new DistanceCounterController(motorController, counterValue -> {
            float distanceMeters = counterValue * METERS_PER_COUNTER_STEP;
            hudController.setDistanceMeters(distanceMeters);
            hudController.refresh();
        });
        cameraController = createCameraController();

        cameraContainer = findViewById(R.id.camera_container);
        cameraHudView = findViewById(R.id.camera_hud);
        leftCameraContainer = findViewById(R.id.left_camera_container);
        rightCameraContainer = findViewById(R.id.right_camera_container);
        statusText = findViewById(R.id.status_text);

        leftPreview = findViewById(R.id.left_camera_preview);
        rightPreview = findViewById(R.id.right_camera_preview);
        leftCameraContainer.setBackgroundColor(Color.BLACK);
        rightCameraContainer.setBackgroundColor(Color.BLACK);

        leftBlackOverlay = buildBlackOverlay();
        rightBlackOverlay = buildBlackOverlay();
        leftCameraContainer.addView(leftBlackOverlay);
        rightCameraContainer.addView(rightBlackOverlay);
        applyPreviewRotation();
        hudController.bind();
        hudController.refresh();

        cameraController.bindPreviews(leftPreview, rightPreview);
        android.util.Log.d(TAG, "onCreate: view init finished");
    }

    // 页面回到前台时恢复沉浸式，并启动相机线程和预览流程。
    @Override
    protected void onResume() {
        super.onResume();
        android.util.Log.d(TAG, "onResume: start camera flow");
        Utils.hideSystemUI(getWindow());
        if (!distanceReceiverRegistered) {
            registerReceiver(distanceReceiver, new IntentFilter(ACTION_DISTANCE_UPDATE));
            distanceReceiverRegistered = true;
            android.util.Log.d(TAG, "onResume: distanceReceiver registered");
        }
        if (!nativeBackendAvailable) {
            android.util.Log.e(TAG, "onResume: skip camera flow because native backend is unavailable: "
                    + nativeBackendSummary);
            showBlackPreview();
            showStatus(getString(R.string.camera_native_library_missing));
            mainHandler.removeCallbacks(hudRefreshRunnable);
            mainHandler.post(hudRefreshRunnable);
            return;
        }
        mainHandler.removeCallbacks(hudRefreshRunnable);
        mainHandler.post(hudRefreshRunnable);
        distanceCounterController.start();
        cameraController.startBackgroundThread();
        ensureCameraPermissionAndStart();
        cameraController.startAvailabilityMonitor();
    }

    // 页面离开前台时释放摄像头，避免资源占用和重复打开。
    @Override
    protected void onPause() {
        android.util.Log.d(TAG, "onPause: release camera resources");
        cameraFlowGeneration++;
        mainHandler.removeCallbacks(refreshCameraRunnable);
        mainHandler.removeCallbacks(hudRefreshRunnable);
        mainHandler.removeCallbacks(backKeyLongPressRunnable);
        mainHandler.removeCallbacks(hideStatusRunnable);
        distanceCounterController.stop();
        backKeyDown = false;
        backKeyLongPressHandled = false;
        if (distanceReceiverRegistered) {
            unregisterReceiver(distanceReceiver);
            distanceReceiverRegistered = false;
            android.util.Log.d(TAG, "onPause: distanceReceiver unregistered");
        }
        cameraController.stopAvailabilityMonitor();
        motorController.stopAndClose();
        cameraController.closeAllCameras();
        cameraController.stopBackgroundThread();
        if (videoRecorderController.isRecording()) {
            android.util.Log.w(TAG, "onPause: stop recording because page is leaving foreground");
            videoRecorderController.stopRecording();
        }
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        android.util.Log.d(TAG, "onWindowFocusChanged: hasFocus=" + hasFocus);
        if (hasFocus) {
            Utils.hideSystemUI(getWindow());
        }
    }

    // 先检查相机权限，已有权限则直接开始预览，没有则发起权限申请。
    private void ensureCameraPermissionAndStart() {
        android.util.Log.d(TAG, "ensureCameraPermissionAndStart: check permission");
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            android.util.Log.d(TAG, "ensureCameraPermissionAndStart: permission granted");
            queryAndStartCameraFlow();
        } else {
            android.util.Log.d(TAG, "ensureCameraPermissionAndStart: request permission");
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    // 在后台线程里枚举摄像头，避免启动阶段卡住主线程。
    private void queryAndStartCameraFlow() {
        android.util.Log.d(TAG, "queryAndStartCameraFlow: start background scan");

        final int generation = ++cameraFlowGeneration;
        android.util.Log.d(TAG, "queryAndStartCameraFlow: generation=" + generation);

        new Thread(() -> {
            String[] selectedCameraIds = cameraController.getSelectedCameraIds();
            android.util.Log.d(TAG, "queryAndStartCameraFlow worker: selected="
                    + Arrays.toString(selectedCameraIds));

            mainHandler.post(() -> {
                if (generation != cameraFlowGeneration) {
                    android.util.Log.d(TAG, "queryAndStartCameraFlow: ignore stale scan result");
                    return;
                }

                if (Arrays.equals(lastSelectedCameraIds, selectedCameraIds)
                        && selectedCameraIds.length > 0
                        && cameraController.getOpenCameraCount() == selectedCameraIds.length) {
                    android.util.Log.d(TAG, "queryAndStartCameraFlow: selection unchanged and camera count matches, ignore");
                    return;
                }

                startCameraFlow(selectedCameraIds);
            });
        }, "camera-scan-thread").start();
    }

    // 根据当前检测到的摄像头数量切换布局，并触发实际的打开动作。
    private void startCameraFlow(String[] selectedCameraIds) {
        android.util.Log.d(TAG, "startCameraFlow: camera count=" + selectedCameraIds.length);

        applyLayoutForCameraCount(selectedCameraIds.length);
        lastSelectedCameraIds = Arrays.copyOf(selectedCameraIds, selectedCameraIds.length);

        if (selectedCameraIds.length == 0) {
            android.util.Log.w(TAG, "startCameraFlow: no available camera");

            cameraController.closeAllCameras();
            showBlackPreview();
            showStatus(getString(R.string.camera_not_found));

            // 不要 Toast，Toast 在无触摸设备上意义不大，也可能干扰全屏预览
            return;
        }

        showPreviewContent(selectedCameraIds.length > 1);

        hideStatus();
        android.util.Log.d(TAG, "startCameraFlow: open selected cameras");
        cameraController.openSelectedCameras(selectedCameraIds);
    }

    // 摄像头拓扑发生变化时，重新扫描一次可用设备并重建预览。
    private void refreshCameraFlow() {
        android.util.Log.d(TAG, "refreshCameraFlow: rebuild camera preview");
        queryAndStartCameraFlow();
    }

    // 1 路摄像头时左侧全屏显示，2 路摄像头时左右等宽平分。
    private void applyLayoutForCameraCount(int count) {
        android.util.Log.d(TAG, "applyLayoutForCameraCount: count=" + count);

        leftCameraContainer.setBackgroundColor(Color.BLACK);
        rightCameraContainer.setBackgroundColor(Color.BLACK);

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

            android.util.Log.d(TAG, "applyLayoutForCameraCount: show right preview");
        } else {
            rightCameraContainer.setVisibility(View.GONE);

            android.util.Log.d(TAG, "applyLayoutForCameraCount: hide right preview");
        }
    }

    // 在页面中央显示状态文字，例如权限缺失或摄像头打开失败。
    private void showStatus(String message) {
        android.util.Log.w(TAG, "showStatus: " + message);
        currentStatusMessage = message;
        runOnUiThread(() -> {
            mainHandler.removeCallbacks(hideStatusRunnable);
            statusText.setText(message);
            statusText.bringToFront();
            statusText.setVisibility(View.VISIBLE);
            mainHandler.postDelayed(hideStatusRunnable, STATUS_MESSAGE_DURATION_MS);
        });
    }

    // 隐藏中央状态文字，保持纯预览或纯黑屏显示。
    private void hideStatus() {
        android.util.Log.d(TAG, "hideStatus");
        mainHandler.removeCallbacks(hideStatusRunnable);
        currentStatusMessage = "";
        runOnUiThread(() -> statusText.setVisibility(View.GONE));
    }

    // 快捷键：按 ESC 进入设置页面。
    private void openSettings() {
        android.util.Log.d(TAG, "openSettings: launch settings page");
        startActivity(new Intent(this, com.example.Settings.Settings.class));
    }

    /**
     * 相机主页的板载按键总入口。
     * 外接键盘事件会先被过滤；随后依次处理计米清零、补光灯、VCM 对焦、云台方向和页面快捷键。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!HardwareKeySourceFilter.isBoardKey(event)) {
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            return handleBackKey(event);
        }
        if (event.getScanCode() == 0x163 || event.getKeyCode() == KeyEvent.KEYCODE_CLEAR) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                boolean resetSent = motorController.resetDistanceCounter();
                android.util.Log.i(TAG, "distance clear key: sent=" + resetSent
                        + ", scanCode=" + event.getScanCode());
                if (resetSent) {
                    hudController.setDistanceMeters(0.0f);
                    hudController.refresh();
                    showStatus("计米已清零");
                }
            }
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_T) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                // T 为开关键：每次有效按下在“关闭”和“10档最亮”之间切换。
                boolean nextEnabled = !lightEnabled;
                boolean sent = motorController.setLightEnabled(nextEnabled);
                android.util.Log.i(TAG, "light key: enabled=" + nextEnabled + ", sent=" + sent);
                if (sent) {
                    lightEnabled = nextEnabled;
                    showStatus(nextEnabled ? "补光灯：最亮" : "补光灯：关闭");
                }
            }
            return true;
        }
        if (handleFocusKey(event)) {
            return true;
        }
        int moveCommand = commandForDirectionKey(event.getKeyCode());
        if (moveCommand != 0) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                android.util.Log.i(TAG, "motor key down: command=" + moveCommand);
                motorController.move(moveCommand);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                android.util.Log.i(TAG, "motor key up: command=" + moveCommand);
                motorController.stopForMove(moveCommand);
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_MENU:
                    android.util.Log.d(TAG, "dispatchKeyEvent: open settings");
                    openSettings();
                    return true;
                case KeyEvent.KEYCODE_G:
                    android.util.Log.d(TAG, "dispatchKeyEvent: open gallery");
                    startActivity(new Intent(this, GalleryActivity.class));
                    return true;
                case KeyEvent.KEYCODE_V:
                case KeyEvent.KEYCODE_ENTER:
                    android.util.Log.d(TAG, "dispatchKeyEvent: capture photo");
                    capturePhoto();
                    return true;
                case KeyEvent.KEYCODE_Y:
                    android.util.Log.d(TAG, "dispatchKeyEvent: toggle recording");
                    toggleVideoRecording();
                    return true;
                case KeyEvent.KEYCODE_H:
                    android.util.Log.d(TAG, "dispatchKeyEvent: rotate preview");
                    rotatePreviewClockwise();
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * GPIO4_B3~B6 编码器按键：W/S 调整电机3焦距，D/A 调整电机3驱动档位。
     * W/S 松开时不发送 0x0A，确保 MCU 保持 VCM 电流，从而维持当前对焦状态。
     */
    private boolean handleFocusKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_W || keyCode == KeyEvent.KEYCODE_S) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                int command = keyCode == KeyEvent.KEYCODE_W
                        ? MotorController.COMMAND_FOCUS_UP : MotorController.COMMAND_FOCUS_DOWN;
                android.util.Log.i(TAG, "focus key down: command=" + command);
                motorController.moveFocus(command);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                // The MCU keeps the VCM energized after CMD_FOCUS_UP/DOWN. Do not send
                // CMD_STOP_FOCUS here: Coast mode releases coil current and loses focus.
                android.util.Log.i(TAG, "focus key up: retain VCM holding current");
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_D || keyCode == KeyEvent.KEYCODE_A) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                int command = keyCode == KeyEvent.KEYCODE_D
                        ? MotorController.COMMAND_FOCUS_SPEED_UP
                        : MotorController.COMMAND_FOCUS_SPEED_DOWN;
                android.util.Log.i(TAG, "focus speed key: command=" + command);
                motorController.adjustFocusSpeed(command);
            }
            return true;
        }
        return false;
    }

    private boolean handleBackKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                backKeyDown = true;
                backKeyLongPressHandled = false;
                mainHandler.postDelayed(backKeyLongPressRunnable, BACK_KEY_MIC_LONG_PRESS_MS);
                android.util.Log.d(TAG, "BACK down: wait for short/long press");
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            mainHandler.removeCallbacks(backKeyLongPressRunnable);
            boolean longPressHandled = backKeyLongPressHandled;
            backKeyDown = false;
            backKeyLongPressHandled = false;
            if (!longPressHandled) {
                // Camera_view is the launcher/home page. Do not let a hardware BACK key
                // finish it and expose the Android launcher; only its long-press action is used here.
                android.util.Log.d(TAG, "BACK short press: ignored on preview home page");
            } else {
                android.util.Log.d(TAG, "BACK released after long press: keep microphone state");
                hudController.refresh();
            }
            return true;
        }
        return true;
    }

    private int commandForDirectionKey(int keyCode) {
        switch (keyCode) {
            // 电机使用 GPIO4_A5/A7/A6/A4 对应的 I/K/J/L 键。
            // DPAD 键保留给设置、图库等页面进行界面导航。
            case KeyEvent.KEYCODE_I:
                return MotorController.COMMAND_UP;
            case KeyEvent.KEYCODE_K:
                return MotorController.COMMAND_DOWN;
            case KeyEvent.KEYCODE_J:
                return MotorController.COMMAND_LEFT;
            case KeyEvent.KEYCODE_L:
                return MotorController.COMMAND_RIGHT;
            default:
                return 0;
        }
    }

    // 相机控制层回调到页面层的统一错误入口。
    @Override
    public void onCameraError() {
        android.util.Log.e(TAG, "onCameraError: camera open failed");
        runOnUiThread(() -> {
            showBlackPreview();
            if (getString(R.string.camera_not_found).equals(currentStatusMessage)) {
                android.util.Log.w(TAG, "onCameraError: keep camera-not-found status");
                return;
            }
            showStatus(getString(R.string.camera_open_failed));
        });
    }

    @Override
    public void onCameraTopologyChanged() {
        android.util.Log.d(TAG, "onCameraTopologyChanged: camera list changed");
        mainHandler.removeCallbacks(refreshCameraRunnable);
        mainHandler.postDelayed(refreshCameraRunnable, 350);
    }

    @Override
    public void onCameraDisconnected(String cameraId) {
        android.util.Log.w(TAG, "onCameraDisconnected: " + cameraId);

        mainHandler.post(() -> {
            showBlackPreview();
            showStatus(getString(R.string.camera_not_found));

            // 强制重新扫描，不要等很久
            mainHandler.removeCallbacks(refreshCameraRunnable);
            mainHandler.postDelayed(refreshCameraRunnable, 100);
        });
    }

    private void showBlackPreview() {
        android.util.Log.w(TAG, "showBlackPreview");
        runOnUiThread(() -> {
            leftCameraContainer.setBackgroundColor(Color.BLACK);
            rightCameraContainer.setBackgroundColor(Color.BLACK);

            if (leftBlackOverlay != null) {
                leftBlackOverlay.bringToFront();
                leftBlackOverlay.setVisibility(View.VISIBLE);
            }

            if (rightBlackOverlay != null) {
                rightBlackOverlay.bringToFront();
                rightBlackOverlay.setVisibility(View.VISIBLE);
            }
        });
    }

    private void showPreviewContent(boolean showRightPreview) {
        android.util.Log.d(TAG, "showPreviewContent: showRightPreview=" + showRightPreview);
        if (leftPreview != null) {
            leftPreview.setVisibility(View.VISIBLE);
        }

        if (rightPreview != null) {
            rightPreview.setVisibility(showRightPreview ? View.VISIBLE : View.INVISIBLE);
        }

        if (leftBlackOverlay != null) {
            leftBlackOverlay.setVisibility(View.GONE);
        }

        if (rightBlackOverlay != null) {
            rightBlackOverlay.setVisibility(showRightPreview ? View.GONE : View.VISIBLE);
        }

        statusText.bringToFront();
    }

    private View buildBlackOverlay() {
        View overlay = new View(this);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        overlay.setBackgroundColor(Color.BLACK);
        overlay.setVisibility(View.VISIBLE);
        android.util.Log.d(TAG, "buildBlackOverlay: overlay created");
        return overlay;
    }

    // 按一次顺时针旋转 90 度，预览、拍照、录像都会沿用同一角度。
    private void rotatePreviewClockwise() {
        previewRotationDegrees = (previewRotationDegrees + 90) % 360;
        android.util.Log.i(TAG, "rotatePreviewClockwise: previewRotationDegrees=" + previewRotationDegrees);
        runOnUiThread(() -> {
            applyPreviewRotation();
            showStatus("画面旋转 " + previewRotationDegrees + "°");
        });
    }

    private void applyPreviewRotation() {
        applyRotationToView(leftPreview, previewRotationDegrees);
        applyRotationToView(rightPreview, previewRotationDegrees);
        android.util.Log.d(TAG, "applyPreviewRotation: degrees=" + previewRotationDegrees);
    }

    private void applyRotationToView(View targetView, int rotationDegrees) {
        if (targetView == null) {
            return;
        }
        if (targetView.getWidth() <= 0 || targetView.getHeight() <= 0) {
            targetView.post(() -> applyRotationToView(targetView, rotationDegrees));
            return;
        }
        targetView.setPivotX(targetView.getWidth() / 2f);
        targetView.setPivotY(targetView.getHeight() / 2f);
        targetView.setRotation(rotationDegrees);
    }

    // 使用当前预览画面抓拍一张 JPEG，并写入系统图片库。
    private void capturePhoto() {
        if (!StorageUtils.hasMountedRemovableStorage(this)) {
            android.util.Log.w(TAG, "capturePhoto: no removable storage");
            showStatus(getString(R.string.storage_insert_usb));
            return;
        }
        photoCaptureController.capture(cameraContainer, cameraHudView, new PhotoCaptureController.Callback() {
            @Override
            public void onPhotoSaved(android.net.Uri uri) {
                android.util.Log.i(TAG, "capturePhoto: saved uri=" + uri);
                runOnUiThread(() -> showStatus("拍照成功"));
            }

            @Override
            public void onPhotoSaveFailed(String error) {
                android.util.Log.e(TAG, "capturePhoto: failed, error=" + error);
                runOnUiThread(() -> showStatus("拍照失败"));
            }
        });
    }

    // 按键再次触发时停止录像，录制内容写入系统视频库。
    private void toggleVideoRecording() {
        if (!videoRecorderController.isRecording() && !StorageUtils.hasMountedRemovableStorage(this)) {
            android.util.Log.w(TAG, "toggleVideoRecording: no removable storage");
            showStatus(getString(R.string.storage_insert_usb));
            return;
        }
        videoRecorderController.toggleRecording(cameraContainer, cameraHudView, new VideoRecorderController.Callback() {
            @Override
            public void onRecordingStarted(android.net.Uri uri) {
                android.util.Log.i(TAG, "toggleVideoRecording: started, uri=" + uri);
                runOnUiThread(() -> showStatus("开始录像"));
            }

            @Override
            public void onRecordingStopped(android.net.Uri uri) {
                android.util.Log.i(TAG, "toggleVideoRecording: stopped, uri=" + uri);
                runOnUiThread(() -> showStatus("录像已保存"));
            }

            @Override
            public void onRecordingFailed(String error) {
                android.util.Log.e(TAG, "toggleVideoRecording: failed, error=" + error);
                runOnUiThread(() -> showStatus("录像失败"));
            }
        });
    }

    private CameraPreviewController createCameraController() {
        if (!nativeBackendAvailable) {
            android.util.Log.e(TAG, "createCameraController: v4l2 native unavailable, no camera2 fallback. summary="
                    + nativeBackendSummary);
        } else {
            android.util.Log.i(TAG, "createCameraController: use v4l2 backend only");
        }
        return new V4l2CameraController(this);
    }
}
