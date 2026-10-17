package com.example.ahd_camera;

import android.content.Context;

/** The counter reports distance in metres; only the displayed unit changes. */
public final class DistanceUnitPreference {
    private static final String PREFS = "distance_display";
    private static final String KEY_FEET = "use_feet";

    private DistanceUnitPreference() { }

    public static boolean usesFeet(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_FEET, false);
    }

    public static void setUsesFeet(Context context, boolean useFeet) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_FEET, useFeet).commit();
    }
}
