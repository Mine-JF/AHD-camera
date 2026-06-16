package com.example.Settings;

import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import com.example.ahd_camera.R;

// 显示页：控制背光亮度滑条，并实时写入系统背光。
public class DisplaySettingsController implements SettingsContentController {

    private int brightnessLevel = 8;

    @Override
    public void bind(View view) {
        TextView valueView = view.findViewById(R.id.tv_display_value);
        SeekBar seekBar = view.findViewById(R.id.seek_display);

        // 先把当前值显示出来，并同步一次背光。
        seekBar.setProgress(brightnessLevel);
        valueView.setText(String.valueOf(brightnessLevel));
        BacklightController.applyLevel(brightnessLevel);

        seekBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                brightnessLevel = progress;
                valueView.setText(String.valueOf(progress));
                // 滑动过程中实时下发，不等松手。
                BacklightController.applyLevel(progress);
            }
        });
    }
}
