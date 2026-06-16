package com.example.Settings;

import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import com.example.ahd_camera.R;

// 显示页：控制背光亮度滑条。
public class DisplaySettingsController implements SettingsContentController {

    private int brightnessLevel = 8;

    @Override
    public void bind(View view) {
        TextView valueView = view.findViewById(R.id.tv_display_value);
        SeekBar seekBar = view.findViewById(R.id.seek_display);
        seekBar.setProgress(brightnessLevel);
        valueView.setText(String.valueOf(brightnessLevel));
        seekBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                brightnessLevel = progress;
                valueView.setText(String.valueOf(progress));
            }
        });
    }
}
