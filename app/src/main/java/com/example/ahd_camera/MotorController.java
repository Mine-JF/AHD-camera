package com.example.ahd_camera;

import android.util.Log;

/**
 * Motorcontrl_sensor 的 RS485 控制封装。
 * 串口固定为 /dev/ttyS3，RS485 收发方向由硬件处理；Activity 暂停时必须调用 stopAndClose() 释放电机。
 */
final class MotorController {

    private static final String TAG = "MotorController";
    private static final String DEVICE_PATH = "/dev/ttyS3";

    // Motor 1 is the mechanical fore/aft (tilt) axis; Motor 2 is pan.
    // Commands 0x05/0x06 control the short-stroke VCM and are not the visible tilt axis.
    static final int COMMAND_UP = 0x01;
    static final int COMMAND_DOWN = 0x02;
    static final int COMMAND_LEFT = 0x03;
    static final int COMMAND_RIGHT = 0x04;
    static final int COMMAND_STOP_ALL = 0x07;
    static final int COMMAND_STOP_TILT = 0x08;
    static final int COMMAND_STOP_PAN = 0x09;
    static final int COMMAND_FOCUS_UP = 0x05;
    static final int COMMAND_FOCUS_DOWN = 0x06;
    static final int COMMAND_STOP_FOCUS = 0x0A;
    static final int COMMAND_FOCUS_SPEED_UP = 0x0C;
    static final int COMMAND_FOCUS_SPEED_DOWN = 0x0D;
    // 当前单摄方案：由 MCU 同时控制全部补光灯。
    // 后续双摄可按协议使用 0x11/0x12（前灯）和 0x13/0x14（后灯）。
    static final int COMMAND_ALL_LIGHT_ON = 0x0E;
    static final int COMMAND_ALL_LIGHT_OFF = 0x0F;
    static final int LIGHT_MAX_BRIGHTNESS = 10;

    private boolean opened;

    /** 打开控制器串口；重复调用时复用已打开的连接。 */
    boolean start() {
        if (opened) {
            return true;
        }
        opened = V4l2CameraNative.openMotorController(DEVICE_PATH);
        Log.i(TAG, "start: opened=" + opened + ", device=" + DEVICE_PATH);
        return opened;
    }

    /** 控制电机1（上下）或电机2（左右）开始运动。 */
    void move(int command) {
        if (!start()) {
            return;
        }
        V4l2CameraNative.sendMotorCommand(command, 0);
    }

    /** 根据运动轴选择停止电机1或电机2，不影响另一个轴和 VCM。 */
    void stopForMove(int moveCommand) {
        if (!opened) {
            return;
        }
        int stopCommand = moveCommand == COMMAND_UP || moveCommand == COMMAND_DOWN
                ? COMMAND_STOP_TILT : COMMAND_STOP_PAN;
        V4l2CameraNative.sendMotorCommand(stopCommand, 0);
    }

    /**
     * 以指定的 1~10 档驱动电机3 VCM。档位代表 PWM 占空比，不使用 0=默认10档，
     * 以便页面层可以逐级调整。MCU 会持续保持当前驱动，直到收到新命令或停止命令。
     */
    boolean moveFocusAtLevel(int command, int level) {
        if ((command != COMMAND_FOCUS_UP && command != COMMAND_FOCUS_DOWN)
                || level < 1 || level > 10 || !start()) {
            return false;
        }
        return V4l2CameraNative.sendMotorCommand(command, level);
    }

    /** 让 VCM 进入 Coast 模式并断电，镜头可能因弹簧作用回位。 */
    void stopFocus() {
        if (opened) {
            V4l2CameraNative.sendMotorCommand(COMMAND_STOP_FOCUS, 0);
        }
    }

    /** 设置全部补光灯：开启时固定发送10档（100%），关闭时发送全部灯关。 */
    boolean setLightEnabled(boolean enabled) {
        if (!start()) {
            return false;
        }
        return V4l2CameraNative.sendMotorCommand(
                enabled ? COMMAND_ALL_LIGHT_ON : COMMAND_ALL_LIGHT_OFF,
                enabled ? LIGHT_MAX_BRIGHTNESS : 0);
    }

    /** 页面离开时停止全部电机并关闭串口，防止云台或 VCM 持续通电。 */
    void stopAndClose() {
        if (!opened) {
            return;
        }
        V4l2CameraNative.sendMotorCommand(COMMAND_STOP_ALL, 0);
        V4l2CameraNative.closeMotorController();
        opened = false;
        Log.i(TAG, "stopAndClose");
    }

    boolean resetDistanceCounter() {
        return start() && V4l2CameraNative.resetDistanceCounter();
    }

    Integer readDistanceCounter() {
        return start() ? V4l2CameraNative.readDistanceCounter() : null;
    }
}
