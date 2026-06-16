package com.example.Settings;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.example.ahd_camera.R;

// 系统信息页：只在页面显示时读取一次系统配置的屏幕分辨率。
public class SystemInfoSettingsController implements SettingsContentController {

    private final Context context;

    public SystemInfoSettingsController(Context context) {
        this.context = context;
    }

    @Override
    public void bind(View view) {
        TextView sdkInfoView = view.findViewById(R.id.tv_sdk_info);
        TextView screenInfoView = view.findViewById(R.id.tv_screen_info);

        // APK 信息直接读取当前应用版本号。
        sdkInfoView.setText(getApkVersionInfo());

        // 屏幕分辨率按系统当前配置读取一次，不做持续刷新。
        screenInfoView.setText(getScreenInfo());
    }

    private String getApkVersionInfo() {
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            String versionName = packageInfo.versionName != null ? packageInfo.versionName : "unknown";
            return context.getString(R.string.settings_apk_version_format, versionName, packageInfo.versionCode);
        } catch (PackageManager.NameNotFoundException e) {
            return context.getString(R.string.settings_apk_version_unavailable);
        }
    }

    private String getScreenInfo() {
        if (!(context instanceof Activity)) {
            return context.getString(R.string.settings_screen_info_unavailable);
        }

        WindowManager windowManager = ((Activity) context).getWindowManager();
        if (windowManager == null) {
            return context.getString(R.string.settings_screen_info_unavailable);
        }

        Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
        return context.getString(
                R.string.settings_screen_info_format,
                bounds.width(),
                bounds.height()
        );
    }
}
