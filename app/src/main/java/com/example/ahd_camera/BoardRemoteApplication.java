package com.example.ahd_camera;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import com.example.Settings.Settings;

/** Keeps the LAN connection alive while board activities are recreated or switched. */
public final class BoardRemoteApplication extends Application
        implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "BoardRemoteApplication";
    private BoardRemoteServer remoteServer;
    private BoardDiscoveryServer discoveryServer;
    private Activity currentActivity;
    private Activity lastBoundActivity;

    @Override
    public void onCreate() {
        super.onCreate();
        if (!"board".equals(BuildConfig.FLAVOR)) return;
        registerActivityLifecycleCallbacks(this);
        remoteServer = new BoardRemoteServer(new Handler(Looper.getMainLooper()),
                this::dispatchRemoteCommand);
        remoteServer.start();
        discoveryServer = new BoardDiscoveryServer(this);
        discoveryServer.start();
    }

    private void dispatchRemoteCommand(String command) {
        Activity activity = currentActivity != null ? currentActivity : lastBoundActivity;
        Log.i(TAG, "remote command=" + command + ", page="
                + (activity == null ? "none" : activity.getClass().getSimpleName()));
        if (activity instanceof Camera_view) {
            ((Camera_view) activity).handleRemoteCommand(command);
        } else if (activity instanceof Settings) {
            if ("MENU".equals(command)) {
                activity.finish();
            } else {
                ((Settings) activity).handleRemoteCommand(command);
            }
        } else if (activity != null) {
            dispatchOtherScreenCommand(activity, command);
        } else {
            Log.w(TAG, "command without resumed board page: " + command);
        }
    }

    private void dispatchOtherScreenCommand(Activity activity, String command) {
        switch (command) {
            case "BACK": case "BACK_LONG":
                activity.onBackPressed();
                return;
            case "HOME": case "MENU":
                Intent preview = new Intent(activity, Camera_view.class);
                preview.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                activity.startActivity(preview);
                return;
            case "OK":
                View selected = activity.getCurrentFocus();
                if (selected != null) selected.performClick();
                return;
            case "UP_DOWN": case "DOWN_DOWN": case "LEFT_DOWN": case "RIGHT_DOWN":
                View current = activity.getCurrentFocus();
                if (current == null) return;
                int direction = "UP_DOWN".equals(command) ? View.FOCUS_UP
                        : "DOWN_DOWN".equals(command) ? View.FOCUS_DOWN
                        : "LEFT_DOWN".equals(command) ? View.FOCUS_LEFT : View.FOCUS_RIGHT;
                View next = current.focusSearch(direction);
                if (next != null) {
                    next.setFocusableInTouchMode(true);
                    next.requestFocus();
                }
                return;
            default:
                Log.d(TAG, "command has no action on " + activity.getClass().getSimpleName()
                        + ": " + command);
        }
    }

    @Override
    public void onActivityResumed(Activity activity) {
        bindActivity(activity);
    }

    public void bindActivity(Activity activity) {
        currentActivity = activity;
        lastBoundActivity = activity;
        Log.i(TAG, "bind page=" + activity.getClass().getSimpleName()
                + ", instance=" + System.identityHashCode(activity));
        if (remoteServer == null) return;
        if (activity instanceof Camera_view) {
            remoteServer.setPreviewRotation(((Camera_view) activity).getPreviewRotationDegrees());
            remoteServer.setPreviewViews(activity.findViewById(R.id.camera_container),
                    activity.findViewById(R.id.camera_hud));
        } else {
            remoteServer.setPreviewViews(activity.getWindow().getDecorView(), null);
        }
        remoteServer.setVideoPaused(false);
    }

    void setPreviewRotation(int degrees) {
        if (remoteServer != null) remoteServer.setPreviewRotation(degrees);
    }

    @Override
    public void onActivityPaused(Activity activity) {
        // Paused does not mean invisible: switching focus to the phone emulator or
        // rebuilding Settings for a locale change can pause the board page briefly.
        // Keep routing commands and streaming until it is actually stopped.
        Log.i(TAG, "pause page=" + activity.getClass().getSimpleName()
                + ", keep binding until stopped");
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) {
        // Locale changes can replace Settings between pause and resume. Bind as soon
        // as the replacement has started so commands and video do not stay on "none".
        bindActivity(activity);
    }
    @Override public void onActivityStopped(Activity activity) {
        if (currentActivity != activity) return;
        Log.i(TAG, "stop page=" + activity.getClass().getSimpleName()
                + ", clear binding");
        currentActivity = null;
        if (remoteServer != null) {
            remoteServer.setVideoPaused(true);
            remoteServer.setPreviewViews(null, null);
        }
    }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) {
        if (lastBoundActivity == activity) lastBoundActivity = null;
        if (currentActivity == activity) {
            currentActivity = null;
            if (remoteServer != null) {
                remoteServer.setVideoPaused(true);
                remoteServer.setPreviewViews(null, null);
            }
        }
    }
}
