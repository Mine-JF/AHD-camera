package com.example.ahd_camera;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

final class MicrophoneController {

    private static final String TAG = "MicrophoneController";

    private final AudioManager audioManager;

    MicrophoneController(Context context) {
        audioManager = context.getSystemService(AudioManager.class);
        Log.d(TAG, "init: audioManagerNull=" + (audioManager == null));
    }

    // 查询当前麦克风是否处于可用状态，供 HUD 和按键逻辑统一读取。
    boolean isEnabled() {
        if (audioManager == null) {
            Log.w(TAG, "isEnabled: audioManager is null, fallback to enabled");
            return true;
        }
        boolean enabled = !audioManager.isMicrophoneMute();
        Log.d(TAG, "isEnabled: enabled=" + enabled);
        return enabled;
    }

    // 切换板卡声卡的麦克风静音状态，返回切换后的“是否启用”结果。
    boolean toggle() {
        if (audioManager == null) {
            Log.w(TAG, "toggle: audioManager is null");
            return true;
        }
        boolean currentMuted = audioManager.isMicrophoneMute();
        boolean nextEnabled = currentMuted;
        Log.d(TAG, "toggle: currentMuted=" + currentMuted + ", nextEnabled=" + nextEnabled);
        audioManager.setMicrophoneMute(!nextEnabled);
        Log.i(TAG, "toggle finished: enabled=" + nextEnabled);
        return nextEnabled;
    }
}
