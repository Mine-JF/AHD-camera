package com.example.Settings;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import com.example.ahd_camera.R;

// 声音页：实时控制媒体音量 STREAM_MUSIC。
public class SoundSettingsController implements SettingsContentController {

    private static final String TAG = "SoundSettings";
    private final Context context;
    private final AudioManager audioManager;
    private int volumeLevel = 8;

    public SoundSettingsController(Context context) {
        this.context = context;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public void bind(View view) {
        Log.d(TAG, "bind: start");
        TextView valueView = view.findViewById(R.id.tv_sound_value);
        SeekBar seekBar = view.findViewById(R.id.seek_sound);
        int currentStreamVolume = readCurrentMusicVolume();
        volumeLevel = currentStreamVolume;
        seekBar.setMax(10);
        seekBar.setProgress(currentStreamVolume);
        valueView.setText(String.valueOf(currentStreamVolume));
        Log.d(TAG, "bind: currentStreamVolume=" + currentStreamVolume
                + ", maxStreamVolume=" + (audioManager != null ? audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) : -1));
        seekBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                volumeLevel = progress;
                valueView.setText(String.valueOf(progress));
                // 0-10 直接映射到媒体音量的实际档位。
                applyMusicVolume(progress);
            }
        });
        Log.d(TAG, "bind: end");
    }

    private int readCurrentMusicVolume() {
        if (audioManager == null) {
            Log.w(TAG, "readCurrentMusicVolume: audioManager is null");
            return 0;
        }
        int max = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        Log.d(TAG, "readCurrentMusicVolume: current=" + current + ", max=" + max);
        return Math.round((current * 10f) / max);
    }

    private void applyMusicVolume(int level) {
        if (audioManager == null) {
            Log.e(TAG, "applyMusicVolume: audioManager is null");
            return;
        }

        int max = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int clampedLevel = Math.max(0, Math.min(10, level));
        int targetVolume = Math.round((clampedLevel * max) / 10f);
        Log.d(TAG, "applyMusicVolume: level=" + clampedLevel
                + ", max=" + max
                + ", targetVolume=" + targetVolume);

        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0);
            Log.d(TAG, "afterSet: readBack=" + audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
        } catch (RuntimeException e) {
            Log.e(TAG, "applyMusicVolume: setStreamVolume failed", e);
        }
    }
}
