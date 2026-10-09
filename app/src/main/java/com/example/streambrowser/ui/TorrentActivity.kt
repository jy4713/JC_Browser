package com.example.streambrowser.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.example.streambrowser.R
import com.example.streambrowser.torrent.TorrentManager
import com.example.streambrowser.util.JcToast
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 토렌트 정보 해석 화면
 * - magnet/.torrent URL을 받아 메타데이터(이름/크기/파일 수)를 조회하고
 * - 확인되면 바로 전체 다운로드를 시작한 뒤 TorrentDownloadsActivity(관리 화면)로 이동
 * - 실패하면 사유를 표시하고 관리 화멸만 열 수 있는 버튼 유지
 */
class TorrentActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"
    }

    private var ti: TorrentInfo? = null

    private lateinit var txtName: TextView
    private lateinit var txtInfo: TextView
    private lateinit var txtStatus: TextView
    private lateinit var prog: ProgressBar
    private lateinit var btnList: Button

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
        btnList = findViewById(R.id.torList)
        btnList.setOnClickListener { openManager() }

        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        txtStatus.text = getString(R.string.torrent_loading)
        kotlin.concurrent.thread { resolve(url) }
    }

    private fun openManager() {
        runCatching { startActivity(Intent(this, TorrentDownloadsActivity::class.java)) }
        finish()
    }

    /** magnet 또는 .torrent URL → TorrentInfo (IO 스레드) */
    private fun resolve(url: String) {
        runCatching {
            when {
                url.startsWith("magnet:") ->
                    TorrentManager.fetchMagnetInfo(withDefaultTrackers(url), 120, workDir())
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
                    txtStatus.text = getString(R.string.torrent_load_failed) + "\n" + getString(R.string.torrent_magnet_timeout)
                } else {
                    ti = info
                    showMeta(info)
                    startDownload(info)
                }
            }
        }.onFailure { e ->
            runOnUiThread {
                txtStatus.text = getString(R.string.torrent_load_failed) + "\n${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    private fun workDir(): File = File(filesDir, "torrent").apply { mkdirs() }

    /** 트래커가 없는 마그넷에 공개 트래커를 붙여 메타데이터 수신 성공률을 높인다 */
    private fun withDefaultTrackers(magnet: String): String {
        if (magnet.contains("tr=")) return magnet
        val trackers = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce"
        )
        return magnet + trackers.joinToString("") { "&tr=" + java.net.URLEncoder.encode(it, "UTF-8") }
    }

    private fun showMeta(info: TorrentInfo) {
        val fs = info.files()
        txtName.text = info.name()
        val total = fs.numFiles()
        val sizeStr = formatSize(info.totalSize())
        txtInfo.text = getString(R.string.torrent_meta, total, sizeStr)
    }

    /** 전체 파일 다운로드 시작 → 관리 화면으로 이동 */
    private fun startDownload(info: TorrentInfo) {
        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        val maxActive = sp.getInt("torrent_max", 2)
        txtStatus.text = getString(R.string.torrent_auto_starting)
        kotlin.concurrent.thread {
            val job = runCatching {
                TorrentManager.add(info, File(workDir(), "downloads"), maxActive)
            }.getOrElse { e ->
                runOnUiThread {
                    txtStatus.text = if (e.message == "max_active")
                        getString(R.string.torrent_max_reached, maxActive)
                    else getString(R.string.torrent_start_failed)
                }
                return@thread
            }
            runOnUiThread {
                JcToast.show(this, getString(R.string.torrent_started, job.name))
                openManager()
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var u = 0
        while (v >= 1024 && u < units.lastIndex) { v /= 1024; u++ }
        return String.format("%.1f %s", v, units[u])
    }
}
