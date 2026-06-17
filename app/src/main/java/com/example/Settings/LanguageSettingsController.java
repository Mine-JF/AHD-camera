package com.example.Settings;

import android.util.Log;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.example.ahd_camera.R;

import java.util.Locale;

// 语言页：负责按钮互斥选中状态。
public class LanguageSettingsController implements SettingsContentController {

    private static final String TAG = "LanguageSettings";
    private final AppCompatActivity activity;

    public LanguageSettingsController(AppCompatActivity activity) {
        this.activity = activity;
    }

    @Override
    public void bind(View view) {
        Log.d(TAG, "bind: start");
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

        syncSelection(view, buttonIds);

        // 点击任意语言时，只在目标语言发生变化时切换应用 locale。
        View.OnClickListener listener = clickedView -> {
            String languageTag = getLanguageTag(clickedView.getId());
            Log.d(TAG, "click: targetLanguage=" + languageTag);
            if (isCurrentLanguage(view, languageTag)) {
                Log.w(TAG, "click: language already active=" + languageTag);
                return;
            }

            try {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag));
                activity.overridePendingTransition(0, 0);
                Log.d(TAG, "click: language switched to=" + languageTag);
            } catch (RuntimeException e) {
                Log.e(TAG, "click: switch language failed", e);
            }
        };

        for (int id : buttonIds) {
            view.findViewById(id).setOnClickListener(listener);
        }
        Log.d(TAG, "bind: end");
    }

    private void syncSelection(View view, int[] buttonIds) {
        String currentLanguage = getCurrentLanguageTag(view);
        Log.d(TAG, "syncSelection: currentLanguage=" + currentLanguage);

        for (int id : buttonIds) {
            view.findViewById(id).setSelected(currentLanguage.equals(getLanguageTag(id).substring(0, 2)));
        }
    }

    private boolean isCurrentLanguage(View view, String languageTag) {
        return getCurrentLanguageTag(view).equals(languageTag.substring(0, 2));
    }

    private String getCurrentLanguageTag(View view) {
        LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
        if (!locales.isEmpty()) {
            Locale locale = locales.get(0);
            if (locale != null) {
                return locale.getLanguage();
            }
        }

        Log.w(TAG, "getCurrentLanguageTag: fallback to system locale");
        Locale currentLocale = view.getResources().getConfiguration().getLocales().get(0);
        return currentLocale.getLanguage();
    }

    private String getLanguageTag(int viewId) {
        if (viewId == R.id.btn_lang_en) {
            return "en";
        }
        if (viewId == R.id.btn_lang_ru) {
            return "ru";
        }
        if (viewId == R.id.btn_lang_ja) {
            return "ja";
        }
        if (viewId == R.id.btn_lang_de) {
            return "de";
        }
        if (viewId == R.id.btn_lang_fr) {
            return "fr";
        }
        if (viewId == R.id.btn_lang_ko) {
            return "ko";
        }
        if (viewId == R.id.btn_lang_es) {
            return "es";
        }
        if (viewId == R.id.btn_lang_it) {
            return "it";
        }
        if (viewId == R.id.btn_lang_pt) {
            return "pt";
        }
        Log.w(TAG, "getLanguageTag: unknown viewId=" + viewId + ", fallback=zh-CN");
        return "zh-CN";
    }
}
