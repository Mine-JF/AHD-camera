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
import com.example.ahd_camera.input.HardwareKeySourceFilter;
import com.example.ahd_camera.input.OkHomeKeyHandler;
import com.example.ahd_camera.common.Utils;

import java.util.Arrays;
import java.util.List;
import java.lang.ref.WeakReference;
import android.util.Log;

public class Settings extends AppCompatActivity {

    private static final String TAG = "Settings";
    private final OkHomeKeyHandler okHomeKeyHandler = new OkHomeKeyHandler(
            () -> handleRemoteCommand("OK"), () -> OkHomeKeyHandler.returnToPreview(this));
    private static WeakReference<Settings> activeRemoteSettings = new WeakReference<>(null);

    private RecyclerView rvLeftMenu;
    private FrameLayout rightContentContainer;
    private SettingsContentController currentController;
    private SettingMenuAdapter menuAdapter;
    private List<String> menuList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate: start");
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
        Log.d(TAG, "onCreate: end");
    }

    private void initView() {
        Log.d(TAG, "initView: start");
        rvLeftMenu = findViewById(R.id.rv_left_menu);
        rightContentContainer = findViewById(R.id.right_content_container);
        rvLeftMenu.setFocusable(true);
        rvLeftMenu.setFocusableInTouchMode(true);
        Log.d(TAG, "initView: end");
    }

    private void initMenu() {
        Log.d(TAG, "initMenu: start");
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
            Log.d(TAG, "menu click: position=" + position + ", name=" + name);
            showRightContent(position);
            enterRightContent();
        });
        rvLeftMenu.setAdapter(menuAdapter);
        showRightContent(0);
        rvLeftMenu.post(() -> {
            rvLeftMenu.requestFocus();
            focusLeftMenuItem(menuAdapter.getSelectedPosition());
        });
        Log.d(TAG, "initMenu: end, menuSize=" + menuList.size());
    }

    private void showRightContent(int position) {
        Log.d(TAG, "showRightContent: position=" + position);
        try {
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
            prepareRemoteFocus(view);
            Log.d(TAG, "showRightContent: layoutId=" + layoutId
                    + ", controller=" + currentController.getClass().getSimpleName());
        } catch (RuntimeException e) {
            Log.e(TAG, "showRightContent: failed for position=" + position, e);
            throw e;
        }
    }

    private void enterRightContent() {
        View firstFocusable = findFirstFocusable(rightContentContainer);
        if (firstFocusable != null) {
            Log.d(TAG, "enterRightContent: focus=" + firstFocusable.getClass().getSimpleName());
            focusRemoteView(firstFocusable);
        } else {
            Log.w(TAG, "enterRightContent: no focusable view found");
        }
    }

    private void focusRemoteView(View view) {
        if (!view.requestFocus()) {
            Log.w(TAG, "remote focus rejected: " + view.getClass().getSimpleName());
        }
    }

    private void prepareRemoteFocus(View view) {
        if (view == null) return;
        // focusSearch() only considers touch-mode focusable targets while the board
        // remains in touch mode. Prepare every option, not just the first one.
        if (view.isFocusable() && view.isEnabled()) view.setFocusableInTouchMode(true);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                prepareRemoteFocus(group.getChildAt(i));
            }
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
            Log.w(TAG, "focusLeftMenuItem: adapter is null");
            return;
        }

        RecyclerView.ViewHolder holder = rvLeftMenu.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            Log.d(TAG, "focusLeftMenuItem: position=" + position);
            holder.itemView.requestFocus();
        } else {
            Log.w(TAG, "focusLeftMenuItem: holder not attached, position=" + position);
        }
    }

    private void moveMenuSelection(int delta) {
        if (menuAdapter == null || menuList == null || menuList.isEmpty()) {
            Log.w(TAG, "moveMenuSelection: menu not ready");
            return;
        }

        int size = menuList.size();
        int nextPosition = (menuAdapter.getSelectedPosition() + delta + size) % size;
        Log.d(TAG, "moveMenuSelection: delta=" + delta + ", nextPosition=" + nextPosition);
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
                Log.w(TAG, "getLayoutId: unknown position=" + position + ", fallback to language page");
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
                Log.w(TAG, "createController: unknown position=" + position + ", use NoOpSettingsController");
                return new NoOpSettingsController();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ((com.example.ahd_camera.remote.BoardRemoteApplication) getApplication()).bindActivity(this);
        activeRemoteSettings = new WeakReference<>(this);
        Log.d(TAG, "onResume");
        Utils.hideSystemUI(getWindow());
        if (currentController != null) {
            currentController.onShow();
        }
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause");
        okHomeKeyHandler.cancel();
        if (activeRemoteSettings.get() == this) activeRemoteSettings.clear();
        if (currentController != null) {
            currentController.onHide();
        }
        super.onPause();
    }

    /** Camera_view keeps the remote connection while this page is visible. Called on the UI thread. */
    public static void dispatchRemoteCommand(String command) {
        Settings settings = activeRemoteSettings.get();
        if (settings == null) {
            Log.w(TAG, "remote command ignored: Settings not resumed, command=" + command);
            return;
        }
        settings.handleRemoteCommand(command);
    }

    public void handleRemoteCommand(String command) {
        Log.i(TAG, "remote command: " + command);
        if ("HOME".equals(command)) {
            finish();
            return;
        }
        View focused = getCurrentFocus();
        boolean inRightPane = isDescendant(focused, rightContentContainer);
        if ("BACK".equals(command) || "BACK_LONG".equals(command)) {
            if (inRightPane) {
                rvLeftMenu.requestFocus();
                rvLeftMenu.post(() -> focusLeftMenuItem(menuAdapter != null
                        ? menuAdapter.getSelectedPosition() : 0));
            } else {
                finish();
            }
            return;
        }
        if ("OK".equals(command)) {
            if (inRightPane && focused != null) focused.performClick();
            else enterRightContent();
            return;
        }
        int direction;
        switch (command) {
            case "UP_DOWN": direction = View.FOCUS_UP; break;
            case "DOWN_DOWN": direction = View.FOCUS_DOWN; break;
            case "LEFT_DOWN": direction = View.FOCUS_LEFT; break;
            case "RIGHT_DOWN": direction = View.FOCUS_RIGHT; break;
            default: return; // Direction release is only meaningful for motors.
        }
        if (!inRightPane) {
            if (direction == View.FOCUS_UP) moveMenuSelection(-1);
            else if (direction == View.FOCUS_DOWN) moveMenuSelection(1);
            // Match the board keys: OK enters the right pane; RIGHT alone does not.
            return;
        }
        if (focused instanceof SeekBar && (direction == View.FOCUS_LEFT
                || direction == View.FOCUS_RIGHT)) {
            SeekBar seekBar = (SeekBar) focused;
            int delta = direction == View.FOCUS_LEFT ? -1 : 1;
            seekBar.setProgress(Math.max(0, Math.min(seekBar.getMax(),
                    seekBar.getProgress() + delta)));
            return;
        }
        if (focused != null) {
            View next = focused.focusSearch(direction);
            if (next != null && isDescendant(next, rightContentContainer)) focusRemoteView(next);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        Log.d(TAG, "onWindowFocusChanged: hasFocus=" + hasFocus);
        if (hasFocus) {
            ((com.example.ahd_camera.remote.BoardRemoteApplication) getApplication()).bindActivity(this);
            Utils.hideSystemUI(getWindow());
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!HardwareKeySourceFilter.isBoardKey(event)) {
            return true;
        }
        if (okHomeKeyHandler.handle(event)) return true;
        // 实体/红外 MENU 只切换菜单，消费按下和松开，避免焦点控件把它当作确认。
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_3) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                Log.d(TAG, "dispatchKeyEvent: MENU close settings");
                finish();
            }
            return true;
        }
        // 只处理按下事件，避免按键抬起阶段重复触发。返回键的抬起事件也必须消费，
        // 否则系统会把已处理的两级返回当作默认返回再次执行。
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_Z) {
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        // ESC 分层处理：右侧内容区返回左栏，左栏直接退出设置页。
        View currentFocus = getCurrentFocus();
        boolean focusIsLeftMenu = isDescendant(currentFocus, rvLeftMenu);
        boolean focusIsRightPane = isDescendant(currentFocus, rightContentContainer);

        // 当前焦点在右侧内容区时，保留左右、上下的局部导航逻辑。
        if (focusIsRightPane) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE
                    || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_Z) {
                Log.d(TAG, "dispatchKeyEvent: back from right pane to left menu");
                rvLeftMenu.requestFocus();
                rvLeftMenu.post(() -> focusLeftMenuItem(menuAdapter != null ? menuAdapter.getSelectedPosition() : 0));
                return true;
            }

            if (currentFocus instanceof SeekBar) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_12
                        || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_13) {
                    SeekBar seekBar = (SeekBar) currentFocus;
                    int delta = event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_12 ? -1 : 1;
                    int nextProgress = Math.max(0, Math.min(seekBar.getProgress() + delta, seekBar.getMax()));
                    if (nextProgress != seekBar.getProgress()) {
                        Log.d(TAG, "dispatchKeyEvent: adjust seekbar progress=" + nextProgress);
                        seekBar.setProgress(nextProgress);
                    }
                    return true;
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_10
                        || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_11) {
                    return true;
                }
            }

            if (currentFocus != null) {
                int direction;
                switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_BUTTON_12:
                        direction = View.FOCUS_LEFT;
                        break;
                    case KeyEvent.KEYCODE_BUTTON_13:
                        direction = View.FOCUS_RIGHT;
                        break;
                    case KeyEvent.KEYCODE_BUTTON_10:
                        direction = View.FOCUS_UP;
                        break;
                    case KeyEvent.KEYCODE_BUTTON_11:
                        direction = View.FOCUS_DOWN;
                        break;
                    default:
                        direction = -1;
                        break;
                }

                if (direction != -1) {
                    View nextFocus = currentFocus.focusSearch(direction);
                    if (nextFocus == null || isDescendant(nextFocus, rvLeftMenu)) {
                        Log.w(TAG, "dispatchKeyEvent: blocked focus jump back to menu");
                        return true;
                    }
                    nextFocus.requestFocus();
                    return true;
                }
            }

            return super.dispatchKeyEvent(event);
        }

        // 当前焦点在左侧菜单或没有焦点时，使用方向键切换菜单项。
        if (focusIsLeftMenu || currentFocus == null) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_ESCAPE:
                case KeyEvent.KEYCODE_BUTTON_Z:
                    Log.d(TAG, "dispatchKeyEvent: back from left menu, finish settings");
                    finish();
                    return true;
                case KeyEvent.KEYCODE_BUTTON_10:
                    Log.d(TAG, "dispatchKeyEvent: move menu up");
                    moveMenuSelection(-1);
                    return true;
                case KeyEvent.KEYCODE_BUTTON_11:
                    Log.d(TAG, "dispatchKeyEvent: move menu down");
                    moveMenuSelection(1);
                    return true;
                case KeyEvent.KEYCODE_BUTTON_13:
                    Log.d(TAG, "dispatchKeyEvent: enter right pane");
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }

        return super.dispatchKeyEvent(event);
    }
}
