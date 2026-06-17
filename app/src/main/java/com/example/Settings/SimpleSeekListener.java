package com.example.Settings;

import android.util.Log;
import android.widget.SeekBar;

// 统一的 SeekBar 监听基类，减少重复空实现。
public abstract class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {

    private static final String TAG = "SimpleSeekListener";

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        Log.d(TAG, "onStartTrackingTouch");
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        Log.d(TAG, "onStopTrackingTouch");
    }
}
