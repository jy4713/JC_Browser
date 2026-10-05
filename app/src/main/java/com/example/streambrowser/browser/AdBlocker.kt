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

    /** 기본 제공 필터 세트 (최초 실행 시 자동 다운로드). 모바일/한국 사이트/추적기 중심 */
    val DEFAULT_FILTERS = listOf(
        "AdGuard Mobile Ads" to "https://filters.adtidy.org/extension/chromium/filters/11.txt",
        "AdGuard Tracking Protection" to "https://filters.adtidy.org/extension/chromium/filters/3.txt",
        "List-KR (Korean sites)" to "https://raw.githubusercontent.com/List-KR/List-KR/master/filter.txt"
    )
    private const val PREFS_DEFAULTS_DONE = "default_filters_added"

    private val hosts = HashSet<String>()
    private val allowHosts = HashSet<String>()         // per-site ad allowlist
    private val substrings = ArrayList<String>()       // 단순 문자열 필터
    private val domainRuleMap = HashMap<String, MutableList<DomainRule>>()  // key: 도메인 (상위 도메인 suffix 조회)
    private val exceptionDomains = HashSet<String>()   // @@||도메인^ 예외
    private val exceptionRegexes = ArrayList<Regex>()  // 기타 @@ 예외
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
        ensureDefaultFilters()
    }

    /** 기본 필터 세트 등록(최초 1회) + 파일 없는 활성 필터 백그라운드 다운로드 */
    private fun ensureDefaultFilters() {
        if (prefs?.getBoolean(PREFS_DEFAULTS_DONE, false) != true) {
            val list = urlFilters().toMutableList()
            DEFAULT_FILTERS.forEach { (name, url) ->
                if (list.none { it.url == url }) list += UrlFilter(name, url, true, 0)
            }
            saveUrlFilters(list)
            prefs?.edit()?.putBoolean(PREFS_DEFAULTS_DONE, true)?.apply()
        }
        kotlin.concurrent.thread {
            urlFilters().filter { it.enabled }.forEach { f ->
                val file = filterFile(f.url)
                if (file == null || !file.exists()) addUrlFilter(f.url, f.name)
            }
        }
    }

    /** 자산 + 커스텀 규칙 + 활성화된 URL 필터 전부 다시 로드 */
    @Synchronized
    fun reload() {
        val ctx = appCtx ?: return
        hosts.clear(); substrings.clear(); domainRuleMap.clear()
        exceptionDomains.clear(); exceptionRegexes.clear(); hideSelectors.clear(); customRules.clear()

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
    fun ruleCounts(): Triple<Int, Int, Int> {
        val domainRules = domainRuleMap.values.sumOf { it.size }
        return Triple(hosts.size, substrings.size + domainRules + hideSelectors.size, customRules.size)
    }

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
        // 지원 안 하는 고급 화장 규칙은 건드리지 않음 (오작동 방지)
        if ("#@#" in line || "#?#" in line || ":-abp-" in line) return

        // 요소 숨김: ##선택자 — :has()/:contains() 등 복합 선택자는 제외
        val cosIdx = line.indexOf("##")
        if (cosIdx >= 0) {
            val sel = line.substring(cosIdx + 2).trim()
            if (sel.isNotEmpty() && sel.length < 200 &&
                !sel.contains(":has(") && !sel.contains(":contains") && !sel.contains("[-abp-")
            ) hideSelectors.add(sel)
            return
        }

        var r = line
        var isException = false
        if (r.startsWith("@@")) {
            isException = true
            r = r.removePrefix("@@")
        }

        // $옵션: 페이지 한정(domain=)이나 복합 옵션은 규칙 자체를 스킵 (전역 적용 시 오작동 방지)
        val optIdx = r.indexOf('$')
        if (optIdx >= 0) {
            val opts = r.substring(optIdx + 1).lowercase()
            r = r.substring(0, optIdx).trim()
            val skip = opts.split(',').any {
                it.startsWith("domain=") || it.startsWith("sitekey") ||
                        "generichide" in it || "genericblock" in it || "elemhide" in it
            }
            if (skip || r.isEmpty()) return
        }
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
            if (isException) exceptionDomains.add(d)
            else domainRuleMap.getOrPut(d) { mutableListOf() }.add(DomainRule(d, path?.lowercase()))
        } else if (r.startsWith("|http") || r.startsWith("|https")) {
            // |http://... 고정 접두 규칙 → 접두 매치
            val prefix = r.removePrefix("|")
            if (isException) exceptionRegexes.add(Regex("^" + Regex.escape(prefix.lowercase())))
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
                if (isException) exceptionRegexes.add(re) else substrings.add(r.lowercase())
            }
        } else {
            if (isException) exceptionRegexes.add(Regex(Regex.escape(r.lowercase())))
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

    @Synchronized
    fun isBlocked(host: String, url: String): Boolean {
        if (!enabled) return false
        val u = url.lowercase()
        val h = host.lowercase()

        // 0) 예외 규칙: @@||도메인^ (상위 도메인 suffix 조회) + 기타 @@ 정규식
        if (exceptionDomains.isNotEmpty()) {
            var cur = h
            while (true) {
                if (cur in exceptionDomains) return false
                val dot = cur.indexOf('.')
                if (dot < 0) break
                cur = cur.substring(dot + 1)
            }
        }
        for (e in exceptionRegexes) {
            if (e.containsMatchIn(u)) return false
        }

        // 1) 사이트별 허용 목록이면 통과
        if (isHostAllowed(host)) return false

        // 2) 호스트 차단 (상위 도메인 suffix 조회)
        if (hosts.isNotEmpty()) {
            var cur = h
            while (true) {
                if (cur in hosts) return true
                val dot = cur.indexOf('.')
                if (dot < 0) break
                cur = cur.substring(dot + 1)
            }
        }

        // 3) ||도메인 규칙 (도메인 키 맵 + suffix 조회)
        if (domainRuleMap.isNotEmpty()) {
            var cur = h
            while (true) {
                val rules = domainRuleMap[cur]
                if (rules != null) {
                    for (r in rules) {
                        if (r.path == null || u.contains(r.path)) return true
                    }
                }
                val dot = cur.indexOf('.')
                if (dot < 0) break
                cur = cur.substring(dot + 1)
            }
        }

        // 4) 문자열/와일드카드 필터
        for (f in substrings) {
            if (u.contains(f)) return true
        }
        return false
    }

    /** 요소 숨김용 CSS (##규칙). 페이지 로드 후 주입 */
    @Synchronized
    fun hideCss(): String {
        if (!enabled || hideSelectors.isEmpty()) return ""
        // 지나치게 크면 페이지 로딩이 느려지므로 상한 적용
        return hideSelectors.take(5000).joinToString(",") + "{display:none!important}"
    }

    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
