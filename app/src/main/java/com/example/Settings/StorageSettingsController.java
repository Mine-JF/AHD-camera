package com.example.Settings;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.example.ahd_camera.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// 存储页：自动检测外部存储并显示容量。
public class StorageSettingsController implements SettingsContentController {

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StorageManager storageManager;
    private TextView storageInfoView;
    private Button storageFormatButton;
    private boolean active;

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            if (active && storageInfoView != null) {
                updateStorageInfo();
                handler.postDelayed(this, 2000);
            }
        }
    };

    public StorageSettingsController(Context context) {
        this.context = context;
        this.storageManager = context.getSystemService(StorageManager.class);
    }

    @Override
    public void bind(View view) {
        storageInfoView = view.findViewById(R.id.tv_storage_info);
        storageFormatButton = view.findViewById(R.id.btn_storage_format);

        // 点击格式化按钮先走确认弹窗。
        storageFormatButton.setOnClickListener(v -> showFormatConfirmDialog());
        updateStorageInfo();
    }

    @Override
    public void onShow() {
        active = true;
        handler.removeCallbacks(poller);
        handler.post(poller);
    }

    @Override
    public void onHide() {
        active = false;
        handler.removeCallbacks(poller);
    }

    private void showFormatConfirmDialog() {
        new AlertDialog.Builder(context)
                .setTitle(R.string.settings_format_confirm_title)
                .setMessage(R.string.settings_format_confirm_message)
                .setPositiveButton(R.string.settings_yes, (dialog, which) -> performStorageFormat())
                .setNegativeButton(R.string.settings_no, (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void performStorageFormat() {
        // 这里后续需要接系统/厂商的外部存储格式化接口。
        Toast.makeText(context, R.string.settings_format_not_implemented, Toast.LENGTH_SHORT).show();
    }

    private void updateStorageInfo() {
        if (storageInfoView == null) {
            return;
        }

        if (storageManager == null) {
            storageInfoView.setText(R.string.settings_storage_manager_unavailable);
            updateFormatButtonState(false);
            return;
        }

        List<StorageVolume> removableVolumes = new ArrayList<>();
        for (StorageVolume volume : storageManager.getStorageVolumes()) {
            if (volume != null && volume.isRemovable()) {
                removableVolumes.add(volume);
            }
        }

        if (removableVolumes.isEmpty()) {
            storageInfoView.setText(R.string.settings_storage_none);
            updateFormatButtonState(false);
            return;
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < removableVolumes.size(); i++) {
            StorageVolume volume = removableVolumes.get(i);
            String description = volume.getDescription(context);
            String state = volume.getState();
            String path = volume.getDirectory() != null ? volume.getDirectory().getAbsolutePath() : "-";
            String capacityLine = context.getString(R.string.settings_storage_capacity_unknown);

            if (volume.getDirectory() != null) {
                StatFs statFs = new StatFs(volume.getDirectory().getAbsolutePath());
                long totalBytes = statFs.getTotalBytes();
                long freeBytes = statFs.getAvailableBytes();
                long usedBytes = Math.max(0L, totalBytes - freeBytes);
                capacityLine = context.getString(R.string.settings_storage_capacity_prefix)
                        + formatBytes(usedBytes) + " / " + formatBytes(totalBytes);
            }

            builder.append(i + 1).append(". ");
            builder.append(description != null ? description : context.getString(R.string.settings_storage_unknown));
            builder.append('\n');
            builder.append(capacityLine).append('\n');
            builder.append(context.getString(R.string.settings_storage_state_prefix)).append(state);
            builder.append('\n');
            builder.append(context.getString(R.string.settings_storage_path_prefix)).append(path);

            if (i < removableVolumes.size() - 1) {
                builder.append("\n\n");
            }
        }

        storageInfoView.setText(builder.toString());
        updateFormatButtonState(true);
    }

    private void updateFormatButtonState(boolean enabled) {
        if (storageFormatButton != null) {
            storageFormatButton.setEnabled(enabled);
            storageFormatButton.setAlpha(enabled ? 1.0f : 0.45f);
        }
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f GB", bytes / (1024f * 1024f * 1024f));
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
        }
        if (bytes >= 1024L) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        }
        return bytes + " B";
    }
}
