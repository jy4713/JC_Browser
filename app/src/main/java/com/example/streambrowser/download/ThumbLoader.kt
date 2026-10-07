package com.example.streambrowser.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** 원격 썸네일 비동기 로더 (메모리 캐시). 이미지/영상 poster 로딩에 사용 */
object ThumbLoader {
    private val cache = LruCache<String, Bitmap>(40)
    private val exec = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String, iv: ImageView, fallback: Bitmap? = null) {
        val cached = cache.get(url)
        if (cached != null) {
            iv.setImageBitmap(cached)
            return
        }
        if (fallback != null) iv.setImageBitmap(fallback)
        iv.tag = url
        exec.execute {
            val bmp = runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                )
                if (conn.responseCode != 200) { conn.disconnect(); return@runCatching null }
                val data = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                BitmapFactory.decodeByteArray(data, 0, data.size)
            }.getOrNull()
            if (bmp != null) cache.put(url, bmp)
            main.post {
                if (iv.tag == url) iv.setImageBitmap(bmp ?: fallback)
            }
        }
    }

    /** 현재 페이지 스냅샷 (영상 썸네일 대체용). MainActivity가 갱신 */
    object PageSnapshot {
        @Volatile
        var pageUrl: String = ""

        @Volatile
        var bitmap: Bitmap? = null
    }

    /* ---------- 종류별 색상 타일 (스냅샷/포스터 없을 때 검은 네모 대신 표시) ---------- */

    private val tileCache = LruCache<String, Bitmap>(16)

    fun kindTile(kind: String): Bitmap {
        tileCache.get(kind)?.let { return it }
        val w = 160
        val h = 90
        val colors = mapOf(
            "HLS" to "#0B8043", "DASH" to "#1A73E8", "MP4" to "#E8710A",
            "WEBM" to "#9334E6", "FLV" to "#D93025", "IMG" to "#5F6368"
        )
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.parseColor(colors[kind] ?: "#3C4043"))
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 30f
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }
        val label = if (kind == "IMG") "IMG" else kind
        val y = h / 2f - (p.descent() + p.ascent()) / 2f
        c.drawText(label, w / 2f, y, p)
        tileCache.put(kind, bmp)
        return bmp
    }

    /* ---------- 원격 영상 직접 프레임 캡처 (썸네일) ---------- */

    private val frameCache = LruCache<String, Bitmap>(24)
    private val frameInFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 직접 파일 URL(MP4/WEBM 등)이면 원격에서 프레임을 떠서 썸네일로 사용 가능 */
    fun canFrameCapture(url: String, kind: String): Boolean {
        if (!url.startsWith("http")) return false
        if (kind == "HLS" || kind == "DASH" || kind == "BLOB") return false
        return kind in setOf("MP4", "WEBM", "FLV", "MEDIA") ||
                Regex("\\.(mp4|webm|mov|m4v|flv)(\\?|#|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)
    }

    /** MediaMetadataRetriever로 1초 지점 프레임 추출 (백그라운드, 메모리 캐시) */
    fun loadFrame(url: String, iv: ImageView, fallback: Bitmap?) {
        frameCache.get(url)?.let { iv.setImageBitmap(it); return }
        iv.setImageBitmap(fallback)
        iv.tag = "frame:$url"
        if (!frameInFlight.add(url)) return
        exec.execute {
            val bmp = runCatching {
                val r = android.media.MediaMetadataRetriever()
                r.setDataSource(url, mapOf("User-Agent" to
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"))
                val f = r.getFrameAtTime(1_000_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: r.getFrameAtTime(0)
                r.release()
                f
            }.getOrNull()
            if (bmp != null) frameCache.put(url, bmp)
            frameInFlight.remove(url)
            main.post {
                if (iv.tag == "frame:$url") iv.setImageBitmap(bmp ?: fallback)
            }
        }
    }
}
