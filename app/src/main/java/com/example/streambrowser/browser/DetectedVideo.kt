package com.example.streambrowser.browser

/** 감지된 스트리밍 영상 정보 */
data class DetectedVideo(
    val url: String,
    val page: String,
    val kind: String, // HLS / DASH / MP4 / WEBM / FLV
    val time: Long = System.currentTimeMillis()
)

/** 감지된 영상 목록 (앱 전역 싱글톤) */
object VideoStore {
    val videos = mutableListOf<DetectedVideo>()

    @Volatile
    var listener: (() -> Unit)? = null

    @Synchronized
    fun add(v: DetectedVideo) {
        if (videos.any { it.url == v.url }) return
        if (videos.size > 100) videos.removeAt(0)
        videos.add(v)
        listener?.invoke()
    }

    @Synchronized
    fun clear() {
        videos.clear()
        listener?.invoke()
    }
}
