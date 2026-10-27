package com.example.Settings;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.SeekBar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.ahd_camera.R;

import java.util.Arrays;
import java.util.List;

/** The same settings pages, hosted above the live camera preview. */
public final class SettingsOverlayController {
    private final AppCompatActivity activity;
    private final View overlay;
    private final RecyclerView menu;
    private final FrameLayout content;
    private final List<String> entries;
    private final SettingMenuAdapter adapter;
    private SettingsContentController controller;

    public SettingsOverlayController(AppCompatActivity activity, View overlay) {
        this.activity = activity;
        this.overlay = overlay;
        menu = overlay.findViewById(R.id.overlay_left_menu);
        content = overlay.findViewById(R.id.overlay_right_content);
        entries = Arrays.asList(
                activity.getString(R.string.settings_menu_language),
                activity.getString(R.string.settings_menu_time),
                activity.getString(R.string.settings_menu_count),
                activity.getString(R.string.settings_menu_storage_card),
                activity.getString(R.string.settings_menu_display),
                activity.getString(R.string.settings_menu_sound),
                activity.getString(R.string.settings_menu_system_info),
                activity.getString(R.string.settings_menu_factory_reset));
        menu.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new SettingMenuAdapter(entries, (position, name) -> {
            showPage(position);
            enterContent();
        });
        menu.setAdapter(adapter);
        menu.setFocusableInTouchMode(true);
    }

    public boolean isVisible() {
        return overlay.getVisibility() == View.VISIBLE;
    }

    public void show() {
        if (isVisible()) return;
        overlay.setVisibility(View.VISIBLE);
        showPage(adapter.getSelectedPosition());
        menu.post(() -> focusMenu(adapter.getSelectedPosition()));
    }

    public void hide() {
        if (!isVisible()) return;
        if (controller != null) controller.onHide();
        controller = null;
        content.removeAllViews();
        overlay.setVisibility(View.GONE);
    }

    public void handleCommand(String command) {
        if (!isVisible()) return;
        if ("BACK".equals(command) || "BACK_LONG".equals(command)) {
            if (inside(activity.getCurrentFocus(), content)) {
                focusMenu(adapter.getSelectedPosition());
            } else {
                hide();
            }
            return;
        }
        if ("HOME".equals(command)) {
            hide();
            return;
        }
        View focused = activity.getCurrentFocus();
        boolean inContent = inside(focused, content);
        if ("OK".equals(command)) {
            if (inContent && focused != null) focused.performClick();
            else enterContent();
            return;
        }
        int direction;
        switch (command) {
            case "UP_DOWN": direction = View.FOCUS_UP; break;
            case "DOWN_DOWN": direction = View.FOCUS_DOWN; break;
            case "LEFT_DOWN": direction = View.FOCUS_LEFT; break;
            case "RIGHT_DOWN": direction = View.FOCUS_RIGHT; break;
            default: return;
        }
        if (!inContent) {
            if (direction == View.FOCUS_UP) moveSelection(-1);
            else if (direction == View.FOCUS_DOWN) moveSelection(1);
            return;
        }
        if (focused instanceof SeekBar && (direction == View.FOCUS_LEFT
                || direction == View.FOCUS_RIGHT)) {
            SeekBar bar = (SeekBar) focused;
            bar.setProgress(Math.max(0, Math.min(bar.getMax(), bar.getProgress()
                    + (direction == View.FOCUS_LEFT ? -1 : 1))));
            return;
        }
        if (focused != null) {
            View next = focused.focusSearch(direction);
            if (next != null && inside(next, content)) next.requestFocus();
        }
    }

    private void moveSelection(int delta) {
        int position = (adapter.getSelectedPosition() + delta + entries.size()) % entries.size();
        adapter.setSelectedPosition(position);
        menu.scrollToPosition(position);
        menu.post(() -> focusMenu(position));
        showPage(position);
    }

    private void focusMenu(int position) {
        RecyclerView.ViewHolder holder = menu.findViewHolderForAdapterPosition(position);
        if (holder != null) holder.itemView.requestFocus();
        else menu.requestFocus();
    }

    private void showPage(int position) {
        if (controller != null) controller.onHide();
        content.removeAllViews();
        int[] layouts = {R.layout.layout_language, R.layout.layout_time,
                R.layout.layout_date, R.layout.layout_sdcard, R.layout.layout_display,
                R.layout.layout_sound, R.layout.layout_system_info,
                R.layout.layout_factory_reset};
        View page = LayoutInflater.from(activity).inflate(layouts[position], content, false);
        content.addView(page);
        switch (position) {
            case 0: controller = new LanguageSettingsController(activity); break;
            case 1: controller = new TimeSettingsController(); break;
            case 2: controller = new CountSettingsController(); break;
            case 3: controller = new StorageSettingsController(activity); break;
            case 4: controller = new DisplaySettingsController(); break;
            case 5: controller = new SoundSettingsController(activity); break;
            case 6: controller = new SystemInfoSettingsController(activity); break;
            case 7: controller = new FactoryResetSettingsController(activity); break;
            default: controller = new NoOpSettingsController();
        }
        controller.bind(page);
        controller.onShow();
        prepareFocus(page);
    }

    private void enterContent() {
        View first = firstFocusable(content);
        if (first != null) first.requestFocus();
    }

    private static void prepareFocus(View view) {
        if (view.isFocusable() && view.isEnabled()) view.setFocusableInTouchMode(true);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) prepareFocus(group.getChildAt(i));
        }
    }

    private static View firstFocusable(View view) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view.isFocusable() && view.isEnabled()) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = firstFocusable(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean inside(View child, View root) {
        while (child != null) {
            if (child == root) return true;
            ViewParent parent = child.getParent();
            child = parent instanceof View ? (View) parent : null;
        }
        return false;
    }
}
