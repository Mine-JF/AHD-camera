package com.example.ahd_camera;

import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * 板载按键来源过滤器。
 * 只允许板载 GPIO、ADC 按键和指定的板载红外接收器触发 APK 的硬件快捷键，
 * 防止外接 USB/2.4G 键盘发送相同 KEY_* 键值时误操作设备。
 */
public final class HardwareKeySourceFilter {

    private static final String TAG = "HardwareKeySource";
    private static final String GPIO_KEYS_DEVICE_NAME = "gpio-keys";
    private static final String ADC_KEYS_DEVICE_NAME = "adc-keys";
    private static final String IR_DEVICE_NAME = "fe700030.pwm";
    private static final int IR_VENDOR_ID = 0x524b;
    private static final int IR_PRODUCT_ID = 0x0006;

    private HardwareKeySourceFilter() {
    }

    /**
     * 判断事件是否来自板载 GPIO、ADC 按键或指定红外接收器。
     * 不使用 /dev/input/eventX 编号，因为插拔 USB 设备后编号会改变；使用 InputDevice 名称更稳定。
     */
    public static boolean isBoardKey(KeyEvent event) {
        InputDevice device = InputDevice.getDevice(event.getDeviceId());
        boolean accepted = device != null && isBoardDevice(device);

        if (!accepted && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            Log.d(TAG, "Non-board key: " + describeDevice(event, device));
        }
        return accepted;
    }

    /**
     * 只有真正的物理字母键盘才允许进入预览页的文字输入。
     * 鼠标、Consumer Control 等同一接收器暴露出来的辅助 input 节点不会被当成文字键盘。
     */
    public static boolean isExternalTextKeyboard(KeyEvent event) {
        InputDevice device = InputDevice.getDevice(event.getDeviceId());
        if (device == null || isBoardDevice(device)) {
            return false;
        }
        boolean hasKeyboardSource = (device.getSources() & InputDevice.SOURCE_KEYBOARD)
                == InputDevice.SOURCE_KEYBOARD;
        return hasKeyboardSource
                && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
    }

    private static boolean isBoardDevice(InputDevice device) {
        String deviceName = device.getName();
        return GPIO_KEYS_DEVICE_NAME.equals(deviceName)
                || ADC_KEYS_DEVICE_NAME.equals(deviceName)
                || (IR_DEVICE_NAME.equals(deviceName)
                && device.getVendorId() == IR_VENDOR_ID
                && device.getProductId() == IR_PRODUCT_ID);
    }

    private static String describeDevice(KeyEvent event, InputDevice device) {
        if (device == null) {
            return "deviceId=" + event.getDeviceId() + ", name=null"
                    + ", keyCode=" + event.getKeyCode() + ", scanCode=" + event.getScanCode();
        }
        return "deviceId=" + event.getDeviceId()
                + ", name=" + device.getName()
                + ", vendorId=0x" + Integer.toHexString(device.getVendorId())
                + ", productId=0x" + Integer.toHexString(device.getProductId())
                + ", sources=0x" + Integer.toHexString(device.getSources())
                + ", keyboardType=" + device.getKeyboardType()
                + ", keyCode=" + event.getKeyCode()
                + ", scanCode=" + event.getScanCode();
    }
}
