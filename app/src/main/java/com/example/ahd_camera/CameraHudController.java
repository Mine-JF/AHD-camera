package com.example.ahd_camera;

import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class CameraHudController {

    private static final String TAG = "CameraHudController";

    private final AppCompatActivity activity;
    private final MicrophoneController microphoneController;
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    private TextView dateTimeView;
    private TextView distanceView;
    private ImageView micIconView;
    private ImageView micOffBadgeView;
    private ImageView wifiIconView;
    private ImageView storageIconView;
    private ImageView storageOffBadgeView;
    private ImageView batteryIconView;
    private TextView batteryStatusView;
    private float distanceMeters;

    CameraHudController(AppCompatActivity activity, MicrophoneController microphoneController) {
        this.activity = activity;
        this.microphoneController = microphoneController;
        dateFormat.setTimeZone(TimeZone.getDefault());
        Log.d(TAG, "init: timeZone=" + TimeZone.getDefault().getID());
    }

    // 绑定 HUD 视图引用，避免页面层直接处理顶部状态栏细节。
    void bind() {
        dateTimeView = activity.findViewById(R.id.hud_datetime);
        distanceView = activity.findViewById(R.id.hud_distance);
        micIconView = activity.findViewById(R.id.hud_mic_icon);
        micOffBadgeView = activity.findViewById(R.id.hud_mic_off_badge);
        wifiIconView = activity.findViewById(R.id.hud_wifi_icon);
        storageIconView = activity.findViewById(R.id.hud_storage_icon);
        storageOffBadgeView = activity.findViewById(R.id.hud_storage_off_badge);
        batteryIconView = activity.findViewById(R.id.hud_battery_icon);
        batteryStatusView = activity.findViewById(R.id.hud_battery_status);
        Log.d(TAG, "bind: viewsBound="
                + (dateTimeView != null && distanceView != null && micIconView != null
                && wifiIconView != null && storageIconView != null && batteryIconView != null));
    }

    // 接收页面层传入的实时测距值，实际刷新由 refresh() 统一执行。
    void setDistanceMeters(float distanceMeters) {
        this.distanceMeters = distanceMeters;
        Log.d(TAG, "setDistanceMeters: distanceMeters=" + distanceMeters);
    }

    // 统一刷新顶部 OSD，便于在时间、距离、网络、存储或麦克风状态变化时复用。
    void refresh() {
        Log.d(TAG, "refresh: distanceMeters=" + distanceMeters);
        if (dateTimeView != null) {
            String dateTime = dateFormat.format(new Date());
            dateTimeView.setText(activity.getString(R.string.camera_hud_datetime_format, dateTime));
        }

        if (distanceView != null) {
            boolean useFeet = DistanceUnitPreference.usesFeet(activity);
            float displayedDistance = useFeet ? distanceMeters * 3.28084f : distanceMeters;
            Log.d(TAG, "distance display: unit=" + (useFeet ? "FT" : "M")
                    + ", meters=" + distanceMeters + ", displayed=" + displayedDistance);
            distanceView.setText(activity.getString(useFeet
                    ? R.string.camera_hud_distance_feet_format
                    : R.string.camera_hud_distance_format, displayedDistance));
        }

        boolean micEnabled = microphoneController.isEnabled();
        if (micIconView != null) {
            micIconView.setAlpha(1.0f);
        }
        if (micOffBadgeView != null) {
            micOffBadgeView.setVisibility(micEnabled ? View.GONE : View.VISIBLE);
        }

        boolean wifiConnected = isWifiConnected();
        int wifiIconRes = resolveWifiIconRes();
        if (wifiIconView != null) {
            wifiIconView.setImageResource(wifiIconRes);
            wifiIconView.setAlpha(wifiConnected ? 1.0f : 0.45f);
        }

        boolean hasStorage = hasRemovableStorageMounted();
        if (storageIconView != null) {
            storageIconView.setAlpha(1.0f);
        }
        if (storageOffBadgeView != null) {
            storageOffBadgeView.setVisibility(hasStorage ? View.GONE : View.VISIBLE);
        }

        if (batteryIconView != null) {
            batteryIconView.setAlpha(1.0f);
        }
        if (batteryStatusView != null) {
            batteryStatusView.setText(readBatteryLabel());
        }
        Log.d(TAG, "refresh finished: micEnabled=" + micEnabled
                + ", wifiConnected=" + wifiConnected
                + ", wifiIconRes=" + wifiIconRes
                + ", hasStorage=" + hasStorage);
    }

    // 判断当前是否存在有效的 Wi‑Fi 连接，供信号图标和透明度一起使用。
    private boolean isWifiConnected() {
        ConnectivityManager connectivityManager = activity.getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) {
            Log.w(TAG, "isWifiConnected: connectivityManager is null");
            return false;
        }
        android.net.Network activeNetwork = connectivityManager.getActiveNetwork();
        if (activeNetwork == null) {
            return false;
        }
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    // 根据 RSSI 分级选择 Wi‑Fi 图标资源，避免 HUD 层再写一套判断逻辑。
    private int resolveWifiIconRes() {
        if (!isWifiConnected()) {
            return R.drawable.ic_hud_wifi_0;
        }
        WifiManager wifiManager = activity.getApplicationContext().getSystemService(WifiManager.class);
        if (wifiManager == null) {
            Log.w(TAG, "resolveWifiIconRes: wifiManager is null");
            return R.drawable.ic_hud_wifi_1;
        }
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        if (wifiInfo == null) {
            Log.w(TAG, "resolveWifiIconRes: wifiInfo is null");
            return R.drawable.ic_hud_wifi_1;
        }
        int level = WifiManager.calculateSignalLevel(wifiInfo.getRssi(), 5);
        Log.d(TAG, "resolveWifiIconRes: rssi=" + wifiInfo.getRssi() + ", level=" + level);
        switch (level) {
            case 4:
                return R.drawable.ic_hud_wifi_4;
            case 3:
                return R.drawable.ic_hud_wifi_3;
            case 2:
                return R.drawable.ic_hud_wifi_2;
            case 1:
                return R.drawable.ic_hud_wifi_1;
            default:
                return R.drawable.ic_hud_wifi_0;
        }
    }

    private String readBatteryLabel() {
        Intent batteryStatus = activity.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryStatus == null) {
            Log.w(TAG, "readBatteryLabel: batteryStatus is null");
            return "--%";
        }
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) {
            Log.w(TAG, "readBatteryLabel: invalid level=" + level + ", scale=" + scale);
            return "--%";
        }
        int percent = Math.round(level * 100f / scale);
        Log.d(TAG, "readBatteryLabel: percent=" + percent);
        return percent + "%";
    }

    // 判断是否存在可移除存储卡或 U 盘，用于控制 SD 图标右上角红色 X。
    private boolean hasRemovableStorageMounted() {
        boolean hasStorage = StorageUtils.hasMountedRemovableStorage(activity);
        Log.d(TAG, "hasRemovableStorageMounted: hasStorage=" + hasStorage);
        return hasStorage;
    }
}
