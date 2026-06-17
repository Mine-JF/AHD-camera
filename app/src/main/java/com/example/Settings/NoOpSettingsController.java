package com.example.Settings;

import android.util.Log;
import android.view.View;

// 没有交互逻辑的页面直接使用这个空实现。
public class NoOpSettingsController implements SettingsContentController {

    private static final String TAG = "NoOpSettings";

    @Override
    public void bind(View view) {
        if (view == null) {
            Log.e(TAG, "bind: view is null");
            return;
        }
        Log.w(TAG, "bind: no operation for this page");
    }
}
