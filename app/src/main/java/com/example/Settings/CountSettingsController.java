package com.example.Settings;

import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.example.ahd_camera.R;

import java.util.Locale;

// 计米页：负责单位切换和数值加减。
public class CountSettingsController implements SettingsContentController {

    private static final String TAG = "CountSettings";
    private boolean useMetric = true;
    private float meterStep = 1.00f;

    @Override
    public void bind(View view) {
        Log.d(TAG, "bind: start, useMetric=" + useMetric + ", meterStep=" + meterStep);
        Button metricButton = view.findViewById(R.id.btn_count_metric);
        Button imperialButton = view.findViewById(R.id.btn_count_imperial);
        TextView valueView = view.findViewById(R.id.tv_count_value);

        // 单位按钮互斥，保持当前选中状态同步。
        View.OnClickListener unitListener = clickedView -> {
            useMetric = clickedView.getId() == R.id.btn_count_metric;
            metricButton.setSelected(useMetric);
            imperialButton.setSelected(!useMetric);
            Log.d(TAG, "bind: unit changed, useMetric=" + useMetric);
        };
        metricButton.setOnClickListener(unitListener);
        imperialButton.setOnClickListener(unitListener);

        view.findViewById(R.id.btn_count_minus).setOnClickListener(v -> {
            float nextStep = Math.max(0.25f, meterStep - 0.25f);
            if (nextStep == meterStep) {
                Log.w(TAG, "minus clicked but already at minimum step=" + meterStep);
            }
            meterStep = nextStep;
            valueView.setText(String.format(Locale.US, "%.2f", meterStep));
            Log.d(TAG, "minus: meterStep=" + meterStep);
        });
        view.findViewById(R.id.btn_count_plus).setOnClickListener(v -> {
            float nextStep = Math.min(9.99f, meterStep + 0.25f);
            if (nextStep == meterStep) {
                Log.w(TAG, "plus clicked but already at maximum step=" + meterStep);
            }
            meterStep = nextStep;
            valueView.setText(String.format(Locale.US, "%.2f", meterStep));
            Log.d(TAG, "plus: meterStep=" + meterStep);
        });

        metricButton.setSelected(useMetric);
        imperialButton.setSelected(!useMetric);
        valueView.setText(String.format(Locale.US, "%.2f", meterStep));
        Log.d(TAG, "bind: end");
    }
}
