package com.example.Settings;

import android.view.View;

// 没有交互逻辑的页面直接使用这个空实现。
public class NoOpSettingsController implements SettingsContentController {
    @Override
    public void bind(View view) {
        // 不处理。
    }
}
