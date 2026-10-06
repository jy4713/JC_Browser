package com.example.streambrowser.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.streambrowser.R
import com.example.streambrowser.browser.AdBlocker
import com.example.streambrowser.browser.WebCleaner

/**
 * 호스트(사이트) 목록 관리 화면 — 범용
 * - 광고 허용 목록 / 오버레이 허용 목록 / 팝업 허용 목록 / JS 차단 사이트
 * extras: EXTRA_TITLE(문자열), EXTRA_PREF(SharedPreferences set 키), EXTRA_CURRENT(현재 사이트 호스트, 선택)
 */
class HostListActivity : Activity() {

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_PREF = "pref"
        const val EXTRA_CURRENT = "current"
    }

    private lateinit var prefKey: String
    private lateinit var listBox: LinearLayout

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.example.streambrowser.util.LocaleHelper.wrap(com.example.streambrowser.util.ThemeHelper.wrap(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.streambrowser.util.ThemeHelper.apply(this)
        prefKey = intent.getStringExtra(EXTRA_PREF) ?: "hosts"

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // 헤더
        val header = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(12), 0)
        }
        val btnBack = ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setBackgroundResource(R.drawable.bg_btn_ripple)
            contentDescription = getString(R.string.back)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(ContextCompat.getColor(this@HostListActivity, R.color.icon_tint))
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
            text = intent.getStringExtra(EXTRA_TITLE) ?: ""
            setTextColor(Color.parseColor("#202124"))
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        header.addView(btnBack)
        header.addView(title)
        root.addView(header)

        // 현재 사이트 추가 버튼
        val currentHost = intent.getStringExtra(EXTRA_CURRENT)
        if (!currentHost.isNullOrEmpty()) {
            val btnCur = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
                    setMargins(dp(12), dp(4), dp(12), dp(4))
                }
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_btn_soft)
                setTextColor(Color.parseColor("#1A73E8"))
                textSize = 14f
                text = getString(R.string.add_current_site_fmt, currentHost)
                setOnClickListener { addHost(currentHost) }
            }
            root.addView(btnCur)
        }

        // 직접 입력 행
        val inputRow = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                setMargins(dp(12), dp(4), dp(12), dp(4))
            }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            hint = "example.com"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val btnAdd = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply {
                marginStart = dp(8)
            }
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            setBackgroundResource(R.drawable.bg_btn_primary)
            setTextColor(Color.WHITE)
            textSize = 14f
            text = getString(R.string.action_add)
            setOnClickListener {
                val h = input.text.toString().trim().lowercase()
                    .removePrefix("http://").removePrefix("https://")
                    .substringBefore('/').substringBefore('?')
                if (h.isNotEmpty()) {
                    addHost(h)
                    input.setText("")
                }
            }
        }
        inputRow.addView(input)
        inputRow.addView(btnAdd)
        root.addView(inputRow)

        // 목록
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(listBox)
        }
        root.addView(scroll)

        setContentView(root)
        reload()
    }

    private fun hosts(): Set<String> = WebCleaner.hostsOf(prefKey)

    private fun save(hosts: Set<String>) {
        if (prefKey == "ad_allow_hosts") AdBlocker.setAllowHosts(hosts)
        else WebCleaner.setHosts(prefKey, hosts)
    }

    private fun addHost(host: String) {
        val cur = hosts().toMutableSet()
        cur.add(host.lowercase())
        save(cur)
        reload()
    }

    private fun removeHost(host: String) {
        val cur = hosts().toMutableSet()
        cur.remove(host)
        save(cur)
        reload()
    }

    private fun reload() {
        listBox.removeAllViews()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val list = hosts().sorted()
        if (list.isEmpty()) {
            val empty = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120))
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#9AA0A6"))
                textSize = 14f
                text = getString(R.string.list_empty)
            }
            listBox.addView(empty)
            return
        }
        for (h in list) {
            val row = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(8), 0)
            }
            val tv = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = h
                setTextColor(Color.parseColor("#202124"))
                textSize = 15f
            }
            val del = ImageButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                setBackgroundResource(R.drawable.bg_btn_ripple)
                contentDescription = getString(R.string.action_remove)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setImageResource(R.drawable.ic_close)
                setColorFilter(Color.parseColor("#5F6368"))
                setOnClickListener { removeHost(h) }
            }
            row.addView(tv)
            row.addView(del)
            listBox.addView(row)
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                    marginStart = dp(16)
                }
                setBackgroundColor(Color.parseColor("#F1F3F4"))
            }
            listBox.addView(divider)
        }
    }
}
