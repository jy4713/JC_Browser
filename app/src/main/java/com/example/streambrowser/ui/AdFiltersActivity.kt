package com.example.streambrowser.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.streambrowser.R
import com.example.streambrowser.browser.AdBlocker
import kotlin.concurrent.thread

/**
 * 광고 필터 관리 화면 (Soul의 광고 필터 메뉴)
 * - 기본 제공 필터 규칙 수 표시
 * - URL 기반 필터 리스트: 추가(다운로드)/켜기·끄기/업데이트/삭제
 * - 사용자 단일 규칙: 추가/삭제
 */
class AdFiltersActivity : Activity() {

    private lateinit var txtBuiltin: TextView
    private lateinit var filterList: LinearLayout
    private lateinit var ruleList: LinearLayout

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.example.streambrowser.util.LocaleHelper.wrap(com.example.streambrowser.util.ThemeHelper.wrap(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.streambrowser.util.ThemeHelper.apply(this)
        setContentView(R.layout.activity_adfilters)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        txtBuiltin = findViewById(R.id.txtBuiltin)
        filterList = findViewById(R.id.filterList)
        ruleList = findViewById(R.id.ruleList)

        findViewById<Button>(R.id.btnAddFilter).setOnClickListener {
            val url = findViewById<EditText>(R.id.inputFilterUrl).text.toString().trim()
            if (!url.startsWith("http")) {
                com.example.streambrowser.util.JcToast.show(this, R.string.filter_url_invalid)
                return@setOnClickListener
            }
            it.isEnabled = false
            thread {
                val count = AdBlocker.addUrlFilter(url)
                runOnUiThread {
                    it.isEnabled = true
                    if (count != null) {
                        com.example.streambrowser.util.JcToast.show(this, getString(R.string.filter_added_fmt, count))
                        findViewById<EditText>(R.id.inputFilterUrl).setText("")
                        reload()
                    } else {
                        com.example.streambrowser.util.JcToast.show(this, R.string.filter_add_failed)
                    }
                }
            }
        }

        findViewById<Button>(R.id.btnAddRule).setOnClickListener {
            val rule = findViewById<EditText>(R.id.inputRule).text.toString().trim()
            if (rule.isNotEmpty()) {
                AdBlocker.addCustomRule(rule)
                findViewById<EditText>(R.id.inputRule).setText("")
                reload()
            }
        }

        reload()
    }

    private fun reload() {
        val (hosts, rules, custom) = AdBlocker.ruleCounts()
        txtBuiltin.text = getString(R.string.builtin_filters_fmt, hosts, rules, custom)

        // URL 필터 목록
        filterList.removeAllViews()
        val filters = AdBlocker.urlFilters()
        if (filters.isEmpty()) {
            val tv = TextView(this).apply {
                text = getString(R.string.list_empty)
                setTextColor(0xFF9AA0A6.toInt())
                textSize = 13f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 72)
            }
            filterList.addView(tv)
        }
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        for (f in filters) {
            val row = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val top = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
            }
            val name = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = f.name
                setTextColor(0xFF202124.toInt())
                textSize = 14f
            }
            val sw = Switch(this).apply {
                isChecked = f.enabled
                setOnCheckedChangeListener { _, on -> AdBlocker.setUrlFilterEnabled(f.url, on) }
            }
            top.addView(name)
            top.addView(sw)

            val sub = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
            }
            val info = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = getString(R.string.filter_rules_fmt, f.rules)
                setTextColor(0xFF5F6368.toInt())
                textSize = 12f
            }
            val btnUpdate = Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32))
                minimumWidth = 0
                setPadding(dp(10), 0, dp(10), 0)
                setBackgroundResource(R.drawable.bg_btn_soft)
                setText(R.string.action_update)
                setTextColor(0xFF1A73E8.toInt())
                textSize = 12f
                setOnClickListener {
                    it.isEnabled = false
                    (it as Button).setText(R.string.action_updating)
                    thread {
                        val ok = AdBlocker.updateUrlFilter(f.url)
                        runOnUiThread {
                            com.example.streambrowser.util.JcToast.show(this@AdFiltersActivity, if (ok) R.string.filter_updated else R.string.filter_add_failed)
                            reload()
                        }
                    }
                }
            }
            val btnDel = ImageButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(4) }
                setBackgroundResource(R.drawable.bg_btn_ripple)
                contentDescription = getString(R.string.action_remove)
                setPadding(dp(9), dp(9), dp(9), dp(9))
                setImageResource(R.drawable.ic_close)
                setColorFilter(0xFF5F6368.toInt())
                setOnClickListener {
                    AdBlocker.removeUrlFilter(f.url)
                    reload()
                }
            }
            sub.addView(info)
            sub.addView(btnUpdate)
            sub.addView(btnDel)

            row.addView(top)
            row.addView(sub)
            val divider = android.view.View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(0xFFF1F3F4.toInt())
            }
            filterList.addView(row)
            filterList.addView(divider)
        }

        // 사용자 규칙 목록
        ruleList.removeAllViews()
        val customRules = AdBlocker.customRuleList()
        if (customRules.isEmpty()) {
            val tv = TextView(this).apply {
                text = getString(R.string.list_empty)
                setTextColor(0xFF9AA0A6.toInt())
                textSize = 13f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 72)
            }
            ruleList.addView(tv)
        }
        for (r in customRules) {
            val row = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
            }
            val tv = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = r
                setTextColor(0xFF202124.toInt())
                textSize = 14f
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            val del = ImageButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                setBackgroundResource(R.drawable.bg_btn_ripple)
                contentDescription = getString(R.string.action_remove)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setImageResource(R.drawable.ic_close)
                setColorFilter(0xFF5F6368.toInt())
                setOnClickListener {
                    AdBlocker.removeCustomRule(r)
                    reload()
                }
            }
            row.addView(tv)
            row.addView(del)
            ruleList.addView(row)
        }
    }
}
