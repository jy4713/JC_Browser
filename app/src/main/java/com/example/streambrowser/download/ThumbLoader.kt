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
}
