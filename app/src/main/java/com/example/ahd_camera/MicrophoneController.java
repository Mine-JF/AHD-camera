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

    // 用于按住说话等明确的开/关场景，避免根据当前状态反转。
    void setEnabled(boolean enabled) {
        if (audioManager == null) {
            Log.w(TAG, "setEnabled: audioManager is null");
            return;
        }
        audioManager.setMicrophoneMute(!enabled);
        Log.i(TAG, "setEnabled: enabled=" + enabled);
    }
}
