package com.example.streambrowser.util

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import com.example.streambrowser.R

/**
 * 테마 설정 (다크 / 라이트 / 시스템)
 * - 앱 UI: 라이트/다크 테마를 [R.style.AppTheme] / [R.style.AppTheme_Dark] 중에서 선택
 * - WebView: prefers-color-scheme이 설정값을 따륵도록 uiMode 오버라이드 컨텍스트 제공
 *   (WebView는 테마가 아니라 configuration.uiMode로 다크 여부를 판단하므로,
 *    시스템이 라이트인데 다크로 설정하면 WebView에도 night uiMode를 심어줘야 한다)
 */
object ThemeHelper {
    const val MODE_SYSTEM = "system"
    const val MODE_DARK = "dark"
    const val MODE_LIGHT = "light"

    private const val PREF = "settings"
    private const val KEY_THEME = "theme_mode"

    fun mode(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_THEME, MODE_SYSTEM) ?: MODE_SYSTEM

    fun setMode(ctx: Context, mode: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_THEME, mode).apply()
    }

    fun isDark(ctx: Context): Boolean = when (mode(ctx)) {
        MODE_DARK -> true
        MODE_LIGHT -> false
        else -> isSystemDark(ctx)
    }

    private fun isSystemDark(ctx: Context): Boolean =
        ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

    /** 액티비티 생성 시 호출 (setContentView 전) */
    fun apply(activity: Activity) {
        activity.setTheme(if (isDark(activity)) R.style.AppTheme_Dark else R.style.AppTheme)
    }

    /**
     * attachBaseContext용 래퍼.
     * 설정된 테마에 맞춰 configuration.uiMode를 고정하면
     * - values-night 리소스(색상 등)가 설정값을 따름
     * - WebView의 prefers-color-scheme 미디어 쿼리도 시스템이 아니라 설정값을 따름
     *   (WebView는 테마가 아니라 uiMode로 다크 여부를 판단하기 때문)
     */
    fun wrap(context: Context): Context {
        val cfg = Configuration(context.resources.configuration)
        val mask = Configuration.UI_MODE_NIGHT_MASK.inv()
        cfg.uiMode = if (isDark(context))
            (cfg.uiMode and mask) or Configuration.UI_MODE_NIGHT_YES
        else
            (cfg.uiMode and mask) or Configuration.UI_MODE_NIGHT_NO
        return context.createConfigurationContext(cfg)
    }
}
