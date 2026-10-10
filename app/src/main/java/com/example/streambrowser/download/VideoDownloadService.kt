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
        const val EXTRA_HEADERS = "headers"

        val sessions = ConcurrentHashMap<Long, Session>()

        /** 동시 다운로드 제한 대기열 — 초과 건은 여기 쌓아두고 완료 시 순서대로 시작 */
        private val waitQueue = java.util.ArrayDeque<Intent>()

        @Volatile
        private var runningCount = 0

        fun cancel(id: Long) {
            sessions[id]?.cancel()
            FastVideoDownloader.cancel(id)
        }

        /** 일시 중지: ffmpeg 세션 + 고속 다운로더 중지 (부분 파일 보존) */
        fun pause(id: Long) {
            sessions[id]?.cancel()
            FastVideoDownloader.pause(id)
        }
    }

    private val notifBase = 2000

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DownloadStore.init(this)
        val id = intent?.getLongExtra(EXTRA_ID, System.currentTimeMillis()) ?: System.currentTimeMillis()
        val url = intent?.getStringExtra(EXTRA_URL) ?: run { stopSelf(); return START_NOT_STICKY }

        // 동시 다운로드 제한 — 초과 건은 대기열에 넣고 실행 중 것이 끝나면 순서대로 시작
        val sp0 = getSharedPreferences("settings", MODE_PRIVATE)
        val maxConcurrent = sp0.getInt("dl_max_concurrent", 2).coerceAtLeast(1)
        synchronized(waitQueue) {
            if (runningCount >= maxConcurrent) {
                intent.let { waitQueue.add(it) }
                com.example.streambrowser.util.JcToast.show(
                    this,
                    getString(R.string.notif_queued, waitQueue.size)
                )
                return START_NOT_STICKY
            }
            runningCount++
        }
        val page = intent.getStringExtra(EXTRA_PAGE) ?: ""
        val kind = intent.getStringExtra(EXTRA_KIND) ?: ""
        val name = intent.getStringExtra(EXTRA_NAME) ?: ("video_" + System.currentTimeMillis())
        val ext = intent.getStringExtra(EXTRA_EXT)?.trim()?.removePrefix(".")?.ifEmpty { "mp4" } ?: "mp4"

        val item = DownloadStore.get(id) ?: DlItem(id, url, page, kind, name, ext).also { DownloadStore.upsert(it) }
        // 감지 시점에 페이지가 본 요청의 핵심 헤더 — 없으면 저장된 항목 것 (재개 경로)
        val captured = intent.getStringExtra(EXTRA_HEADERS)?.takeIf { it.isNotBlank() } ?: item.headers
        if (item.headers.isBlank() && captured.isNotBlank()) {
            item.headers = captured
        }

        // 설정: 알림 표시 여부 / 다운로드 위치 (공용 Download 폴더면 완료 후 복사)
        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        val notifyOn = sp.getBoolean("dl_notify", true)

        createChannel()
        val notifId = notifBase + (id % 500).toInt()
        startForeground(notifId, buildNotification(item.name, getString(com.example.streambrowser.R.string.notif_preparing), indeterminate = true))

        // 진행 중 1초 간격으로 노티 갱신 (fast/ffmpeg 양 경로 공통, preparing 정적 문구 대체)
        Thread {
            while (item.status == DlStatus.PENDING || item.status == DlStatus.RUNNING) {
                Thread.sleep(1000)
                runCatching {
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(notifId, buildNotification(item.name, DlFormat.progress(item), indeterminate = true))
                }
            }
        }.start()

        Thread {
            val headers = buildHeaders(page, captured)

            // Soul 스타일 고속 분할 병렬 다운로드 시도 (HLS는 세그먼트 병렬, 직접 파일은 Range 분할)
            if (sp.getBoolean("fast_dl", true)) {
                val canFast = item.kind == "HLS" || ".m3u8" in item.url ||
                        runCatching { FastVideoDownloader.isRangeSupported(item.url, headers) }.getOrDefault(false)
                if (canFast) {
                    val result = runCatching {
                        FastVideoDownloader.download(
                            this, item, headers,
                            split = sp.getInt("dl_split", 8),
                            conn = sp.getInt("dl_conn", 4)
                        )
                    }.getOrElse {
                        android.util.Log.e("VideoDownloadService", "fast download error", it)
                        FastVideoDownloader.Result.FAILED
                    }
                    when (result) {
                        FastVideoDownloader.Result.DONE -> {
                            runCatching { item.file?.let { DownloadFolder.export(this, it, "video/mp4") } }
                            notifyFinished(item, notifyOn, startId)
                            return@Thread
                        }
                        FastVideoDownloader.Result.CANCELED -> {
                            item.status = DlStatus.CANCELED
                            DownloadStore.upsert(item)
                            notifyFinished(item, notifyOn, startId)
                            return@Thread
                        }
                        FastVideoDownloader.Result.PAUSED -> {
                            item.status = DlStatus.PAUSED
                            DownloadStore.upsert(item)
                            notifyFinished(item, notifyOn, startId)
                            return@Thread
                        }
                        FastVideoDownloader.Result.FAILED -> {
                            // ffmpeg 경로로 폴파
                        }
                    }
                }
            }

            runCatching {
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
                        val paused = FastVideoDownloader.isPaused(id)
                        item.status = when {
                            paused -> DlStatus.PAUSED
                            code != null && code.isValueSuccess -> DlStatus.DONE
                            code != null && code.isValueCancel -> DlStatus.CANCELED
                            else -> DlStatus.FAILED
                        }
                        // 취소/일시 중지 시 부분 파일 삭제 (ffmpeg 경로는 재개 시 처음부터 다시 받음)
                        if (item.status == DlStatus.CANCELED || paused) out.delete()
                        // 공용 다운로드 폴더 모드: 완료 파일을 Download/JC Browser로 복사
                        if (item.status == DlStatus.DONE) {
                                    DownloadFolder.export(this, out, "video/mp4")
                                }
                        DownloadStore.upsert(item)
                        notifyFinished(item, notifyOn, startId)
                    },
                    {},
                    { stats ->
                        item.doneBytes = stats.size
                        item.currentTimeMs = stats.time.toLong()
                        item.speedBps = stats.bitrate.toLong() * 1000L / 8
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
                notifyFinished(item, notifyOn, startId)
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun progressText(item: DlItem): String {
        val mb = item.doneBytes / 1048576.0
        val spd = item.speedBps / 1048576.0
        return if (item.totalDurationMs > 0 && item.currentTimeMs > 0) {
            val p = (item.currentTimeMs * 100 / item.totalDurationMs).coerceAtMost(100)
            String.format("%d%% · %.1f MB · %.1f MB/s", p, mb, spd)
        } else {
            String.format("%.1f MB · %.1f MB/s", mb, spd)
        }
    }

    private fun notifyFinished(item: DlItem, notifyOn: Boolean, startId: Int) {
        val notifId = notifBase + (item.id % 500).toInt()
        if (notifyOn) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (item.status == DlStatus.FAILED || item.status == DlStatus.CANCELED) {
                // 실패/취소 — 진행 표시(프로그레스 바)가 노티바에 남지 않게 제거
                nm.cancel(notifId)
            } else {
                val fname = item.file?.name ?: item.name
                val msg = when (item.status) {
                    DlStatus.DONE -> "${getString(R.string.notif_done)}: $fname"
                    DlStatus.PAUSED -> "${getString(R.string.notif_paused)}: $fname"
                    else -> "${getString(R.string.notif_failed)}: $fname"
                }
                nm.notify(notifId, buildNotification(item.name, msg, indeterminate = false))
            }
        }
        // 실행 슬롯 해제 — 대기 중인 건이 있으면 바로 다음 다운로드 시작
        val next: Intent? = synchronized(waitQueue) {
            runningCount--
            waitQueue.poll()
        }
        if (next != null) {
            runCatching { startService(next) }
            return
        }
        if (!DownloadStore.hasRunning()) {
            // 포그라운드 알림이 시스템에 남지 않게 명시적으로 제거 후 서비스 종료
            runCatching { stopForeground(Service.STOP_FOREGROUND_REMOVE) }
            stopSelf(startId)
        }
    }

    /** 다운로드용 헤더 조립.
     *  1) 감지 시점에 페이지가 본 요청에서 뽑은 User-Agent/Referer/Origin/Cookie/Accept 우선
     *     (재생과 동일한 세션 — 복호화 키 요청에도 그대로 쓰임)
     *  2) 빠진 것만 보충: UA는 실제 웹뷰 기본 UA, Cookie는 CookieManager(페이지 URL 기준), Referer는 페이지 URL */
    private fun buildHeaders(page: String, captured: String): String {
        val sb = StringBuilder()
        var hasUa = false; var hasCookie = false; var hasReferer = false
        captured.lines().filter { it.contains(":") }.forEach { line ->
            val k = line.substringBefore(":").trim()
            when (k.lowercase()) {
                "user-agent" -> hasUa = true
                "cookie" -> hasCookie = true
                "referer" -> hasReferer = true
            }
            sb.append(line.trimEnd()).append("\r\n")
        }
        if (!hasUa) {
            sb.append("User-Agent: ").append(android.webkit.WebSettings.getDefaultUserAgent(this)).append("\r\n")
        }
        if (!hasCookie && page.isNotEmpty()) {
            runCatching { CookieManager.getInstance().getCookie(page) }?.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { sb.append("Cookie: ").append(it).append("\r\n") }
        }
        if (!hasReferer && page.isNotEmpty()) sb.append("Referer: $page\r\n")
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

    /** 완료 파일을 공용 Download/JC Browser 폴더로 복사 (MediaStore, API 29+) */
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
