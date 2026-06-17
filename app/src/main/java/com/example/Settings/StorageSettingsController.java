package com.example.Settings;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;
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

    private static final String TAG = "StorageSettings";
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
        Log.d(TAG, "bind: start");
        storageInfoView = view.findViewById(R.id.tv_storage_info);
        storageFormatButton = view.findViewById(R.id.btn_storage_format);

        // 点击格式化按钮先走确认弹窗。
        storageFormatButton.setOnClickListener(v -> showFormatConfirmDialog());
        updateStorageInfo();
        Log.d(TAG, "bind: end");
    }

    @Override
    public void onShow() {
        Log.d(TAG, "onShow");
        active = true;
        handler.removeCallbacks(poller);
        handler.post(poller);
    }

    @Override
    public void onHide() {
        Log.d(TAG, "onHide");
        active = false;
        handler.removeCallbacks(poller);
    }

    private void showFormatConfirmDialog() {
        Log.w(TAG, "showFormatConfirmDialog: user requested storage format");
        new AlertDialog.Builder(context)
                .setTitle(R.string.settings_format_confirm_title)
                .setMessage(R.string.settings_format_confirm_message)
                .setPositiveButton(R.string.settings_yes, (dialog, which) -> performStorageFormat())
                .setNegativeButton(R.string.settings_no, (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void performStorageFormat() {
        // 这里后续需要接系统/厂商的外部存储格式化接口。
        Log.w(TAG, "performStorageFormat: not implemented yet");
        Toast.makeText(context, R.string.settings_format_not_implemented, Toast.LENGTH_SHORT).show();
    }

    private void updateStorageInfo() {
        if (storageInfoView == null) {
            Log.w(TAG, "updateStorageInfo: storageInfoView is null");
            return;
        }

        if (storageManager == null) {
            Log.e(TAG, "updateStorageInfo: storageManager unavailable");
            storageInfoView.setText(R.string.settings_storage_manager_unavailable);
            updateFormatButtonState(false);
            return;
        }

        try {
            List<StorageVolume> removableVolumes = new ArrayList<>();
            for (StorageVolume volume : storageManager.getStorageVolumes()) {
                if (volume != null && volume.isRemovable()) {
                    removableVolumes.add(volume);
                }
            }

            if (removableVolumes.isEmpty()) {
                Log.w(TAG, "updateStorageInfo: no removable volumes");
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
                    Log.d(TAG, "updateStorageInfo: volume=" + path
                            + ", usedBytes=" + usedBytes
                            + ", totalBytes=" + totalBytes);
                } else {
                    Log.w(TAG, "updateStorageInfo: volume has no directory, description=" + description);
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
        } catch (RuntimeException e) {
            Log.e(TAG, "updateStorageInfo: failed", e);
            storageInfoView.setText(R.string.settings_storage_manager_unavailable);
            updateFormatButtonState(false);
        }
    }

    private void updateFormatButtonState(boolean enabled) {
        if (storageFormatButton != null) {
            Log.d(TAG, "updateFormatButtonState: enabled=" + enabled);
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
