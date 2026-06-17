package com.example.Settings;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.SeekBar;

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
    private SettingMenuAdapter menuAdapter;
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
        rvLeftMenu.setFocusable(true);
        rvLeftMenu.setFocusableInTouchMode(true);
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
        menuAdapter = new SettingMenuAdapter(menuList, (position, name) -> {
            showRightContent(position);
            enterRightContent();
        });
        rvLeftMenu.setAdapter(menuAdapter);
        showRightContent(0);
        rvLeftMenu.post(() -> {
            rvLeftMenu.requestFocus();
            focusLeftMenuItem(menuAdapter.getSelectedPosition());
        });
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

    private void enterRightContent() {
        View firstFocusable = findFirstFocusable(rightContentContainer);
        if (firstFocusable != null) {
            firstFocusable.requestFocus();
        }
    }

    private View findFirstFocusable(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return null;
        }
        if (view.isFocusable() && view.isEnabled()) {
            return view;
        }
        if (!(view instanceof ViewGroup)) {
            return null;
        }

        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            View focusable = findFirstFocusable(child);
            if (focusable != null) {
                return focusable;
            }
        }
        return null;
    }

    private boolean isDescendant(View child, View parent) {
        if (child == null || parent == null) {
            return false;
        }
        View current = child;
        while (current != null) {
            if (current == parent) {
                return true;
            }
            ViewParent viewParent = current.getParent();
            current = viewParent instanceof View ? (View) viewParent : null;
        }
        return false;
    }

    private void focusLeftMenuItem(int position) {
        if (menuAdapter == null) {
            return;
        }

        RecyclerView.ViewHolder holder = rvLeftMenu.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            holder.itemView.requestFocus();
        }
    }

    private void moveMenuSelection(int delta) {
        if (menuAdapter == null || menuList == null || menuList.isEmpty()) {
            return;
        }

        int size = menuList.size();
        int nextPosition = (menuAdapter.getSelectedPosition() + delta + size) % size;
        menuAdapter.setSelectedPosition(nextPosition);
        rvLeftMenu.scrollToPosition(nextPosition);
        rvLeftMenu.post(() -> focusLeftMenuItem(nextPosition));
        showRightContent(nextPosition);
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

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }

        View currentFocus = getCurrentFocus();
        boolean focusIsLeftMenu = isDescendant(currentFocus, rvLeftMenu);
        boolean focusIsRightPane = isDescendant(currentFocus, rightContentContainer);

        if (focusIsRightPane) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
                rvLeftMenu.requestFocus();
                rvLeftMenu.post(() -> focusLeftMenuItem(menuAdapter != null ? menuAdapter.getSelectedPosition() : 0));
                return true;
            }

            if (currentFocus instanceof SeekBar) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT
                        || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    SeekBar seekBar = (SeekBar) currentFocus;
                    int delta = event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                    int nextProgress = Math.max(0, Math.min(seekBar.getProgress() + delta, seekBar.getMax()));
                    if (nextProgress != seekBar.getProgress()) {
                        seekBar.setProgress(nextProgress);
                    }
                    return true;
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP
                        || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
                    return true;
                }
            }

            if (currentFocus != null) {
                int direction;
                switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        direction = View.FOCUS_LEFT;
                        break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        direction = View.FOCUS_RIGHT;
                        break;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        direction = View.FOCUS_UP;
                        break;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        direction = View.FOCUS_DOWN;
                        break;
                    default:
                        direction = -1;
                        break;
                }

                if (direction != -1) {
                    View nextFocus = currentFocus.focusSearch(direction);
                    if (nextFocus == null || isDescendant(nextFocus, rvLeftMenu)) {
                        return true;
                    }
                }
            }

            return super.dispatchKeyEvent(event);
        }

        if (focusIsLeftMenu || currentFocus == null) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    moveMenuSelection(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    moveMenuSelection(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    enterRightContent();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }

        return super.dispatchKeyEvent(event);
    }
}
