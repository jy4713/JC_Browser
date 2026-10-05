package com.example.streambrowser.browser

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * 호스트 기반 + 간단 필터 문자열 기반 광고/트래커 차단.
 * - hosts.txt: 차단할 도메인 (서브도메인 포함 매치)
 * - filters.txt: URL에 포함되면 차단하는 문자열
 * - 사용자가 추가한 커스텀 호스트는 SharedPreferences에 저장
 */
object AdBlocker {

    private val hosts = HashSet<String>()
    private val filters = ArrayList<String>()

    @Volatile
    var enabled = true
        set(value) {
            field = value
            prefs?.edit()?.putBoolean("adblock", value)?.apply()
        }

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        enabled = prefs?.getBoolean("adblock", true) ?: true
        runCatching {
            context.assets.open("hosts.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val l = line.trim()
                    if (l.isNotEmpty() && !l.startsWith("#")) hosts.add(l.lowercase())
                }
            }
        }
        runCatching {
            context.assets.open("filters.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val l = line.trim()
                    if (l.isNotEmpty() && !l.startsWith("#") && !l.startsWith("[")) {
                        filters.add(l.lowercase())
                    }
                }
            }
        }
        prefs?.getStringSet("custom_hosts", emptySet())?.forEach { hosts.add(it) }
    }

    /** 현재 페이지 호스트를 커스텀 차단 목록에 추가 */
    fun addCustomHost(context: Context, host: String) {
        val h = host.lowercase()
        hosts.add(h)
        val cur = prefs?.getStringSet("custom_hosts", emptySet())?.toMutableSet() ?: mutableSetOf()
        cur.add(h)
        prefs?.edit()?.putStringSet("custom_hosts", cur)?.apply()
    }

    fun isBlocked(host: String, url: String): Boolean {
        if (!enabled) return false
        val h = host.lowercase()
        for (b in hosts) {
            if (h == b || h.endsWith("." + b)) return true
        }
        val u = url.lowercase()
        for (f in filters) {
            if (u.contains(f)) return true
        }
        return false
    }

    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
