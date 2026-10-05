package com.example.streambrowser.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.Session
import com.example.streambrowser.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * 스트림 다운로드 서비스.
 * - 진행률: HLS는 재생목록에서 총 재생 시간을 계산해 % 표시, 수신 바이트로 크기 표시
 * - 취소: 실행 중 세션 취소 + 부분 파일 삭제
 */
class VideoDownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "video_download"
        const val EXTRA_ID = "id"
        const val EXTRA_URL = "url"
        const val EXTRA_PAGE = "page"
        const val EXTRA_KIND = "kind"
        const val EXTRA_NAME = "name"
        const val EXTRA_EXT = "ext"

        val sessions = ConcurrentHashMap<Long, Session>()

        fun cancel(id: Long) {
            sessions[id]?.cancel()
        }
    }

    private val notifBase = 2000

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_ID, System.currentTimeMillis()) ?: System.currentTimeMillis()
        val url = intent?.getStringExtra(EXTRA_URL) ?: run { stopSelf(); return START_NOT_STICKY }
        val page = intent.getStringExtra(EXTRA_PAGE) ?: ""
        val kind = intent.getStringExtra(EXTRA_KIND) ?: ""
        val name = intent.getStringExtra(EXTRA_NAME) ?: ("video_" + System.currentTimeMillis())
        val ext = intent.getStringExtra(EXTRA_EXT)?.trim()?.removePrefix(".")?.ifEmpty { "mp4" } ?: "mp4"

        val item = DownloadStore.get(id) ?: DlItem(id, url, page, kind, name, ext).also { DownloadStore.upsert(it) }

        // 설정: 알림 표시 여부 / 다운로드 위치 (공용 Download 폴터면 완료 후 복사)
        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        val notifyOn = sp.getBoolean("dl_notify", true)
        val folderPublic = sp.getString("dl_folder", "public") == "public"

        createChannel()
        startForeground(notifBase + (id % 500).toInt(), buildNotification(item.name, getString(com.example.streambrowser.R.string.notif_preparing), indeterminate = true))

        Thread {
            runCatching {
                val headers = buildHeaders(page)
                if (item.kind == "HLS") {
                    item.totalDurationMs = measureHlsDuration(item.url, headers)
                    DownloadStore.upsert(item)
                }
                val out = uniqueFile(item.name, item.ext)
                item.file = out
                item.status = DlStatus.RUNNING
                DownloadStore.upsert(item)

                val cmd = buildString {
                    append("-headers \"${headers}\" ")
                    append("-y -i \"${item.url}\" ")
                    if (item.kind == "HLS") append("-c copy -bsf:a aac_adtstoasc ")
                    else append("-c copy ")
                    append("\"" + out.absolutePath + "\"")
                }

                val session = FFmpegKit.executeAsync(
                    cmd,
                    { s ->
                        sessions.remove(id)
                        val code = s.returnCode
                        item.status = when {
                            code != null && code.isValueSuccess -> DlStatus.DONE
                            code != null && code.isValueCancel -> DlStatus.CANCELED
                            else -> DlStatus.FAILED
                        }
                        if (item.status == DlStatus.CANCELED) out.delete()
                        // 공용 다운로드 폴터 모드: 완료 파일을 Download/JC Browser로 복사
                        if (item.status == DlStatus.DONE && folderPublic) {
                            runCatching { copyToPublicDownloads(out) }
                        }
                        DownloadStore.upsert(item)
                        if (notifyOn) {
                            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                            val msg = when (item.status) {
                                DlStatus.DONE -> "${getString(com.example.streambrowser.R.string.notif_done)}: ${out.name}"
                                DlStatus.CANCELED -> "${getString(com.example.streambrowser.R.string.notif_canceled)}: ${out.name}"
                                else -> "${getString(com.example.streambrowser.R.string.notif_failed)}: ${out.name}"
                            }
                            nm.notify(notifBase + (id % 500).toInt(), buildNotification(item.name, msg, indeterminate = false))
                        }
                        if (!DownloadStore.hasRunning()) stopSelf(startId)
                    },
                    {},
                    { stats ->
                        item.doneBytes = stats.size
                        item.currentTimeMs = stats.time.toLong()
                        DownloadStore.upsert(item)
                        if (notifyOn) {
                            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                            nm.notify(
                                notifBase + (id % 500).toInt(),
                                buildNotification(item.name, progressText(item), indeterminate = true)
                            )
                        }
                    }
                )
                sessions[id] = session
            }.onFailure {
                item.status = DlStatus.FAILED
                DownloadStore.upsert(item)
                if (!DownloadStore.hasRunning()) stopSelf(startId)
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun progressText(item: DlItem): String {
        val mb = item.doneBytes / 1048576.0
        return if (item.totalDurationMs > 0 && item.currentTimeMs > 0) {
            val p = (item.currentTimeMs * 100 / item.totalDurationMs).coerceAtMost(100)
            String.format("%d%% · %.1f MB", p, mb)
        } else {
            String.format("%.1f MB 받는 중…", mb)
        }
    }

    private fun buildHeaders(page: String): String {
        val cookie = runCatching { CookieManager.getInstance().getCookie(page) }.getOrNull()
        val sb = StringBuilder()
        sb.append("User-Agent: Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36\r\n")
        if (page.isNotEmpty()) sb.append("Referer: $page\r\n")
        if (!cookie.isNullOrEmpty()) sb.append("Cookie: $cookie\r\n")
        return sb.toString()
    }

    /** HLS 마스터/미디어 재생목록에서 총 재생 시간(ms) 계산. 실패 시 -1 */
    private fun measureHlsDuration(urlStr: String, headers: String): Long {
        return runCatching {
            var text = fetchText(urlStr, headers) ?: return -1
            if ("EXT-X-STREAM-INF" in text) {
                // 변형 재생목록 중 마지막(보통 최고 화질) 선택
                val lines = text.lines()
                var variant: String? = null
                for (i in lines.indices) {
                    if ("EXT-X-STREAM-INF" in lines[i]) {
                        for (j in i + 1 until lines.size) {
                            val t = lines[j].trim()
                            if (t.isNotEmpty() && !t.startsWith("#")) { variant = t; break }
                        }
                    }
                }
                val v = variant ?: return -1
                val abs = URL(URL(urlStr), v).toString()
                text = fetchText(abs, headers) ?: return -1
            }
            if ("EXTINF" !in text) return -1
            var total = 0.0
            for (line in text.lines()) {
                if (line.startsWith("#EXTINF:")) {
                    total += line.removePrefix("#EXTINF:").substringBefore(",").toDoubleOrNull() ?: 0.0
                }
            }
            (total * 1000).toLong()
        }.getOrElse { -1 }
    }

    private fun fetchText(urlStr: String, headers: String): String? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        headers.lines().filter { it.contains(":") }.forEach {
            val k = it.substringBefore(":").trim()
            val v = it.substringAfter(":").trim()
            if (k.isNotEmpty()) conn.setRequestProperty(k, v)
        }
        if (conn.responseCode != 200) { conn.disconnect(); return null }
        val t = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        conn.disconnect()
        return t
    }

    private fun uniqueFile(name: String, ext: String): File {
        val outDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        outDir.mkdirs()
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "video" }
        val safeExt = ext.replace(Regex("[^a-zA-Z0-9]"), "").ifEmpty { "mp4" }
        var f = File(outDir, "$safe.$safeExt")
        var i = 1
        while (f.exists()) {
            f = File(outDir, "${safe}_$i.$safeExt")
            i++
        }
        return f
    }

    /** 완료 파일을 공용 Download/JC Browser 폴터로 복사 (MediaStore, API 29+) */
    private fun copyToPublicDownloads(src: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, src.name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "video/mp4")
            put(
                android.provider.MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/JC Browser"
            )
        }
        val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return
        contentResolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(com.example.streambrowser.R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(title: String, text: String, indeterminate: Boolean): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(0, 0, indeterminate)
            .setContentIntent(pi)
            .setOngoing(indeterminate)
            .build()
    }
}
