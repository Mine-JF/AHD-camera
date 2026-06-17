package com.example.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import android.util.Log;

// 背光写入器：把 0-10 的滑条值转换成 PWM，并写入系统背光节点。
public final class BacklightController {

    private BacklightController() {
    }

    private static final String TAG = "BacklightController";

    // 常见的背光节点路径，按顺序尝试写入。
    private static final String[] BACKLIGHT_PATHS = {
            "/sys/class/backlight/backlight/brightness",
            "/sys/class/backlight/panel0-backlight/brightness",
            "/sys/class/backlight/lcd-backlight/brightness",
            "/sys/class/leds/lcd-backlight/brightness"
    };

    // 把 0-10 映射到 10-255，最低保底 10，避免全黑。
    public static int mapLevelToPwm(int level) {
        // 先把输入值限制到合法范围，再映射到硬件可写的 PWM 区间。
        int clampedLevel = Math.max(0, Math.min(10, level));
        return 10 + Math.round((clampedLevel * 245f) / 10f);
    }

    public static void applyLevel(int level) {
        int pwm = mapLevelToPwm(level);
        Log.d(TAG, "applyLevel: level=" + level + ", pwm=" + pwm);
        writeBrightness(String.valueOf(pwm));
    }

    private static void writeBrightness(String value) {
        for (String path : BACKLIGHT_PATHS) {
            Log.d(TAG, "try write brightness path=" + path + ", value=" + value);
            if (writeToFile(path, value)) {
                Log.d(TAG, "write brightness success path=" + path + ", value=" + value);
                return;
            }
        }
        Log.e(TAG, "write brightness failed, no writable backlight path");
    }

    private static boolean writeToFile(String path, String value) {
        File file = new File(path);
        if (!file.exists() || !file.canWrite()) {
            return false;
        }

        try (FileOutputStream outputStream = new FileOutputStream(file, false)) {
            outputStream.write(value.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            return true;
        } catch (Exception ignored) {
            Log.e(TAG, "write failed path=" + path + ", reason=" + ignored.getMessage(), ignored);
            return false;
        }
    }
}
