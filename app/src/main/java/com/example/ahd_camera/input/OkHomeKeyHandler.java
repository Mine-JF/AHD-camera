package com.example.ahd_camera.input;

import com.example.ahd_camera.Camera_view;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

/** Distinguishes a short OK press from a long HOME press on board/IR BUTTON_7. */
public final class OkHomeKeyHandler {
    private static final long LONG_PRESS_MS = 650;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable shortPress;
    private final Runnable longPress;
    private boolean pressed;
    private boolean longPressHandled;
    private final Runnable longPressTask;

    public OkHomeKeyHandler(Runnable shortPress, Runnable longPress) {
        this.shortPress = shortPress;
        this.longPress = longPress;
        this.longPressTask = () -> {
            if (pressed) {
                longPressHandled = true;
                this.longPress.run();
            }
        };
    }

    public boolean handle(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_BUTTON_7) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (!pressed && event.getRepeatCount() == 0) {
                pressed = true;
                longPressHandled = false;
                handler.postDelayed(longPressTask, LONG_PRESS_MS);
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            boolean wasPressed = pressed;
            boolean wasLongPress = longPressHandled;
            cancel();
            if (wasPressed && !wasLongPress) shortPress.run();
        }
        return true;
    }

    public void cancel() {
        handler.removeCallbacks(longPressTask);
        pressed = false;
        longPressHandled = false;
    }

    public static void returnToPreview(Activity activity) {
        Intent intent = new Intent(activity, Camera_view.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivity(intent);
    }
}
