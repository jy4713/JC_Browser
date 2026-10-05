package com.example.streambrowser.browser

import android.webkit.WebView

/** 감지된 스트리밍 미디어 정보 */
data class DetectedVideo(
    val url: String,
    val page: String,
    val kind: String, // HLS / DASH / MP4 / WEBM / FLV / BLOB / IMG / ...
    val time: Long = System.currentTimeMillis()
) {
    /** 다운로드 불가 여부 (blob: 등) */
    val unavailable: Boolean get() = url.startsWith("blob:") || url.startsWith("data:")
}

/** 탭(WebView) 하나가 수집한 미디어 목록 */
class TabMedia {
    val videos = mutableListOf<DetectedVideo>()
    val images = mutableListOf<DetectedVideo>()
    val posterByUrl = mutableMapOf<String, String>()
}

/**
 * 감지된 미디어 저장소.
 * - 탭(WebView)별 독립 목록: 탭 전환/다른 탭 로드가 현재 탭 목록을 지우지 않음
 * - WeakHashMap이라 탭이 닫히면(GC) 목록도 함께 정리
 * - 페이지 이동(메인 프레임 onPageStarted / SPA 주소 변경) 시 해당 탭 목록만 리셋
 */
object VideoStore {
    private val byWebView = java.util.WeakHashMap<WebView, TabMedia>()
    private val fallback = TabMedia()

    @Volatile
    var listener: (() -> Unit)? = null

    @Synchronized
    fun mediaFor(wv: WebView?): TabMedia =
        if (wv == null) fallback else byWebView.getOrPut(wv) { TabMedia() }

    @Synchronized
    fun add(wv: WebView?, v: DetectedVideo) {
        val m = mediaFor(wv)
        val target = if (v.kind == "IMG") m.images else m.videos
        if (target.any { it.url == v.url }) return
        if (target.size > 100) target.removeAt(0)
        target.add(v)
        listener?.invoke()
    }

    @Synchronized
    fun addPoster(wv: WebView?, videoUrl: String, poster: String) {
        if (videoUrl.isNotBlank() && poster.isNotBlank()) mediaFor(wv).posterByUrl[videoUrl] = poster
    }

    @Synchronized
    fun clear(wv: WebView?) {
        val m = mediaFor(wv)
        m.videos.clear()
        m.images.clear()
        m.posterByUrl.clear()
        listener?.invoke()
    }
}
