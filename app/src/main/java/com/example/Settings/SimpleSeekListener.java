package com.example.Settings;

import android.widget.SeekBar;

// 统一的 SeekBar 监听基类，减少重复空实现。
public abstract class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
    }
}
