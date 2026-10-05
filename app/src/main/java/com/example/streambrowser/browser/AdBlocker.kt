package com.example.streambrowser.browser

import android.content.Context
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Brave/Soul 스타일 고급 광고/트래커 차단.
 * - hosts.txt: 차단할 도메인 (서브도메인 포함 매치)
 * - filters.txt: EasyList 계열 문법
 *     ||domain^      도메인 전체 차단 (서브도메인 포함)
 *     ||domain/path  도메인 + 경로
 *     문자열          URL 부분 문자열 차단 (기존 방식)
 *     @@규칙         예외 (화이트리스트) — 매치되면 차단 안 함
 *     ##선택자        요소 숨김 (cosmetic) — 페이지에 display:none CSS 주입
 *     $옵션           무시 (third-party 등 단순화)
 * - 사용자 커스텀 호스트는 SharedPreferences에 저장
 * - URL 기반 필터 리스트: AdFilters 화면에서 추가/관리, 파일로 저장 후 재파싱
 */
object AdBlocker {

    private val hosts = HashSet<String>()
    private val allowHosts = HashSet<String>()         // per-site ad allowlist
    private val substrings = ArrayList<String>()       // 단순 문자열 필터
    private val domainRules = ArrayList<DomainRule>()  // ||도메인 규칙
    private val exceptions = ArrayList<Regex>()        // @@ 예외
    private val hideSelectors = ArrayList<String>()    // ## 요소 숨김

    /** 사용자가 추가한 단일 규칙 원문 (화면 표시용) */
    private val customRules = LinkedHashSet<String>()

    private class DomainRule(val domain: String, val path: String?)

    class UrlFilter(val name: String, val url: String, var enabled: Boolean, var rules: Int)

    @Volatile
    var enabled = true
        set(value) {
            field = value
            prefs?.edit()?.putBoolean("adblock", value)?.apply()
        }

    private var prefs: android.content.SharedPreferences? = null
    private var appCtx: Context? = null

    fun init(context: Context) {
        appCtx = context.applicationContext
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        enabled = prefs?.getBoolean("adblock", true) ?: true
        reload()
    }

    /** 자산 + 커스텀 규칙 + 활성화된 URL 필터 전부 다시 로드 */
    fun reload() {
        val ctx = appCtx ?: return
        hosts.clear(); substrings.clear(); domainRules.clear()
        exceptions.clear(); hideSelectors.clear(); customRules.clear()

        runCatching {
            ctx.assets.open("hosts.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val l = line.trim()
                    if (l.isNotEmpty() && !l.startsWith("#")) hosts.add(l.lowercase())
                }
            }
        }
        runCatching {
            ctx.assets.open("filters.txt").bufferedReader().useLines { lines ->
                lines.forEach { parseRule(it.trim()) }
            }
        }
        prefs?.getStringSet("custom_hosts", emptySet())?.forEach { hosts.add(it.lowercase()) }
        prefs?.getStringSet("ad_allow_hosts", emptySet())?.forEach { allowHosts.add(it.lowercase()) }
        // 사용자 단일 규칙
        prefs?.getStringSet("custom_rules", emptySet())?.forEach { line ->
            customRules.add(line)
            parseRule(line)
        }
        // 활성화된 URL 필터 파일
        urlFilters().filter { it.enabled }.forEach { f ->
            filterFile(f.url)?.let { file ->
                if (file.exists()) {
                    runCatching {
                        file.bufferedReader().useLines { lines -> lines.forEach { parseRule(it.trim()) } }
                    }
                }
            }
        }
    }

    // ---------------- URL 필터 관리 ----------------

    private fun urlFiltersJson(): JSONArray =
        runCatching { JSONArray(prefs?.getString("url_filters", "[]") ?: "[]") }.getOrElse { JSONArray() }

    fun urlFilters(): List<UrlFilter> {
        val arr = urlFiltersJson()
        val out = mutableListOf<UrlFilter>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += UrlFilter(
                o.optString("name", o.optString("url")),
                o.optString("url"),
                o.optBoolean("enabled", true),
                o.optInt("rules", 0)
            )
        }
        return out
    }

    private fun saveUrlFilters(list: List<UrlFilter>) {
        val arr = JSONArray()
        list.forEach { f ->
            arr.put(JSONObject().apply {
                put("name", f.name); put("url", f.url)
                put("enabled", f.enabled); put("rules", f.rules)
            })
        }
        prefs?.edit()?.putString("url_filters", arr.toString())?.apply()
    }

    private fun filterFile(url: String): File? {
        val ctx = appCtx ?: return null
        val dir = File(ctx.filesDir, "filters").apply { mkdirs() }
        val name = MessageDigest.getInstance("MD5").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$name.txt")
    }

    /** URL에서 필터 다운로드 + 등록. 규칙 수 리턴 (실패 시 null) */
    fun addUrlFilter(url: String, name: String? = null): Int? {
        val ctx = appCtx ?: return null
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.instanceFollowRedirects = true
            if (conn.responseCode != 200) { conn.disconnect(); return null }
            val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()

            val count = text.lines().count { it.trim().isNotEmpty() && !it.trim().startsWith("!") && !it.trim().startsWith("#") }
            val file = filterFile(url) ?: return null
            file.writeText(text)

            val displayName = name?.takeIf { it.isNotBlank() }
                ?: url.substringAfterLast('/').ifBlank { url }
            val list = urlFilters().toMutableList()
            list.removeAll { it.url == url }
            list += UrlFilter(displayName, url, true, count)
            saveUrlFilters(list)
            reload()
            count
        }.getOrNull()
    }

    fun removeUrlFilter(url: String) {
        filterFile(url)?.delete()
        saveUrlFilters(urlFilters().filterNot { it.url == url })
        reload()
    }

    fun setUrlFilterEnabled(url: String, enabled: Boolean) {
        val list = urlFilters().map { if (it.url == url) UrlFilter(it.name, it.url, enabled, it.rules) else it }
        saveUrlFilters(list)
        reload()
    }

    /** 기존 필터 재다운로드 (규칙 수 갱신) */
    fun updateUrlFilter(url: String): Boolean {
        val old = urlFilters().firstOrNull { it.url == url } ?: return false
        return addUrlFilter(url, old.name) != null
    }

    // ---------------- 사용자 단일 규칙 ----------------

    fun customRuleList(): List<String> = customRules.toList()

    fun addCustomRule(line: String) {
        val l = line.trim()
        if (l.isEmpty()) return
        val cur = prefs?.getStringSet("custom_rules", emptySet())?.toMutableSet() ?: mutableSetOf()
        cur.add(l)
        prefs?.edit()?.putStringSet("custom_rules", cur)?.apply()
        reload()
    }

    fun removeCustomRule(line: String) {
        val cur = prefs?.getStringSet("custom_rules", emptySet())?.toMutableSet() ?: mutableSetOf()
        cur.remove(line)
        prefs?.edit()?.putStringSet("custom_rules", cur)?.apply()
        reload()
    }

    /** 기본 제공 + 커스텀 규칙 수 (화면 표시용) */
    fun ruleCounts(): Triple<Int, Int, Int> =
        Triple(hosts.size, substrings.size + domainRules.size + hideSelectors.size, customRules.size)

    // ---------------- 차단 로직 ----------------

    /** 이 사이트(호스트)의 광고를 차단에서 예외 처리했는지 */
    fun isHostAllowed(host: String): Boolean {
        val h = host.lowercase()
        return allowHosts.any { h == it || h.endsWith("." + it) }
    }

    fun adAllowHosts(): Set<String> = allowHosts

    /** 사이트별 광고 허용 토글 (전역 차단은 그대로 두고 예외만 관리) */
    fun toggleAllowHost(context: Context, host: String) {
        val h = host.lowercase()
        if (!allowHosts.add(h)) allowHosts.remove(h)
        val cur = prefs?.getStringSet("ad_allow_hosts", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (!cur.add(h)) cur.remove(h)
        prefs?.edit()?.putStringSet("ad_allow_hosts", cur)?.apply()
    }

    fun setAllowHosts(hosts: Set<String>) {
        allowHosts.clear()
        allowHosts.addAll(hosts.map { it.lowercase() })
        prefs?.edit()?.putStringSet("ad_allow_hosts", allowHosts)?.apply()
    }

    /** 규칙 한 줄 파싱 (EasyList 문법 단순화) */
    private fun parseRule(line: String) {
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) return
        if (line.startsWith("!")) return

        // 요소 숨김: ##선택자 (예: ##.adsbygoogle)
        val cosIdx = line.indexOf("##")
        if (cosIdx >= 0) {
            val sel = line.substring(cosIdx + 2).trim()
            if (sel.isNotEmpty() && sel.length < 200) hideSelectors.add(sel)
            return
        }

        var r = line
        var isException = false
        if (r.startsWith("@@")) {
            isException = true
            r = r.removePrefix("@@")
        }

        // $옵션 제거 (domain= / third-party 등 단순 무시)
        r = r.substringBefore("$").trim()
        if (r.isEmpty()) return

        if (r.startsWith("||")) {
            // 도메인 규칙: ||도메인^ 또는 ||도메인/경로
            var d = r.removePrefix("||")
            var path: String? = null
            val sep = d.indexOfFirst { it == '/' || it == '^' || it == ':' }
            if (sep >= 0) {
                if (d[sep] == '/') path = d.substring(sep)
                d = d.substring(0, sep)
            }
            d = d.removeSuffix("^").lowercase()
            if (d.isEmpty()) return
            val rule = DomainRule(d, path?.lowercase())
            if (isException) exceptions.add(domainExceptionRegex(d)) else domainRules.add(rule)
        } else if (r.startsWith("|http") || r.startsWith("|https")) {
            // |http://... 고정 접두 규칙 → 접두 매치
            val prefix = r.removePrefix("|")
            if (isException) exceptions.add(Regex("^" + Regex.escape(prefix.lowercase())))
            else substrings.add(prefix.lowercase())
        } else if (r.contains("*")) {
            // 와일드카드 → 정규식 (실패 시 무시)
            runCatching {
                val re = Regex(
                    r.lowercase()
                        .replace(".", "\\.")
                        .replace("?", "\\?")
                        .replace("*", ".*")
                )
                if (isException) exceptions.add(re) else substrings.add(r.lowercase())
            }
        } else {
            if (isException) exceptions.add(Regex(Regex.escape(r.lowercase())))
            else substrings.add(r.lowercase())
        }
    }

    private fun domainExceptionRegex(domain: String): Regex =
        Regex("(^|\\.)" + Regex.escape(domain) + "(/|:|$|\\?)")

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
        val u = url.lowercase()

        // 0) 예외 규칙이 먼저 매치되면 무조건 통과
        for (e in exceptions) {
            if (e.containsMatchIn(u)) return false
        }

        // 1) 사이트별 허용 목록이면 통과
        if (isHostAllowed(host)) return false

        // 2) 호스트 차단
        val h = host.lowercase()
        for (b in hosts) {
            if (h == b || h.endsWith("." + b)) return true
        }

        // 2) ||도메인 규칙
        for (r in domainRules) {
            if (h == r.domain || h.endsWith("." + r.domain)) {
                if (r.path == null || u.contains(r.path)) return true
            }
        }

        // 3) 문자열/와일드카드 필터
        for (f in substrings) {
            if (u.contains(f)) return true
        }
        return false
    }

    /** 요소 숨김용 CSS (##규칙). 페이지 로드 후 주입 */
    fun hideCss(): String {
        if (!enabled || hideSelectors.isEmpty()) return ""
        return hideSelectors.joinToString(",") + "{display:none!important}"
    }

    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
