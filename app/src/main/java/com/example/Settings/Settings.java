package com.example.Settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.ahd_camera.R;
import com.example.ahd_camera.Utils;

import java.util.Arrays;
import java.util.List;

public class Settings extends AppCompatActivity {

    private RecyclerView rvLeftMenu;
    private FrameLayout rightContentContainer;
    private SettingsContentController currentController;
    private List<String> menuList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Utils.prepareFullscreenWindow(getWindow());
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);

        // 让内容区域避开系统栏，同时保持当前页面的全屏风格。
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initView();
        initMenu();
    }

    private void initView() {
        rvLeftMenu = findViewById(R.id.rv_left_menu);
        rightContentContainer = findViewById(R.id.right_content_container);
    }

    private void initMenu() {
        menuList = Arrays.asList(
                getString(R.string.settings_menu_language),
                getString(R.string.settings_menu_time),
                getString(R.string.settings_menu_count),
                getString(R.string.settings_menu_storage_card),
                getString(R.string.settings_menu_display),
                getString(R.string.settings_menu_sound),
                getString(R.string.settings_menu_system_info),
                getString(R.string.settings_menu_factory_reset)
        );

        rvLeftMenu.setLayoutManager(new LinearLayoutManager(this));
        SettingMenuAdapter adapter = new SettingMenuAdapter(menuList, (position, name) -> showRightContent(position));
        rvLeftMenu.setAdapter(adapter);
        showRightContent(0);
    }

    private void showRightContent(int position) {
        if (currentController != null) {
            currentController.onHide();
        }

        rightContentContainer.removeAllViews();

        int layoutId = getLayoutId(position);
        View view = LayoutInflater.from(this).inflate(layoutId, rightContentContainer, false);
        rightContentContainer.addView(view);

        currentController = createController(position);
        currentController.bind(view);
        currentController.onShow();
    }

    private int getLayoutId(int position) {
        switch (position) {
            case 0:
                return R.layout.layout_language;
            case 1:
                return R.layout.layout_time;
            case 2:
                return R.layout.layout_date;
            case 3:
                return R.layout.layout_sdcard;
            case 4:
                return R.layout.layout_display;
            case 5:
                return R.layout.layout_sound;
            case 6:
                return R.layout.layout_system_info;
            case 7:
                return R.layout.layout_factory_reset;
            default:
                return R.layout.layout_language;
        }
    }

    private SettingsContentController createController(int position) {
        switch (position) {
            case 0:
                return new LanguageSettingsController(this);
            case 1:
                return new TimeSettingsController();
            case 2:
                return new CountSettingsController();
            case 3:
                return new StorageSettingsController(this);
            case 4:
                return new DisplaySettingsController();
            case 5:
                return new SoundSettingsController(this);
            case 6:
                return new SystemInfoSettingsController(this);
            case 7:
                return new FactoryResetSettingsController(this);
            default:
                return new NoOpSettingsController();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Utils.hideSystemUI(getWindow());
        if (currentController != null) {
            currentController.onShow();
        }
    }

    @Override
    protected void onPause() {
        if (currentController != null) {
            currentController.onHide();
        }
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            Utils.hideSystemUI(getWindow());
        }
    }
}
