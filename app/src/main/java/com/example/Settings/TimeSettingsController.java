package com.example.Settings;

import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.example.ahd_camera.R;

import java.util.Calendar;
import java.util.Locale;

// 时间页：处理年月日时分秒的加减。
public class TimeSettingsController implements SettingsContentController {

    private static final String TAG = "TimeSettings";
    private final Calendar settingsCalendar = Calendar.getInstance();

    @Override
    public void bind(View view) {
        Log.d(TAG, "bind: start");
        view.findViewById(R.id.btn_year_plus).setOnClickListener(v -> updateCalendar(view, Calendar.YEAR, 1));
        view.findViewById(R.id.btn_year_minus).setOnClickListener(v -> updateCalendar(view, Calendar.YEAR, -1));
        view.findViewById(R.id.btn_month_plus).setOnClickListener(v -> updateCalendar(view, Calendar.MONTH, 1));
        view.findViewById(R.id.btn_month_minus).setOnClickListener(v -> updateCalendar(view, Calendar.MONTH, -1));
        view.findViewById(R.id.btn_day_plus).setOnClickListener(v -> updateCalendar(view, Calendar.DAY_OF_MONTH, 1));
        view.findViewById(R.id.btn_day_minus).setOnClickListener(v -> updateCalendar(view, Calendar.DAY_OF_MONTH, -1));
        view.findViewById(R.id.btn_hour_plus).setOnClickListener(v -> updateCalendar(view, Calendar.HOUR_OF_DAY, 1));
        view.findViewById(R.id.btn_hour_minus).setOnClickListener(v -> updateCalendar(view, Calendar.HOUR_OF_DAY, -1));
        view.findViewById(R.id.btn_minute_plus).setOnClickListener(v -> updateCalendar(view, Calendar.MINUTE, 1));
        view.findViewById(R.id.btn_minute_minus).setOnClickListener(v -> updateCalendar(view, Calendar.MINUTE, -1));
        view.findViewById(R.id.btn_second_plus).setOnClickListener(v -> updateCalendar(view, Calendar.SECOND, 1));
        view.findViewById(R.id.btn_second_minus).setOnClickListener(v -> updateCalendar(view, Calendar.SECOND, -1));
        refreshTimeValues(view);
        Log.d(TAG, "bind: end");
    }

    private void updateCalendar(View view, int field, int delta) {
        Log.d(TAG, "updateCalendar: field=" + field + ", delta=" + delta);
        settingsCalendar.add(field, delta);
        refreshTimeValues(view);
    }

    private void refreshTimeValues(View view) {
        try {
            ((TextView) view.findViewById(R.id.tv_year_value)).setText(String.valueOf(settingsCalendar.get(Calendar.YEAR)));
            ((TextView) view.findViewById(R.id.tv_month_value)).setText(formatTwoDigits(settingsCalendar.get(Calendar.MONTH) + 1));
            ((TextView) view.findViewById(R.id.tv_day_value)).setText(formatTwoDigits(settingsCalendar.get(Calendar.DAY_OF_MONTH)));
            ((TextView) view.findViewById(R.id.tv_hour_value)).setText(formatTwoDigits(settingsCalendar.get(Calendar.HOUR_OF_DAY)));
            ((TextView) view.findViewById(R.id.tv_minute_value)).setText(formatTwoDigits(settingsCalendar.get(Calendar.MINUTE)));
            ((TextView) view.findViewById(R.id.tv_second_value)).setText(formatTwoDigits(settingsCalendar.get(Calendar.SECOND)));
            Log.d(TAG, "refreshTimeValues: " + settingsCalendar.getTime());
        } catch (RuntimeException e) {
            Log.e(TAG, "refreshTimeValues: failed", e);
        }
    }

    private String formatTwoDigits(int value) {
        return String.format(Locale.US, "%02d", value);
    }
}
