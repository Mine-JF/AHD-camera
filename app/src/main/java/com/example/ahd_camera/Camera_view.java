package com.example.ahd_camera;

import com.example.ahd_camera.camera.CameraHudController;
import com.example.ahd_camera.camera.CameraPreviewController;
import com.example.ahd_camera.camera.CameraStatusCallback;
import com.example.ahd_camera.camera.V4l2CameraController;
import com.example.ahd_camera.common.Utils;
import com.example.ahd_camera.device.DistanceCounterController;
import com.example.ahd_camera.device.DistanceUnitPreference;
import com.example.ahd_camera.device.MicrophoneController;
import com.example.ahd_camera.device.MotorController;
import com.example.ahd_camera.device.RemoteMotorSafetyController;
import com.example.ahd_camera.input.HardwareKeySourceFilter;
import com.example.ahd_camera.media.GalleryActivity;
import com.example.ahd_camera.media.PhotoCaptureController;
import com.example.ahd_camera.media.VideoRecorderController;
import com.example.ahd_camera.remote.BoardRemoteApplication;
import com.example.ahd_camera.storage.StorageUtils;
import com.example.Settings.SettingsOverlayController;

import android.Manifest;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.BroadcastReceiver;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.SparseLongArray;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.EditText;
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
    private static final long BOARD_KEY_DEBOUNCE_MS = 120L;
    private static final long STATUS_MESSAGE_DURATION_MS = 1_000L;
    // 顶部 HUD（日期、时间、状态图标）占用区域；输入编辑器不能覆盖该区域。
    private static final int INPUT_EDITOR_TOP_CLEARANCE_DP = 76;
    private static final int INPUT_EDITOR_BOTTOM_CLEARANCE_DP = 20;
    // 1档和2档都低于 VCM 始动电流，将两档合并进起步过程并直接从首个有效的3档开始。
    private static final int VCM_MIN_LEVEL = 3;
    private static final int VCM_MAX_LEVEL = 10;
    // 摇杆保持拨动时逐档调焦。首档先等待一小段时间，短拨仍可精确微调一档；
    // 之后每 120ms 更新一档，3~10 档完整过渡约一秒，避免焦距变化过快。
    private static final long VCM_CONTINUOUS_START_DELAY_MS = 180L;
    private static final long VCM_CONTINUOUS_STEP_INTERVAL_MS = 120L;
    // DnkCounter protocol reports signed steps. Adjust this after wheel calibration if needed.
    private static final float METERS_PER_COUNTER_STEP = 1.0f;
    public static final String ACTION_DISTANCE_UPDATE = "com.example.ahd_camera.action.DISTANCE_UPDATE";
    public static final String EXTRA_DISTANCE_METERS = "distance_meters";

    private FrameLayout leftCameraContainer;
    private FrameLayout rightCameraContainer;
    private TextView statusText;
    private View cameraContainer;
    private View cameraHudView;
    private View cameraHudTopBar;
    private TextView recordingSubtitleText;
    private CameraPreviewController cameraController;
    private CameraHudController hudController;
    private MicrophoneController microphoneController;
    private PhotoCaptureController photoCaptureController;
    private VideoRecorderController videoRecorderController;
    private MotorController motorController;
    private RemoteMotorSafetyController remoteMotorSafety;
    private volatile boolean remoteMenuOpen;
    private SettingsOverlayController settingsOverlay;
    private boolean restoreSettingsOverlay;
    private volatile boolean remoteMotorEnabled;
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
    private volatile boolean lightEnabled;
    // VCM 没有位置反馈；该值仅表示当前页面已成功发送的 PWM 档位。
    private int focusLevel;
    private int focusAdjustDelta;
    private int activeFocusKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private final StringBuilder directInputText = new StringBuilder();
    private EditText hardwareKeyboardEditor;
    private boolean clearingHardwareKeyboardEditor;
    // 按键抖动通常会形成 DOWN-UP-DOWN；保留最近 DOWN 的时间而不在 UP 时清除。
    private final SparseLongArray lastBoardKeyDownTimes = new SparseLongArray();
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
    private final Runnable continuousFocusRunnable = new Runnable() {
        @Override
        public void run() {
            if (focusAdjustDelta == 0) {
                return;
            }
            if (changeFocusLevel(focusAdjustDelta)) {
                mainHandler.postDelayed(this, VCM_CONTINUOUS_STEP_INTERVAL_MS);
            } else {
                stopContinuousFocusAdjustment();
            }
        }
    };
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
        View overlayView = findViewById(R.id.camera_settings_overlay);
        settingsOverlay = new SettingsOverlayController(this, overlayView);
        restoreSettingsOverlay = savedInstanceState != null
                && savedInstanceState.getBoolean("settings_overlay_open", false);
        View root = findViewById(R.id.main);
        root.post(() -> {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) overlayView.getLayoutParams();
            params.width = Math.round(root.getWidth() * 0.70f);
            params.height = Math.round(root.getHeight() * 0.76f);
            params.gravity = Gravity.CENTER;
            overlayView.setLayoutParams(params);
        });
        nativeBackendAvailable = V4l2CameraNative.isAvailable();
        nativeBackendSummary = V4l2CameraNative.getAvailabilitySummary();
        android.util.Log.d(TAG, "onCreate: nativeBackendAvailable=" + nativeBackendAvailable
                + ", summary=" + nativeBackendSummary);
        microphoneController = new MicrophoneController(this);
        // Start each camera session with audio muted; the physical key and phone can enable it.
        microphoneController.setEnabled(false);
        hudController = new CameraHudController(this, microphoneController);
        photoCaptureController = new PhotoCaptureController(this);
        videoRecorderController = new VideoRecorderController(this);
        motorController = new MotorController();
        remoteMotorSafety = new RemoteMotorSafetyController(motorController);
        distanceCounterController = new DistanceCounterController(motorController, counterValue -> {
            float distanceMeters = counterValue * METERS_PER_COUNTER_STEP;
            hudController.setDistanceMeters(distanceMeters);
            hudController.refresh();
        });
        cameraController = createCameraController();

        cameraContainer = findViewById(R.id.camera_container);
        cameraHudView = findViewById(R.id.camera_hud);
        cameraHudTopBar = findViewById(R.id.camera_hud_top_bar);
        recordingSubtitleText = findViewById(R.id.recording_subtitle);
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
        setupHardwareKeyboardEditor();
        android.util.Log.d(TAG, "onCreate: view init finished");
    }

    // 页面回到前台时恢复沉浸式，并启动相机线程和预览流程。
    @Override
    protected void onResume() {
        super.onResume();
        ((BoardRemoteApplication) getApplication()).bindActivity(this);
        android.util.Log.d(TAG, "onResume: start camera flow");
        Utils.hideSystemUI(getWindow());
        // Settings can change the display unit while this Activity is paused.
        hudController.refresh();
        focusHardwareKeyboardEditor();
        if (!distanceReceiverRegistered) {
            registerReceiver(distanceReceiver, new IntentFilter(ACTION_DISTANCE_UPDATE));
            distanceReceiverRegistered = true;
            android.util.Log.d(TAG, "onResume: distanceReceiver registered");
        }
        if (!nativeBackendAvailable) {
            android.util.Log.e(TAG, "onResume: skip camera flow because native backend is unavailable: "
                    + nativeBackendSummary);
            android.util.Log.e(TAG, "onResume: remote LAN server not started");
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
        remoteMotorEnabled = true;
        if (restoreSettingsOverlay) {
            restoreSettingsOverlay = false;
            openSettings();
        }
    }

    // 页面离开前台时释放摄像头，避免资源占用和重复打开。
    @Override
    protected void onPause() {
        android.util.Log.d(TAG, "onPause: release camera resources");
        remoteMotorEnabled = false;
        cameraFlowGeneration++;
        mainHandler.removeCallbacks(refreshCameraRunnable);
        mainHandler.removeCallbacks(hudRefreshRunnable);
        mainHandler.removeCallbacks(backKeyLongPressRunnable);
        mainHandler.removeCallbacks(hideStatusRunnable);
        stopContinuousFocusAdjustment();
        distanceCounterController.stop();
        focusLevel = 0;
        backKeyDown = false;
        backKeyLongPressHandled = false;
        if (distanceReceiverRegistered) {
            unregisterReceiver(distanceReceiver);
            distanceReceiverRegistered = false;
            android.util.Log.d(TAG, "onPause: distanceReceiver unregistered");
        }
        cameraController.stopAvailabilityMonitor();
        remoteMotorSafety.stopAllAndClose();
        cameraController.closeAllCameras();
        cameraController.stopBackgroundThread();
        if (videoRecorderController.isRecording()) {
            android.util.Log.w(TAG, "onPause: stop recording because page is leaving foreground");
            videoRecorderController.stopRecording();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        settingsOverlay.hide();
        remoteMotorSafety.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("settings_overlay_open", remoteMenuOpen);
        super.onSaveInstanceState(outState);
    }

    public void handleRemoteCommand(String command) {
        if (!command.endsWith("_KEEPALIVE")) {
            android.util.Log.i(TAG, "handleRemoteCommand: " + command);
        }
        if (remoteMenuOpen && (command.equals("OK") || command.equals("HOME")
                || command.equals("BACK") || command.equals("BACK_LONG")
                || command.startsWith("UP_") || command.startsWith("DOWN_")
                || command.startsWith("LEFT_") || command.startsWith("RIGHT_"))) {
            settingsOverlay.handleCommand(command);
            remoteMenuOpen = settingsOverlay.isVisible();
            return;
        }
        if (remoteMenuOpen && !command.equals("MENU") && !command.equals("MIC")
                && !command.equals("STOP_ALL")) {
            // Board hardware shortcuts do not control the preview while Settings is foreground.
            return;
        }
        if (!remoteMotorEnabled && (command.startsWith("UP_") || command.startsWith("DOWN_")
                || command.startsWith("LEFT_") || command.startsWith("RIGHT_"))) return;
        if (handleRemoteDirectionCommand(command)) return;
        switch (command) {
            case "FOCUS_PLUS_DOWN":
                startContinuousFocusAdjustment(KeyEvent.KEYCODE_BUTTON_C, 1);
                break;
            case "FOCUS_MINUS_DOWN":
                startContinuousFocusAdjustment(KeyEvent.KEYCODE_BUTTON_Y, -1);
                break;
            case "FOCUS_PLUS_UP":
            case "FOCUS_MINUS_UP":
                stopContinuousFocusAdjustment();
                break;
            case "RESET_DISTANCE":
                if (motorController.resetDistanceCounter()) {
                    hudController.setDistanceMeters(0.0f);
                    hudController.refresh();
                    showStatus("计米已清零");
                }
                break;
            case "LIGHT":
                boolean nextLightEnabled = !lightEnabled;
                if (motorController.setLightEnabled(nextLightEnabled)) {
                    lightEnabled = nextLightEnabled;
                    showStatus(nextLightEnabled ? "补光灯：最亮" : "补光灯：关闭");
                }
                break;
            case "PHOTO": capturePhoto(); break;
            case "RECORD": toggleVideoRecording(); break;
            case "MENU":
                if (remoteMenuOpen) {
                    closeSettings();
                } else {
                    openSettings();
                }
                break;
            case "OK":
                // OK only confirms selections on pages that have selectable content.
                // The preview page has nothing to confirm; never use OK as a shutter key.
                break;
            case "HOME": case "BACK":
                // The preview page is already home; a short back press does not exit it.
                break;
            case "MIC":
            case "BACK_LONG":
                microphoneController.setEnabled(!microphoneController.isEnabled());
                hudController.refresh();
                break;
            case "ROTATE": rotatePreviewClockwise(); break;
            case "STOP_ALL":
                stopContinuousFocusAdjustment();
                remoteMotorSafety.stopAllAndClose();
                focusLevel = 0;
                break;
            default: android.util.Log.w(TAG, "Unknown remote command: " + command);
        }
    }

    /** Direction-only path safe to call from the LAN socket thread. */
    public boolean handleRemoteDirectionCommand(String command) {
        if (remoteMenuOpen || !remoteMotorEnabled) return false;
        switch (command) {
            case "UP_DOWN": remoteMotorSafety.start(MotorController.COMMAND_UP); return true;
            case "UP_KEEPALIVE": remoteMotorSafety.keepAlive(MotorController.COMMAND_UP); return true;
            case "UP_UP": remoteMotorSafety.stop(MotorController.COMMAND_UP); return true;
            case "DOWN_DOWN": remoteMotorSafety.start(MotorController.COMMAND_DOWN); return true;
            case "DOWN_KEEPALIVE": remoteMotorSafety.keepAlive(MotorController.COMMAND_DOWN); return true;
            case "DOWN_UP": remoteMotorSafety.stop(MotorController.COMMAND_DOWN); return true;
            case "LEFT_DOWN": remoteMotorSafety.start(MotorController.COMMAND_LEFT); return true;
            case "LEFT_KEEPALIVE": remoteMotorSafety.keepAlive(MotorController.COMMAND_LEFT); return true;
            case "LEFT_UP": remoteMotorSafety.stop(MotorController.COMMAND_LEFT); return true;
            case "RIGHT_DOWN": remoteMotorSafety.start(MotorController.COMMAND_RIGHT); return true;
            case "RIGHT_KEEPALIVE": remoteMotorSafety.keepAlive(MotorController.COMMAND_RIGHT); return true;
            case "RIGHT_UP": remoteMotorSafety.stop(MotorController.COMMAND_RIGHT); return true;
            default: return false;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        android.util.Log.d(TAG, "onWindowFocusChanged: hasFocus=" + hasFocus);
        if (hasFocus) {
            ((BoardRemoteApplication) getApplication()).bindActivity(this);
            Utils.hideSystemUI(getWindow());
            focusHardwareKeyboardEditor();
        }
    }

    /**
     * 2.4G/USB HID 插拔会改变系统的 keyboard、keyboardHidden 或 navigation 配置。
     * Manifest 已声明由当前 Activity 自行处理，避免系统重建页面并在 onPause() 中
     * 关闭 AHD 视频流。这里只恢复沉浸式界面和隐藏文字编辑器的焦点。
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        android.util.Log.i(TAG, "onConfigurationChanged: keyboard=" + newConfig.keyboard
                + ", keyboardHidden=" + newConfig.keyboardHidden
                + ", hardKeyboardHidden=" + newConfig.hardKeyboardHidden
                + ", navigation=" + newConfig.navigation
                + "; keep AHD preview running");
        Utils.hideSystemUI(getWindow());
        focusHardwareKeyboardEditor();
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

    // 设置面板与 TextureView 保持在同一 Activity，预览和推流不中断。
    private void openSettings() {
        stopContinuousFocusAdjustment();
        remoteMotorSafety.stopAllAndClose();
        settingsOverlay.show();
        remoteMenuOpen = true;
        ((BoardRemoteApplication) getApplication()).setCameraSettingsOverlayVisible(this, true);
    }

    private void closeSettings() {
        settingsOverlay.hide();
        remoteMenuOpen = false;
        ((BoardRemoteApplication) getApplication()).setCameraSettingsOverlayVisible(this, false);
        focusHardwareKeyboardEditor();
    }

    /**
     * 相机主页的板载按键总入口。
     * 外接键盘事件会先被过滤；随后依次处理计米清零、补光灯、VCM 对焦、云台方向和页面快捷键。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!HardwareKeySourceFilter.isBoardKey(event)) {
            // 仅外接的物理字母键盘可输入文字；同一接收器的鼠标/Consumer Control 节点忽略。
            return HardwareKeySourceFilter.isExternalTextKeyboard(event)
                    ? handleExternalKeyboardInput(event)
                    : true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            // 板载按键的长按重复和机械抖动都不能重复触发拍照、录像、切页或电机命令。
            if (event.getRepeatCount() > 0 || isBoardKeyDebounced(event)) {
                return true;
            }
        }
        // MENU 的 DOWN/UP 都由页面消费，不能把 UP 交给当前焦点当作确认键。
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_3) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (remoteMenuOpen) closeSettings(); else openSettings();
            }
            return true;
        }
        if (remoteMenuOpen) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                String menuCommand = null;
                switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_BUTTON_7: menuCommand = "OK"; break;
                    case KeyEvent.KEYCODE_BUTTON_Z:
                    case KeyEvent.KEYCODE_ESCAPE: menuCommand = "BACK"; break;
                    case KeyEvent.KEYCODE_BUTTON_10: menuCommand = "UP_DOWN"; break;
                    case KeyEvent.KEYCODE_BUTTON_11: menuCommand = "DOWN_DOWN"; break;
                    case KeyEvent.KEYCODE_BUTTON_12: menuCommand = "LEFT_DOWN"; break;
                    case KeyEvent.KEYCODE_BUTTON_13: menuCommand = "RIGHT_DOWN"; break;
                    default: break;
                }
                if (menuCommand != null) {
                    settingsOverlay.handleCommand(menuCommand);
                    remoteMenuOpen = settingsOverlay.isVisible();
                }
            }
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_7) {
            // OK has no confirmation target on preview; consume both DOWN and UP.
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_Z) {
            return handleBackKey(event);
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_L1) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                boolean useFeet = !DistanceUnitPreference.usesFeet(this);
                DistanceUnitPreference.setUsesFeet(this, useFeet);
                hudController.refresh();
                android.util.Log.i(TAG, "distance unit switched to " + (useFeet ? "ft" : "m"));
            }
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_4) {
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
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_8) {
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
                case KeyEvent.KEYCODE_BUTTON_1:
                    android.util.Log.d(TAG, "dispatchKeyEvent: open gallery");
                    if (StorageUtils.hasMountedRemovableStorage(this)) {
                        startActivity(new Intent(this, GalleryActivity.class));
                    } else {
                        showStatus(getString(R.string.storage_insert_usb));
                    }
                    return true;
                case KeyEvent.KEYCODE_BUTTON_5:
                    android.util.Log.d(TAG, "dispatchKeyEvent: capture photo");
                    capturePhoto();
                    return true;
                case KeyEvent.KEYCODE_BUTTON_9:
                    android.util.Log.d(TAG, "dispatchKeyEvent: toggle recording");
                    toggleVideoRecording();
                    return true;
                case KeyEvent.KEYCODE_BUTTON_6:
                    android.util.Log.d(TAG, "dispatchKeyEvent: rotate preview");
                    rotatePreviewClockwise();
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean isBoardKeyDebounced(KeyEvent event) {
        long now = SystemClock.uptimeMillis();
        int keyCode = event.getKeyCode();
        int index = lastBoardKeyDownTimes.indexOfKey(keyCode);
        long previousDownAt = index >= 0 ? lastBoardKeyDownTimes.valueAt(index) : 0L;
        lastBoardKeyDownTimes.put(keyCode, now);
        if (index >= 0 && now - previousDownAt < BOARD_KEY_DEBOUNCE_MS) {
            android.util.Log.d(TAG, "dispatchKeyEvent: debounce keyCode=" + keyCode);
            return true;
        }
        return false;
    }

    /** BUTTON_C/Y 摇杆：拨住后连续调节 VCM，松开后保持当前 PWM 档位。 */
    private boolean handleFocusKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BUTTON_C || keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                startContinuousFocusAdjustment(
                        keyCode,
                        keyCode == KeyEvent.KEYCODE_BUTTON_C ? 1 : -1);
            } else if (event.getAction() == KeyEvent.ACTION_UP
                    && keyCode == activeFocusKeyCode) {
                stopContinuousFocusAdjustment();
            }
            return true;
        }

        // BUTTON_B/X 为预留物理键，不能绕过应用维护的逐级档位状态。
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BUTTON_X) {
            return true;
        }
        return false;
    }

    private void startContinuousFocusAdjustment(int keyCode, int delta) {
        mainHandler.removeCallbacks(continuousFocusRunnable);
        activeFocusKeyCode = keyCode;
        focusAdjustDelta = delta;

        // 按下立即响应；继续拨住摇杆时自动连续变化，短拨仍可精确微调一档。
        if (changeFocusLevel(delta)) {
            mainHandler.postDelayed(continuousFocusRunnable, VCM_CONTINUOUS_START_DELAY_MS);
        } else {
            stopContinuousFocusAdjustment();
        }
    }

    private void stopContinuousFocusAdjustment() {
        mainHandler.removeCallbacks(continuousFocusRunnable);
        focusAdjustDelta = 0;
        activeFocusKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    }

    private boolean changeFocusLevel(int delta) {
        int nextLevel = focusLevel + delta;
        if (delta > 0 && focusLevel == 0) {
            nextLevel = VCM_MIN_LEVEL;
        }
        if (nextLevel < VCM_MIN_LEVEL || nextLevel > VCM_MAX_LEVEL) {
            android.util.Log.d(TAG, nextLevel < VCM_MIN_LEVEL
                    ? "changeFocusLevel: already at minimum"
                    : "changeFocusLevel: already at maximum");
            return false;
        }

        // VCM 的位置由同一方向上的 PWM 占空比决定。减档时如果发送反向命令
        // COMMAND_FOCUS_DOWN，反向电流叠加弹簧回位会让镜头直接退回；因此
        // 有效档3~10及10~3都使用正向驱动，只改变目标 PWM 档位。
        int command = MotorController.COMMAND_FOCUS_UP;
        if (motorController.moveFocusAtLevel(command, nextLevel)) {
            focusLevel = nextLevel;
            android.util.Log.i(TAG, "changeFocusLevel: command=" + command + ", level=" + focusLevel);
            return true;
        } else {
            android.util.Log.e(TAG, "changeFocusLevel: send failed, level=" + nextLevel);
            return false;
        }
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
            case KeyEvent.KEYCODE_BUTTON_14:
                return MotorController.COMMAND_UP;
            case KeyEvent.KEYCODE_BUTTON_15:
                return MotorController.COMMAND_DOWN;
            case KeyEvent.KEYCODE_BUTTON_16:
                return MotorController.COMMAND_LEFT;
            case KeyEvent.KEYCODE_BUTTON_A:
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
        ((BoardRemoteApplication) getApplication()).setPreviewRotation(previewRotationDegrees);
        android.util.Log.i(TAG, "rotatePreviewClockwise: previewRotationDegrees=" + previewRotationDegrees);
        runOnUiThread(() -> {
            applyPreviewRotation();
            showStatus("画面旋转 " + previewRotationDegrees + "°");
        });
    }

    public int getPreviewRotationDegrees() {
        return previewRotationDegrees;
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
        if (!StorageUtils.hasWritableRemovableStorage(this)) {
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
        if (!videoRecorderController.isRecording() && !StorageUtils.hasWritableRemovableStorage(this)) {
            android.util.Log.w(TAG, "toggleVideoRecording: no removable storage");
            showStatus(getString(R.string.storage_insert_usb));
            return;
        }
        videoRecorderController.toggleRecording(cameraContainer, cameraHudView, new VideoRecorderController.Callback() {
            @Override
            public void onRecordingStarted(android.net.Uri uri) {
                android.util.Log.i(TAG, "toggleVideoRecording: started, uri=" + uri);
                runOnUiThread(() -> {
                    clearRecordingSubtitles();
                    showStatus("开始录像（可直接键盘输入文字）");
                });
            }

            @Override
            public void onRecordingStopped(android.net.Uri uri) {
                android.util.Log.i(TAG, "toggleVideoRecording: stopped, uri=" + uri);
                runOnUiThread(() -> {
                    clearRecordingSubtitles();
                    showStatus("录像已保存");
                });
            }

            @Override
            public void onRecordingFailed(String error) {
                android.util.Log.e(TAG, "toggleVideoRecording: failed, error=" + error);
                runOnUiThread(() -> {
                    clearRecordingSubtitles();
                    showStatus("录像失败");
                });
            }
        });
    }

    /** Snapshot read by the LAN control connection for phone button indicators. */
    public boolean isRemoteLightEnabled() {
        return lightEnabled;
    }

    public boolean isRemoteMicrophoneEnabled() {
        return microphoneController != null && microphoneController.isEnabled();
    }

    public boolean isRemoteRecording() {
        return videoRecorderController != null && videoRecorderController.isRecording();
    }

    private boolean handleExternalKeyboardInput(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
            clearRecordingSubtitles();
            return true;
        }
        // 交给隐藏的 EditText 和系统输入法处理，可获得拼音上屏后的中文字符。
        return super.dispatchKeyEvent(event);
    }

    private void setupHardwareKeyboardEditor() {
        FrameLayout root = findViewById(R.id.main);
        if (root == null) {
            android.util.Log.e(TAG, "setupHardwareKeyboardEditor: root view not found");
            return;
        }
        hardwareKeyboardEditor = new EditText(this);
        float density = getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams keyboardEditorParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        keyboardEditorParams.gravity = Gravity.TOP | Gravity.START;
        // 输入区域覆盖预览，但避开顶部 HUD，底部固定留 20dp。
        keyboardEditorParams.topMargin = (int) (INPUT_EDITOR_TOP_CLEARANCE_DP * density);
        keyboardEditorParams.bottomMargin = (int) (INPUT_EDITOR_BOTTOM_CLEARANCE_DP * density);
        hardwareKeyboardEditor.setLayoutParams(keyboardEditorParams);
        hardwareKeyboardEditor.setAlpha(0f);
        hardwareKeyboardEditor.setBackground(null);
        hardwareKeyboardEditor.setCursorVisible(false);
        hardwareKeyboardEditor.setSingleLine(false);
        hardwareKeyboardEditor.setMaxLines(Integer.MAX_VALUE);
        hardwareKeyboardEditor.setGravity(Gravity.TOP | Gravity.START);
        hardwareKeyboardEditor.setHorizontallyScrolling(false);
        hardwareKeyboardEditor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        hardwareKeyboardEditor.setShowSoftInputOnFocus(false);
        hardwareKeyboardEditor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (!clearingHardwareKeyboardEditor) {
                    syncDirectInputFromEditor(editable);
                }
            }
        });
        root.addView(hardwareKeyboardEditor);
        root.post(this::updateHardwareKeyboardEditorBounds);
        focusHardwareKeyboardEditor();
    }

    private void updateHardwareKeyboardEditorBounds() {
        if (hardwareKeyboardEditor == null) {
            return;
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) hardwareKeyboardEditor.getLayoutParams();
        float density = getResources().getDisplayMetrics().density;
        int minimumTopClearance = (int) (INPUT_EDITOR_TOP_CLEARANCE_DP * density);
        int hudBottom = cameraHudTopBar == null ? 0 : cameraHudTopBar.getBottom();
        int topMargin = Math.max(minimumTopClearance, hudBottom + (int) (8 * density));
        int bottomMargin = (int) (INPUT_EDITOR_BOTTOM_CLEARANCE_DP * density);
        params.topMargin = topMargin;
        params.bottomMargin = bottomMargin;
        hardwareKeyboardEditor.setLayoutParams(params);

        // 可见文字层与隐藏编辑器使用完全相同的有效区域：从上向下排列，保留换行。
        if (recordingSubtitleText != null) {
            FrameLayout.LayoutParams subtitleParams =
                    (FrameLayout.LayoutParams) recordingSubtitleText.getLayoutParams();
            subtitleParams.topMargin = topMargin;
            subtitleParams.bottomMargin = bottomMargin;
            recordingSubtitleText.setLayoutParams(subtitleParams);
        }
    }

    private void focusHardwareKeyboardEditor() {
        if (hardwareKeyboardEditor != null) {
            updateHardwareKeyboardEditorBounds();
            hardwareKeyboardEditor.requestFocus();
        }
    }

    private void syncDirectInputFromEditor(Editable editable) {
        directInputText.setLength(0);
        directInputText.append(editable);
        if (directInputText.length() == 0) {
            if (recordingSubtitleText != null) {
                recordingSubtitleText.setText("");
                recordingSubtitleText.setVisibility(View.GONE);
            }
            return;
        }
        updateDirectInputOverlay();
    }

    private void updateDirectInputOverlay() {
        if (recordingSubtitleText == null || directInputText.length() == 0) {
            return;
        }
        recordingSubtitleText.setText(directInputText);
        recordingSubtitleText.setVisibility(View.VISIBLE);
        // 预览时持续显示；录像时该叠加层会随帧合成写入 MP4，直到用户手动清除。
    }

    private void clearRecordingSubtitles() {
        clearDirectInputOverlay();
    }

    private void clearDirectInputOverlay() {
        directInputText.setLength(0);
        if (hardwareKeyboardEditor != null && hardwareKeyboardEditor.length() > 0) {
            clearingHardwareKeyboardEditor = true;
            hardwareKeyboardEditor.setText("");
            clearingHardwareKeyboardEditor = false;
        }
        if (recordingSubtitleText != null) {
            recordingSubtitleText.setText("");
            recordingSubtitleText.setVisibility(View.GONE);
        }
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
