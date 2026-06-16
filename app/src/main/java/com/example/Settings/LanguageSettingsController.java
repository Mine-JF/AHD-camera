package com.example.Settings;

import android.view.View;
import android.widget.Button;

import com.example.ahd_camera.R;

// 语言页：负责按钮互斥选中状态。
public class LanguageSettingsController implements SettingsContentController {

    @Override
    public void bind(View view) {
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

        // 点击任意语言时，只保留当前按钮为选中状态。
        View.OnClickListener listener = clickedView -> {
            for (int id : buttonIds) {
                Button button = view.findViewById(id);
                button.setSelected(button == clickedView);
            }
        };

        for (int id : buttonIds) {
            view.findViewById(id).setOnClickListener(listener);
        }

        // 默认语言是简体中文。
        view.findViewById(R.id.btn_lang_zh_cn).setSelected(true);
    }
}
