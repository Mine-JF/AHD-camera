package com.example.Settings;

import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.example.ahd_camera.R;

import java.util.Locale;

// 计米页：负责单位切换和数值加减。
public class CountSettingsController implements SettingsContentController {

    private boolean useMetric = true;
    private float meterStep = 1.00f;

    @Override
    public void bind(View view) {
        Button metricButton = view.findViewById(R.id.btn_count_metric);
        Button imperialButton = view.findViewById(R.id.btn_count_imperial);
        TextView valueView = view.findViewById(R.id.tv_count_value);

        View.OnClickListener unitListener = clickedView -> {
            useMetric = clickedView.getId() == R.id.btn_count_metric;
            metricButton.setSelected(useMetric);
            imperialButton.setSelected(!useMetric);
        };
        metricButton.setOnClickListener(unitListener);
        imperialButton.setOnClickListener(unitListener);

        view.findViewById(R.id.btn_count_minus).setOnClickListener(v -> {
            meterStep = Math.max(0.25f, meterStep - 0.25f);
            valueView.setText(String.format(Locale.US, "%.2f", meterStep));
        });
        view.findViewById(R.id.btn_count_plus).setOnClickListener(v -> {
            meterStep = Math.min(9.99f, meterStep + 0.25f);
            valueView.setText(String.format(Locale.US, "%.2f", meterStep));
        });

        metricButton.setSelected(useMetric);
        imperialButton.setSelected(!useMetric);
        valueView.setText(String.format(Locale.US, "%.2f", meterStep));
    }
}
