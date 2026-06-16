package com.example.Settings;

import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import com.example.ahd_camera.R;

// 声音页：控制音量滑条。
public class SoundSettingsController implements SettingsContentController {

    private int volumeLevel = 8;

    @Override
    public void bind(View view) {
        TextView valueView = view.findViewById(R.id.tv_sound_value);
        SeekBar seekBar = view.findViewById(R.id.seek_sound);
        seekBar.setProgress(volumeLevel);
        valueView.setText(String.valueOf(volumeLevel));
        seekBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                volumeLevel = progress;
                valueView.setText(String.valueOf(progress));
            }
        });
    }
}
