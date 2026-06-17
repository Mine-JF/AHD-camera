package com.example.Settings;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.example.ahd_camera.R;

// 恢复出厂设置页：直接跳转到 Android 系统的恢复出厂设置界面。
public class FactoryResetSettingsController implements SettingsContentController {

    private static final String TAG = "FactoryReset";
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String SETTINGS_SUB_SETTINGS = "com.android.settings.SubSettings";
    private static final String SETTINGS_MASTER_CLEAR_FRAGMENT = "com.android.settings.MasterClear";
    private static final String EXTRA_SHOW_FRAGMENT = ":settings:show_fragment";
    private static final String EXTRA_NO_HEADERS = ":settings:no_headers";
    private static final String EXTRA_SHOW_FRAGMENT_AS_SUBSETTING =
            ":settings:show_fragment_as_subsetting";

    private final Context context;

    public FactoryResetSettingsController(Context context) {
        this.context = context;
    }

    @Override
    public void bind(View view) {
        Button resetButton = view.findViewById(R.id.btn_factory_reset);
        resetButton.setOnClickListener(v -> openSystemFactoryResetPage());
    }

    private void openSystemFactoryResetPage() {
        try {
            Log.d(TAG, "openSystemFactoryResetPage: start");
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName(SETTINGS_PACKAGE, SETTINGS_SUB_SETTINGS);
            intent.putExtra(EXTRA_SHOW_FRAGMENT, SETTINGS_MASTER_CLEAR_FRAGMENT);
            intent.putExtra(EXTRA_NO_HEADERS, true);
            intent.putExtra(EXTRA_SHOW_FRAGMENT_AS_SUBSETTING, true);
            context.startActivity(intent);
            Log.d(TAG, "openSystemFactoryResetPage: launched");
        } catch (RuntimeException e) {
            Log.e(TAG, "openSystemFactoryResetPage: failed", e);
            Toast.makeText(context, R.string.settings_factory_reset_failed, Toast.LENGTH_SHORT).show();
        }
    }
}
