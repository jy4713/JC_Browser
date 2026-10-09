package com.example.streambrowser.browser

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * WebView 요청을 가로채서
 * 1) 광고/트래커 차단 (AdBlocker)
 * 2) 스트리밍 미디어(m3u8/mpd/mp4 등) 감지 (VideoStore에 등록)
 * 3) HTML 응답에 영상 소스 스캐너 JS 주입 (iframe 플레이어 낶부까지 커버)
 * 4) http(s) 외 스킴(intent:// 등) 처리
 * 5) SSL 오류 시 사용자 확인 (기본 차단) / 렌더 프로세스 종료 시 복구
 */
class SniffingWebViewClient(
    private val onPageStartedCb: (WebView, String) -> Unit = { _, _ -> },
    private val onPageFinishedCb: (WebView, String) -> Unit = { _, _ -> },
    private val onRenderProcessGoneCb: (WebView) -> Unit = {}
) : WebViewClient() {

    /** 마지막 사용자 제스처 시각 — 클릭 직후 비동기 이동(전체화면 진입 등)을 팝업 오인 차단하지 않기 위함 */
    @Volatile
    private var lastGestureAt = 0L

    /** 호스트에서 베이스 도메인(등록 도메인) 추출 — 서브도메인 순환 사이트를 같은 사이트로 취급 */
    private fun baseDomain(host: String): String {
        val labels = host.lowercase().trimEnd('.').split('.')
        if (labels.size <= 2) return labels.joinToString(".")
        // 국가코드 하위 공용 접미사(co.kr, or.jp, com.au 등)는 3단계를 베이스로 봄
        val secondLevel = setOf(
            "com", "net", "org", "co", "or", "go", "ne", "re", "pe", "ac", "edu", "gov", "mil"
        )
        val tld = labels.last()
        return if (tld.length == 2 && labels[labels.size - 2] in secondLevel) {
            labels.takeLast(3).joinToString(".")
        } else {
            labels.takeLast(2).joinToString(".")
        }
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val url = request.url.toString()
        val host = request.url.host ?: ""
        // .torrent 메인 프레임 요청: 팝업/리다이렉트 경로(shouldOverrideUrlLoading이 안 불리는 경우)까지 커버
        if (request.isForMainFrame && request.method == "GET" &&
            request.url.path?.lowercase()?.endsWith(".torrent") == true
        ) {
            view.post { onTorrentLink?.invoke(view.context, url) }
            return AdBlocker.emptyResponse()
        }
        runCatching {
            if (AdBlocker.isBlocked(host, url)) {
                return AdBlocker.emptyResponse()
            }
            val accept = request.requestHeaders["Accept"] ?: ""
            detect(url, accept)?.let { kind ->
                VideoStore.add(view, DetectedVideo(url = url, page = view.url ?: "", kind = kind, headers = captureHeaders(request)))
            }
            // HTML 문서(메인 프레임 + iframe)면 스캐너 JS 주입
            // - fetch/XHR(API)는 Sec-Fetch-Dest로 구분해 제외 — 가로채면 API가 깨지거나(네이버 추천 피드 등)
            //   스트리밍/롱폴 엔드포인트면 읽기가 멈춰 페이지 로딩이 끝나지 않음
            if (request.method == "GET" && looksLikeDocument(request)) {
                injectScanner(view, request)?.let { return it }
            }
        }
        return null
    }

    /** 감지 시점 요청에서 다운로드에 필요한 핵심 헤더만 뽑아 "K: V" 줄 목록으로 저장.
     *  서버가 세션을 UA/Referer/쿠키에 묶는 경우 다운로드/복호화 키 요청이 이것을 그대로 재사용.
     *  Accept-Encoding/Range/Host 등은 제외 (gzip 수신/분할 다운로드와 충돌) */
    private fun captureHeaders(request: WebResourceRequest): String {
        val keep = setOf("user-agent", "referer", "origin", "cookie", "accept")
        return request.requestHeaders.entries
            .filter { it.key.lowercase() in keep && it.value.isNotBlank() }
            .joinToString("\r\n") { "${it.key}: ${it.value}" }
    }

    /** 문서(HTML) 요청 여부 — Sec-Fetch-Dest(모던 웹뷰, 크롬 80+)가 있으면 그것으로 판별.
     *  없는 구형 웹뷰는 Accept에 text/html이 명시된 경우만 (fetch와 XHR의 애스터리스크-슬래시 Accept는 제외 — API 깨짐 방지) */
    private fun looksLikeDocument(request: WebResourceRequest): Boolean {
        val dest = request.requestHeaders.entries
            .firstOrNull { it.key.equals("Sec-Fetch-Dest", ignoreCase = true) }
            ?.value?.lowercase()
        if (dest != null) return dest == "document" || dest == "iframe"
        val a = (request.requestHeaders["Accept"] ?: "").lowercase()
        return "text/html" in a
    }

    /** HTML 응답을 직접 받아 <head> 뒤에 스캐너 스크립트를 삽입 (iframe 낶의 video 태그도 수집) */
    private fun injectScanner(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val urlStr = request.url.toString()
        if (urlStr.startsWith("data:") || urlStr.startsWith("about:")) return null
        val host = request.url.host ?: ""
        // 보안 인증(Cloudflare Turnstile/hCaptcha/reCAPTCHA) 관련 페이지에는 주입하지 않음
        // (JS 훅이 챌린지를 감지해 체크박스가 나타나지 않는 문제 방지)
        if (isSecurityChallengeHost(host)) return null
        return runCatching {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.instanceFollowRedirects = true
            request.requestHeaders.forEach { (k, v) ->
                if (k.lowercase() !in setOf("accept-encoding", "connection", "content-length")) {
                    conn.setRequestProperty(k, v)
                }
            }
            runCatching {
                CookieManager.getInstance().getCookie(urlStr)?.let { conn.setRequestProperty("Cookie", it) }
            }
            // gzip 압축 명시 — 헤더 복사 단계에서 accept-encoding을 뺐으므로 여기서 직접 지정
            // (압축 없이 받으면 HTML 전송량이 3~5배 늘어 페이지 로딩이 느려짐)
            conn.setRequestProperty("Accept-Encoding", "gzip")
            if (conn.responseCode != 200) { conn.disconnect(); return null }
            val contentType = conn.contentType ?: ""
            if (!contentType.contains("text/html")) { conn.disconnect(); return null }

            val charset = Regex("charset=([A-Za-z0-9\\-]+)").find(contentType)?.groupValues?.get(1) ?: "utf-8"
            val bodyStream = if (conn.contentEncoding.equals("gzip", ignoreCase = true)) {
                java.util.zip.GZIPInputStream(conn.inputStream)
            } else conn.inputStream
            val data = bodyStream.use { it.readBytes() }

            // Set-Cookie은 응답 헤더 맵 하나로 못 넘기는데(여러 개면 쉼표로 합쳐져 Expires의 쉼표 때문에 쿠키 파싱이 깨짐),
            // 원본 값 각각을 CookieManager에 직접 저장한다. 그렇지 않으면 메인 문서 쿠키(네이버 NNB 등)가 깨져
            // 같은 URL의 API(추천 피드 등)가 계속 실패함
            val cookies = conn.headerFields.entries
                .firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }
                ?.value?.filterNotNull()
            val finalUrl = conn.url.toString()

            // 캐시 관련 헤더를 원본 응답에서 그대로 전달 — 재방문/뒤로가기 시 문서 캐시 히트로 빨라짐
            // 단 X-Frame-Options/CSP는 제외: iframe 문서를 우리가 재전송하면 프레임 임베딩이 막혀 플레이어가 깨짐
            // Set-Cookie도 제외: 위에서 CookieManager에 직접 저장함
            val hopByHop = setOf(
                "transfer-encoding", "content-encoding", "content-length", "connection",
                "x-frame-options", "content-security-policy", "content-security-policy-report-only",
                "set-cookie"
            )
            val respHeaders = mutableMapOf<String, String>()
            conn.headerFields.forEach { (k, v) ->
                if (k != null && k.lowercase() !in hopByHop && v != null) {
                    respHeaders[k] = v.filterNotNull().joinToString(", ")
                }
            }
            conn.disconnect()
            cookies?.forEach { c ->
                runCatching { CookieManager.getInstance().setCookie(finalUrl, c) }
            }

            val cs = runCatching { charset(charset) }.getOrElse { Charsets.UTF_8 }
            var html = String(data, cs)
            // 챌린지 페이지면 원본 그대로 둔다 (인증 스크립트가 주입을 감지하지 않게)
            if (isSecurityChallengePage(html)) return null
            if ("__sbScanner" !in html) {
                val script = "<script>window.__sbMinImg=${VideoJsBridge.imageMinWidth};${VideoJsBridge.SCANNER_JS}</script>"
                val m = Regex("(?i)<head[^>]*>").find(html)
                html = if (m != null) {
                    html.replaceRange(m.range.endInclusive + 1, m.range.endInclusive + 1, script)
                } else {
                    script + html
                }
            }
            WebResourceResponse("text/html", charset, ByteArrayInputStream(html.toByteArray(cs))).apply {
                if (respHeaders.isNotEmpty()) responseHeaders = respHeaders
            }
        }.getOrNull()
    }

    /** http/https 외 스킴은 여기서 처리. 예외가 나도 앱이 죽지 않게 true 리턴 */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        val scheme = request.url.scheme ?: ""
        // 토렌트: magnet 링크
        if (scheme == "magnet") {
            onTorrentLink?.invoke(view.context, url)
            return true
        }
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:")) {
            // 토렌트: .torrent 파일 (메인 프레임 클릭만)
            if (request.isForMainFrame && request.method == "GET" &&
                request.url.path?.lowercase()?.endsWith(".torrent") == true
            ) {
                onTorrentLink?.invoke(view.context, url)
                return true
            }
            // 팝업 차단: 제스처 없는 메인프레임 이동 중 사이트를 벗어나는 이동만 차단
            // (같은 사이트 내부 이동까지 막으면 Google 검색/JS 리다이렉트 등 정상 동작이 먹통이 됨)
            if (request.hasGesture()) lastGestureAt = SystemClock.elapsedRealtime()
            if (WebCleaner.popupEnabled && request.isForMainFrame &&
                !request.hasGesture() && !request.isRedirect
            ) {
                val host = request.url.host ?: ""
                val pageHost = runCatching { Uri.parse(view.url ?: "").host ?: "" }.getOrDefault("")
                // 같은 베이스 도메인(m02.x.com → m05.x.com 같은 서브도메인 순환 스트리밍 사이트)은
                // 전체화면 진입 등 JS 이동이 많아 팝업 오인 차단하면 사이트가 에러를 띄움
                val crossSite = host.isNotEmpty() && pageHost.isNotEmpty() &&
                        baseDomain(host) != baseDomain(pageHost)
                // 클릭 직후(수 초 이내) 비동기 이동은 사용자 유도 이동으로 간주해 허용 —
                // 전체화면 버튼 등이 fetch 후 location 이동하는 흐름이 여기 해당
                val recentGesture = SystemClock.elapsedRealtime() - lastGestureAt < 5000
                if (crossSite && !recentGesture && !WebCleaner.isPopupAllowed(pageHost)) {
                    com.example.streambrowser.util.JcToast.show(view.context, com.example.streambrowser.R.string.popup_blocked)
                    return true
                }
            }
            return false
        }
        // 앱 실행 차단 (intent://, market://, android-app:// 등)
        if (WebCleaner.appBlockEnabled && scheme in setOf("intent", "market", "android-app", "mailto", "tel")) {
            com.example.streambrowser.util.JcToast.show(view.context, com.example.streambrowser.R.string.app_blocked)
            return true
        }
        return openExternal(view, url)
    }

    @Deprecated("구형 API 대응")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        if (url.startsWith("magnet:")) {
            onTorrentLink?.invoke(view.context, url)
            return true
        }
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:")) {
            return false
        }
        return openExternal(view, url)
    }

    private fun openExternal(view: WebView, url: String): Boolean {
        val ctx = view.context
        return runCatching {
            when {
                url.startsWith("intent://") -> {
                    val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    intent.addCategory(Intent.CATEGORY_BROWSABLE)
                    intent.component = null
                    intent.selector = null
                    try {
                        ctx.startActivity(intent)
                    } catch (e: Exception) {
                        val fallback = intent.getStringExtra("browser_fallback_url")
                        if (!fallback.isNullOrEmpty()) view.loadUrl(fallback)
                    }
                    true
                }
                url.startsWith("javascript:") -> false
                else -> {
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                        // 처리 가능한 앱이 없으면 무시
                    }
                    true
                }
            }
        }.getOrElse { true }
    }

    /**
     * SSL 인증서 오류: 기본은 차단하고, 사용자가 명시적으로 확인한 경우에만 진행.
     * (구버전의 proceed()는 MITM 공격에 노출되므로 제거)
     */
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        val ctx = view.context
        if (ctx !is android.app.Activity || ctx.isFinishing || ctx.isDestroyed) {
            handler.cancel()
            return
        }
        android.app.AlertDialog.Builder(ctx)
            .setTitle(ctx.getString(com.example.streambrowser.R.string.ssl_error_title))
            .setMessage(ctx.getString(com.example.streambrowser.R.string.ssl_error_msg))
            .setPositiveButton(ctx.getString(com.example.streambrowser.R.string.ssl_error_continue)) { _, _ -> handler.proceed() }
            .setNegativeButton(ctx.getString(com.example.streambrowser.R.string.btn_cancel)) { _, _ -> handler.cancel() }
            .setOnCancelListener { handler.cancel() }
            .show()
    }

    /**
     * Chrome Safe Browsing 연동 (API 26+).
     * 피싱/악성코드 사이트 적발 시 안전한 페이지로 되돌림.
     */
    override fun onSafeBrowsingHit(
        view: WebView,
        request: WebResourceRequest,
        threatType: Int,
        callback: android.webkit.SafeBrowsingResponse
    ) {
        runCatching { callback.backToSafety(false) }
        com.example.streambrowser.util.JcToast.show(view.context, view.context.getString(com.example.streambrowser.R.string.blocked_warning), long = true)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onRenderProcessGoneCb(view)
        return true
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        onPageStartedCb(view, url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        onPageFinishedCb(view, url)
    }

    private fun detect(url: String, accept: String): String? {
        val u = url.lowercase()
        val pathOnly = u.substringBefore("?")
        val a = accept.lowercase()
        return when {
            ".m3u8" in u || "mpegurl" in a || "format=m3u8" in u || "protocol=hls" in u -> "HLS"
            ".mpd" in u || "dash" in a || "/manifest" in u -> "DASH"
            ".f4m" in u -> "HDS"
            ".mp4" in pathOnly || ".m4v" in pathOnly || "video/mp4" in a -> "MP4"
            ".webm" in pathOnly -> "WEBM"
            ".flv" in pathOnly -> "FLV"
            ".mov" in pathOnly -> "MP4"
            else -> null
        }
    }

    companion object {
        /** magnet / .torrent 링크 처리 콜백 — MainActivity가 설정 (토렌트 기능) */
        @Volatile var onTorrentLink: ((android.content.Context, String) -> Unit)? = null

        /** 보안 인증(봇 체크) 관련 호스트 — 스캐너 주입/차단 제외 대상 */
        private val CHALLENGE_HOST_PARTS = listOf("cloudflare", "hcaptcha", "recaptcha", "turnstile")

        fun isSecurityChallengeHost(host: String): Boolean {
            val h = host.lowercase()
            return CHALLENGE_HOST_PARTS.any { it in h }
        }

        /** HTML 내용이 보안 인증 챌린지 페이지인지 */
        fun isSecurityChallengePage(html: String): Boolean {
            val t = html.lowercase()
            return "challenges.cloudflare.com" in t || "__cf_chl" in t ||
                    "cdn-cgi/challenge" in t || "cf-turnstile" in t ||
                    "hcaptcha.com" in t || "recaptcha" in t
        }

        /** URL이 보안 인증 절차를 거치는 중인지 (페이지 로드 완료 후 주입 스킵용) */
        fun isSecurityChallengeUrl(url: String): Boolean {
            val u = url.lowercase()
            return "__cf_chl" in u || "cdn-cgi/challenge" in u ||
                    runCatching { isSecurityChallengeHost(Uri.parse(url).host ?: "") }.getOrDefault(false)
        }
    }
}
