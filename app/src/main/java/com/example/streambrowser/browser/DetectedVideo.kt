package com.example.streambrowser.browser

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

/**
 * 감지된 미디어 저장소 (앱 전역 싱글톤)
 * - videos: 동영상 목록 / images: 이미지 목록 (분리)
 * - posterByUrl: 영상 URL -> 썸네일(poster) URL
 */
object VideoStore {
    val videos = mutableListOf<DetectedVideo>()
    val images = mutableListOf<DetectedVideo>()
    val posterByUrl = mutableMapOf<String, String>()

    @Volatile
    var listener: (() -> Unit)? = null

    @Synchronized
    fun add(v: DetectedVideo) {
        val target = if (v.kind == "IMG") images else videos
        if (target.any { it.url == v.url }) return
        if (target.size > 100) target.removeAt(0)
        target.add(v)
        listener?.invoke()
    }

    @Synchronized
    fun addPoster(videoUrl: String, poster: String) {
        if (videoUrl.isNotBlank() && poster.isNotBlank()) posterByUrl[videoUrl] = poster
    }

    @Synchronized
    fun clear() {
        videos.clear()
        images.clear()
        posterByUrl.clear()
        listener?.invoke()
    }
}
