package com.nic.lgpower

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object LanguageManager {
    const val PREF_LANGUAGE = "app_language"
    const val LANG_SYSTEM = "system"
    const val LANG_AR = "ar"
    const val LANG_EN = "en"

    fun getLanguage(context: Context): String {
        return context.getSharedPreferences("webos", Context.MODE_PRIVATE)
            .getString(PREF_LANGUAGE, LANG_SYSTEM) ?: LANG_SYSTEM
    }

    fun setLanguage(context: Context, langCode: String) {
        context.getSharedPreferences("webos", Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LANGUAGE, langCode)
            .apply()
        applyLanguage(langCode)
    }

    fun applyLanguage(langCode: String) {
        if (langCode == LANG_SYSTEM) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(langCode))
        }
    }

    fun init(context: Context) {
        val saved = getLanguage(context)
        if (saved != LANG_SYSTEM) {
            applyLanguage(saved)
        }
    }

    fun getLanguageDisplayName(context: Context, langCode: String): String {
        return when (langCode) {
            LANG_AR -> context.getString(R.string.lang_arabic)
            LANG_EN -> context.getString(R.string.lang_english)
            else -> context.getString(R.string.lang_system_default)
        }
    }
}
