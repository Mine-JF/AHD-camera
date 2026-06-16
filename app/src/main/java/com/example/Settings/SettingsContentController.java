package com.example.Settings;

import android.view.View;

public interface SettingsContentController {
    // 绑定当前页面的布局控件和点击逻辑。
    void bind(View view);

    // 页面显示时调用，适合做自动刷新或恢复状态。
    default void onShow() {
    }

    // 页面隐藏时调用，适合停止轮询或释放资源。
    default void onHide() {
    }
}
