package com.example.streambrowser.download

import android.content.Context
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 이미지 직접 다운로더 (ffmpeg 불필요). 진행 상황은 DownloadStore를 통해 다운로드 화면에 표시 */
object ImageDownloader {

    fun download(ctx: Context, url: String, page: String, name: String, ext: String) {
        val id = System.currentTimeMillis()
        val item = DlItem(id, url, page, "IMG", name, ext).also { DownloadStore.upsert(it) }
        item.status = DlStatus.RUNNING
        DownloadStore.upsert(item)

        Thread {
            runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                )
                if (page.isNotEmpty()) conn.setRequestProperty("Referer", page)
                runCatching { CookieManager.getInstance().getCookie(url) }
                    .getOrNull()?.takeIf { it.isNotEmpty() }
                    ?.let { conn.setRequestProperty("Cookie", it) }

                if (conn.responseCode != 200) {
                    conn.disconnect()
                    throw IllegalStateException("HTTP ${conn.responseCode}")
                }

                val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "JC Browser").apply { mkdirs() }
                val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "image" }
                val safeExt = ext.replace(Regex("[^a-zA-Z0-9]"), "").ifEmpty { "jpg" }
                var f = File(dir, "$safe.$safeExt")
                var i = 1
                while (f.exists()) {
                    f = File(dir, "${safe}_$i.$safeExt")
                    i++
                }

                conn.inputStream.use { inp ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            item.doneBytes += n
                            DownloadStore.upsert(item)
                        }
                    }
                }
                conn.disconnect()
                item.file = f
                item.status = DlStatus.DONE
                // 공용 다운로드 폴더 모드
                val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
                if (sp.getString("dl_folder", "public") == "public") {
                    runCatching { copyToPublicDownloads(ctx, f) }
                }
            }.onFailure {
                item.status = DlStatus.FAILED
            }
            DownloadStore.upsert(item)
        }.start()
    }

    /** 완료 이미지를 공용 Download/JC Browser 폴더로 복사 (MediaStore, API 29+) */
    private fun copyToPublicDownloads(ctx: Context, src: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, src.name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "image/*")
            put(
                android.provider.MediaStore.Downloads.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_DOWNLOADS + "/JC Browser"
            )
        }
        val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        }
    }
}
