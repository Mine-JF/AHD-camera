package com.example.Settings;

import android.util.Log;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import com.example.ahd_camera.R;

// 显示页：控制背光亮度滑条，并实时写入系统背光。
public class DisplaySettingsController implements SettingsContentController {

    private static final String TAG = "DisplaySettings";
    private int brightnessLevel = 8;

    @Override
    public void bind(View view) {
        Log.d(TAG, "bind: start, brightnessLevel=" + brightnessLevel);
        TextView valueView = view.findViewById(R.id.tv_display_value);
        SeekBar seekBar = view.findViewById(R.id.seek_display);

        // 先把当前值显示出来，并同步一次背光。
        seekBar.setProgress(brightnessLevel);
        valueView.setText(String.valueOf(brightnessLevel));
        try {
            BacklightController.applyLevel(brightnessLevel);
        } catch (RuntimeException e) {
            Log.e(TAG, "bind: applyLevel failed", e);
        }

        seekBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                brightnessLevel = progress;
                valueView.setText(String.valueOf(progress));
                // 滑动过程中实时下发，不等松手。
                Log.d(TAG, "onProgressChanged: progress=" + progress + ", fromUser=" + fromUser);
                try {
                    BacklightController.applyLevel(progress);
                } catch (RuntimeException e) {
                    Log.e(TAG, "onProgressChanged: applyLevel failed", e);
                }
            }
        });
        Log.d(TAG, "bind: end");
    }
}
