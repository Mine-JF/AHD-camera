package com.example.Settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.ahd_camera.R;
import com.example.ahd_camera.Utils;

import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class Settings extends AppCompatActivity {

    private RecyclerView rvLeftMenu;
    private FrameLayout rightContentContainer;
    private final Calendar settingsCalendar = Calendar.getInstance();
    private boolean useMetric = true;
    private float meterStep = 1.00f;
    private int brightnessLevel = 8;
    private int volumeLevel = 8;

    private final List<String> menuList = Arrays.asList(
            "语言",
            "时间",
            "计米",
            "存储卡",
            "显示",
            "声音",
            "系统信息",
            "恢复出厂设置"
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initView();
        initMenu();
    }

    private void initView() {
        rvLeftMenu = findViewById(R.id.rv_left_menu);
        rightContentContainer = findViewById(R.id.right_content_container);
    }

    private void initMenu() {
        rvLeftMenu.setLayoutManager(new LinearLayoutManager(this));
        SettingMenuAdapter adapter = new SettingMenuAdapter(menuList, (position, name) -> showRightContent(position));
        rvLeftMenu.setAdapter(adapter);
        showRightContent(0);
    }

    private void showRightContent(int position) {
        rightContentContainer.removeAllViews();

        int layoutId;
        switch (position) {
            case 0:
                layoutId = R.layout.layout_language;
                break;
            case 1:
                layoutId = R.layout.layout_time;
                break;
            case 2:
                layoutId = R.layout.layout_date;
                break;
            case 3:
                layoutId = R.layout.layout_sdcard;
                break;
            case 4:
                layoutId = R.layout.layout_display;
                break;
            case 5:
                layoutId = R.layout.layout_sound;
                break;
            case 6:
                layoutId = R.layout.layout_system_info;
                break;
            case 7:
                layoutId = R.layout.layout_factory_reset;
                break;
            default:
                layoutId = R.layout.layout_language;
                break;
        }

        View view = LayoutInflater.from(this).inflate(layoutId, rightContentContainer, false);
        rightContentContainer.addView(view);
        bindRightContent(position, view);
    }

    private void bindRightContent(int position, View view) {
        switch (position) {
            case 0:
                bindLanguageContent(view);
                break;
            case 1:
                bindTimeContent(view);
                break;
            case 2:
                bindCountContent(view);
                break;
            case 4:
                bindDisplayContent(view);
                break;
            case 5:
                bindSoundContent(view);
                break;
            default:
                break;
        }
    }

    private void bindLanguageContent(View view) {
        int[] buttonIds = {
                R.id.btn_lang_zh_cn,
                R.id.btn_lang_en,
                R.id.btn_lang_ru,
                R.id.btn_lang_ja,
                R.id.btn_lang_de,
                R.id.btn_lang_fr,
                R.id.btn_lang_ko,
                R.id.btn_lang_es,
                R.id.btn_lang_it,
                R.id.btn_lang_pt
        };

        View.OnClickListener listener = clickedView -> {
            for (int id : buttonIds) {
                Button button = view.findViewById(id);
                button.setSelected(button == clickedView);
            }
        };

        for (int id : buttonIds) {
            view.findViewById(id).setOnClickListener(listener);
        }

        Button defaultButton = view.findViewById(R.id.btn_lang_zh_cn);
        defaultButton.setSelected(true);
    }

    private void bindTimeContent(View view) {
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
    }

    private void updateCalendar(View view, int field, int delta) {
        settingsCalendar.add(field, delta);
        refreshTimeValues(view);
    }

    private void refreshTimeValues(View view) {
        ((TextView) view.findViewById(R.id.tv_year_value)).setText(String.valueOf(settingsCalendar.get(Calendar.YEAR)));
        ((TextView) view.findViewById(R.id.tv_month_value)).setText(formatTwoDigits(settingsCalendar.get(Calendar.MONTH) + 1));
        ((TextView) view.findViewById(R.id.tv_day_value)).setText(formatTwoDigits(settingsCalendar.get(Calendar.DAY_OF_MONTH)));
        ((TextView) view.findViewById(R.id.tv_hour_value)).setText(formatTwoDigits(settingsCalendar.get(Calendar.HOUR_OF_DAY)));
        ((TextView) view.findViewById(R.id.tv_minute_value)).setText(formatTwoDigits(settingsCalendar.get(Calendar.MINUTE)));
        ((TextView) view.findViewById(R.id.tv_second_value)).setText(formatTwoDigits(settingsCalendar.get(Calendar.SECOND)));
    }

    private void bindCountContent(View view) {
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

    private void bindDisplayContent(View view) {
        TextView valueView = view.findViewById(R.id.tv_display_value);
        SeekBar seekBar = view.findViewById(R.id.seek_display);
        seekBar.setProgress(brightnessLevel);
        valueView.setText(String.valueOf(brightnessLevel));
        seekBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                brightnessLevel = progress;
                valueView.setText(String.valueOf(progress));
            }
        });
    }

    private void bindSoundContent(View view) {
        TextView valueView = view.findViewById(R.id.tv_sound_value);
        SeekBar seekBar = view.findViewById(R.id.seek_sound);
        seekBar.setProgress(volumeLevel);
        valueView.setText(String.valueOf(volumeLevel));
        seekBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                volumeLevel = progress;
                valueView.setText(String.valueOf(progress));
            }
        });
    }

    private String formatTwoDigits(int value) {
        return String.format(Locale.US, "%02d", value);
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Utils.hideSystemUI(getWindow());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            Utils.hideSystemUI(getWindow());
        }
    }
}
