package com.example.ahd_camera;

import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * 板载按键来源过滤器。
 * 只允许设备树创建的 gpio-keys 与 adc-keys 触发 APK 的硬件快捷键，
 * 防止外接 USB/2.4G 键盘发送相同 KEY_* 键值时误操作设备。
 */
public final class HardwareKeySourceFilter {

    private static final String TAG = "HardwareKeySource";
    private static final String GPIO_KEYS_DEVICE_NAME = "gpio-keys";
    private static final String ADC_KEYS_DEVICE_NAME = "adc-keys";

    private HardwareKeySourceFilter() {
    }

    /**
     * 判断事件是否来自板载 GPIO 或 ADC 按键。
     * 不使用 /dev/input/eventX 编号，因为插拔 USB 设备后编号会改变；使用 InputDevice 名称更稳定。
     */
    public static boolean isBoardKey(KeyEvent event) {
        InputDevice device = InputDevice.getDevice(event.getDeviceId());
        String deviceName = device != null ? device.getName() : null;
        boolean accepted = GPIO_KEYS_DEVICE_NAME.equals(deviceName)
                || ADC_KEYS_DEVICE_NAME.equals(deviceName);

        if (!accepted && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            Log.d(TAG, "Ignore non-board key: deviceId=" + event.getDeviceId()
                    + ", name=" + deviceName + ", keyCode=" + event.getKeyCode()
                    + ", scanCode=" + event.getScanCode());
        }
        return accepted;
    }
}
