package com.example.streambrowser.browser

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
                VideoStore.add(view, DetectedVideo(url = url, page = view.url ?: "", kind = kind))
            }
            // HTML 문서(메인 프레임 + iframe)면 스캐너 JS 주입
            // - accept에 text/html이 있거나, 확장자 없는 */* 문서 요청(iframe이 */*로 오는 경우 많음)도 시도
            //   (주입 여부는 응답 content-type이 text/html일 때만 확정 — 낶에서 2차 확인)
            if (request.method == "GET" && looksLikeDocument(accept, request.url)) {
                injectScanner(view, request)?.let { return it }
            }
        }
        return null
    }

    /** 문서(HTML) 요청 여부 — accept 헤더 + URL 확장자로 판별 */
    private fun looksLikeDocument(accept: String, uri: Uri): Boolean {
        val a = accept.lowercase()
        if ("text/html" in a) return true
        // */* 요청 중 확장자 없는 것(iframe 문서 등)만 시도 — 스크립트/이미지 등 정적 파일 제외
        if ("*/*" !in a) return false
        val path = uri.path ?: ""
        val ext = path.substringAfterLast('.', "")
        if (ext.length > 4) return false
        return ext !in setOf(
            "js", "css", "png", "jpg", "jpeg", "gif", "webp", "svg", "ico",
            "woff", "woff2", "ttf", "otf", "eot", "mp4", "webm", "mp3", "m3u8", "ts",
            "json", "xml", "txt", "pdf", "zip", "wasm"
        )
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
            if (conn.responseCode != 200) { conn.disconnect(); return null }
            val contentType = conn.contentType ?: ""
            if (!contentType.contains("text/html")) { conn.disconnect(); return null }

            val charset = Regex("charset=([A-Za-z0-9\\-]+)").find(contentType)?.groupValues?.get(1) ?: "utf-8"
            val data = conn.inputStream.use { it.readBytes() }
            conn.disconnect()

            val cs = runCatching { charset(charset) }.getOrElse { Charsets.UTF_8 }
            var html = String(data, cs)
            // 챌린지 페이지면 원본 그대로 둔다 (인증 스크립트가 주입을 감지하지 않게)
            if (isSecurityChallengePage(html)) return null
            if ("__sbScanner" !in html) {
                val script = "<script>${VideoJsBridge.SCANNER_JS}</script>"
                val m = Regex("(?i)<head[^>]*>").find(html)
                html = if (m != null) {
                    html.replaceRange(m.range.endInclusive + 1, m.range.endInclusive + 1, script)
                } else {
                    script + html
                }
            }
            WebResourceResponse("text/html", charset, ByteArrayInputStream(html.toByteArray(cs)))
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
            // 팝업 차단: 제스처 없는 메인프레임 이동 중 서버 리다이렉트가 아닌 것
            if (WebCleaner.popupEnabled && request.isForMainFrame &&
                !request.hasGesture() && !request.isRedirect
            ) {
                val host = request.url.host ?: ""
                val pageHost = runCatching { Uri.parse(view.url ?: "").host ?: "" }.getOrDefault("")
                val blocked = if (WebCleaner.popupBlockAll) {
                    true
                } else {
                    // 광고 의심 모드: 다른 사이트로 가는 것만
                    host.isNotEmpty() && pageHost.isNotEmpty() &&
                            host != pageHost && !host.endsWith("." + pageHost) && !pageHost.endsWith("." + host)
                }
                if (blocked && !WebCleaner.isPopupAllowed(pageHost)) {
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
