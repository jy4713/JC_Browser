package com.example.streambrowser.browser

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Brave 스타일 고급 광고/트래커 차단.
 * - hosts.txt: 차단할 도메인 (서브도메인 포함 매치)
 * - filters.txt: EasyList 계열 문법
 *     ||domain^      도메인 전체 차단 (서브도메인 포함)
 *     ||domain/path  도메인 + 경로
 *     문자열          URL 부분 문자열 차단 (기존 방식)
 *     @@규칙         예외 (화이트리스트) — 매치되면 차단 안 함
 *     ##선택자        요소 숨김 (cosmetic) — 페이지에 display:none CSS 주입
 *     $옵션           무시 (third-party 등 단순화)
 * - 사용자 커스텀 호스트는 SharedPreferences에 저장
 */
object AdBlocker {

    private val hosts = HashSet<String>()
    private val substrings = ArrayList<String>()       // 단순 문자열 필터
    private val domainRules = ArrayList<DomainRule>()  // ||도메인 규칙
    private val exceptions = ArrayList<Regex>()        // @@ 예외
    private val hideSelectors = ArrayList<String>()    // ## 요소 숨김

    private class DomainRule(val domain: String, val path: String?)

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
                lines.forEach { parseRule(it.trim()) }
            }
        }
        prefs?.getStringSet("custom_hosts", emptySet())?.forEach { hosts.add(it) }
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

        // 1) 호스트 차단
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
