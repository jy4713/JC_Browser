package com.example.streambrowser.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.example.streambrowser.R
import com.example.streambrowser.download.DownloadFolder
import com.example.streambrowser.torrent.TorrentManager
import com.example.streambrowser.util.JcToast
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 토렌트 순차 재생/다운로드 화면
 * - magnet: 링크 또는 .torrent URL을 받아 메타데이터 표시
 * - "바로 재생": 가장 큰 영상 파일만 순차 다운로드 → 플레이어가 받으면서 재생
 * - "전체 다운로드": 모든 파일 일반 다운로드, 완료 시 공유 다운로드 폴터로 복사
 */
class TorrentActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var ti: TorrentInfo? = null
    private var magnetUri: String? = null
    private var job: TorrentManager.Job? = null
    private var exported = false
    private var stopped = false

    private lateinit var txtName: TextView
    private lateinit var txtInfo: TextView
    private lateinit var txtStatus: TextView
    private lateinit var prog: ProgressBar
    private lateinit var btnPlay: Button
    private lateinit var btnDl: Button
    private lateinit var btnStop: Button

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.example.streambrowser.util.LocaleHelper.wrap(com.example.streambrowser.util.ThemeHelper.wrap(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.streambrowser.util.ThemeHelper.apply(this)
        setContentView(R.layout.activity_torrent)

        txtName = findViewById(R.id.torName)
        txtInfo = findViewById(R.id.torInfo)
        txtStatus = findViewById(R.id.torStatus)
        prog = findViewById(R.id.torProgress)
        btnPlay = findViewById(R.id.torPlay)
        btnDl = findViewById(R.id.torDownload)
        btnStop = findViewById(R.id.torStop)

        btnPlay.setOnClickListener { start(true) }
        btnDl.setOnClickListener { start(false) }
        btnStop.setOnClickListener { stopJob() }

        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        txtStatus.text = getString(R.string.torrent_loading)
        kotlin.concurrent.thread { resolve(url) }
    }

    /** magnet 또는 .torrent URL → TorrentInfo (IO 스레드) */
    private fun resolve(url: String) {
        runCatching {
            when {
                url.startsWith("magnet:") -> {
                    magnetUri = url
                    TorrentManager.fetchMagnetInfo(url, 60, workDir())
                }
                else -> {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 20000
                    conn.instanceFollowRedirects = true
                    if (conn.responseCode != 200) { conn.disconnect(); null }
                    else conn.inputStream.use { TorrentInfo(it.readBytes()) }
                        .also { conn.disconnect() }
                }
            }
        }.onSuccess { info ->
            runOnUiThread {
                if (info == null) {
                    txtStatus.text = getString(R.string.torrent_load_failed)
                    btnPlay.isEnabled = false
                    btnDl.isEnabled = false
                } else {
                    ti = info
                    showMeta(info)
                }
            }
        }.onFailure {
            runOnUiThread {
                txtStatus.text = getString(R.string.torrent_load_failed)
                btnPlay.isEnabled = false
                btnDl.isEnabled = false
            }
        }
    }

    private fun workDir(): File = File(filesDir, "torrent").apply { mkdirs() }

    private fun showMeta(info: TorrentInfo) {
        val fs = info.files()
        txtName.text = info.name()
        val total = fs.numFiles()
        val vidIdx = TorrentManager.largestVideoIndex(info)
        val sizeStr = formatSize(info.totalSize())
        val videoStr = if (vidIdx >= 0)
            getString(R.string.torrent_video_file, fs.fileName(vidIdx), formatSize(fs.fileSize(vidIdx)))
        else getString(R.string.torrent_no_video)
        txtInfo.text = getString(R.string.torrent_meta, total, sizeStr, videoStr)
        btnPlay.isEnabled = vidIdx >= 0
        txtStatus.text = getString(R.string.torrent_ready)
    }

    /** @param stream true=바로 재생(영상 하나만 순차) / false=전체 다운로드 */
    private fun start(stream: Boolean) {
        val info = ti ?: return
        btnPlay.isEnabled = false
        btnDl.isEnabled = false
        txtStatus.text = getString(R.string.torrent_starting)
        val saveDir = if (stream) File(workDir(), "stream") else File(workDir(), "full")
        kotlin.concurrent.thread {
            val j = runCatching { TorrentManager.add(info, saveDir, stream) }.getOrNull()
            runOnUiThread {
                if (j == null) {
                    txtStatus.text = getString(R.string.torrent_start_failed)
                    btnPlay.isEnabled = true
                    btnDl.isEnabled = true
                    return@runOnUiThread
                }
                job = j
                poll()
                if (stream) openPlayerWhenReady(j)
            }
        }
    }

    /** 순차 다운로드로 앞부분이 받아지면 플레이어 실행 (IO 스레드에서 파일 생성 대기) */
    private fun openPlayerWhenReady(j: TorrentManager.Job) {
        kotlin.concurrent.thread {
            val f = j.streamFile()
            if (TorrentManager.awaitFileReady(f, 60)) {
                runOnUiThread {
                    JcToast.show(this, getString(R.string.torrent_playing))
                    startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_URL, "file://${f!!.absolutePath}")
                    )
                }
            } else {
                runOnUiThread { txtStatus.text = getString(R.string.torrent_file_wait_failed) }
            }
        }
    }

    private fun poll() {
        handler.postDelayed({
            val j = job
            if (j == null || stopped) return@postDelayed
            runCatching {
                val st = j.handle.status()
                val p = st.progress()
                prog.progress = (p * 100).toInt()
                txtStatus.text = getString(
                    R.string.torrent_status,
                    (p * 100).toInt(),
                    st.numSeeds(),
                    st.numPeers(),
                    formatSize(st.totalDone())
                )
                // 전체 다운로드 완료 시 공유 다운로드 폴터로 복사 (한 번만)
                if (p >= 1f && !exported && j.streamFileIndex < 0) {
                    exported = true
                    exportAll(j)
                }
            }
            poll()
        }, 1000)
    }

    private fun exportAll(j: TorrentManager.Job) {
        kotlin.concurrent.thread {
            val base = File(j.saveDir, j.info.files().filePath(0)).parentFile ?: return@thread
            val files = base.walkTopDown().filter { it.isFile }.toList()
            files.forEach { DownloadFolder.export(this, it, mimeOf(it.name)) }
            runOnUiThread { JcToast.show(this, getString(R.string.torrent_done, files.size)) }
        }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"; "mp3" -> "audio/mpeg"; "srt" -> "application/x-subrip"
        else -> "application/octet-stream"
    }

    private fun stopJob() {
        stopped = true
        job?.let { TorrentManager.remove(TorrentManager.infoHashHex(it.info)) }
        job = null
        txtStatus.text = getString(R.string.torrent_stopped)
        btnPlay.isEnabled = ti != null && TorrentManager.largestVideoIndex(ti!!) >= 0
        btnDl.isEnabled = ti != null
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var u = 0
        while (v >= 1024 && u < units.lastIndex) { v /= 1024; u++ }
        return String.format("%.1f %s", v, units[u])
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
