package com.example.streambrowser.util

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** 언어 설정: 시스템 / 한국어 / 영어. 각 액티비티의 attachBaseContext에서 적용 */
object LocaleHelper {

    fun getLang(prefs: SharedPreferences): String = prefs.getString("lang", "system") ?: "system"

    fun wrap(context: Context): Context {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return wrap(context, getLang(prefs))
    }

    fun wrap(context: Context, lang: String): Context {
        if (lang == "system") return context
        val locale = when (lang) {
            "ko" -> Locale.KOREAN
            "en" -> Locale.ENGLISH
            else -> return context
        }
        Locale.setDefault(locale)
        val res: Resources = context.resources
        val config = Configuration(res.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        return context.createConfigurationContext(config)
    }
}
