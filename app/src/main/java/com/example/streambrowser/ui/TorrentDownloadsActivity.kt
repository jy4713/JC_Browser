package com.example.streambrowser.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.streambrowser.R
import com.example.streambrowser.torrent.TorrentManager
import com.example.streambrowser.util.JcToast
import java.io.File

/**
 * 토렌트 다운로드 관리 화면
 * - 진행 중/완료/취소·실패 목록 (전체/진행/완료/취소 필터)
 * - 진행 중: 일시 중지 / 재시작 / 취소
 * - 완료: 실행(재생) / 목록에서 삭제 (파일은 유지)
 * - 취소·실패: 목록에서 삭제 — 받던 파일도 함께 삭제
 */
class TorrentDownloadsActivity : Activity() {

    private lateinit var adapter: JobAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    private enum class Filter { ALL, RUNNING, DONE, CANCELED }
    private var filter = Filter.ALL

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.example.streambrowser.util.LocaleHelper.wrap(com.example.streambrowser.util.ThemeHelper.wrap(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.streambrowser.util.ThemeHelper.apply(this)
        setContentView(R.layout.activity_torrent_downloads)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = JobAdapter(
            onOpen = { job -> openJob(job) },
            onPause = { job -> TorrentManager.pauseJob(job.key) },
            onResume = { job -> resumeJob(job) },
            onCancel = { job -> TorrentManager.cancelJob(job.key) },
            onDelete = { job -> confirmDelete(job) }
        )
        list.adapter = adapter

        findViewById<TextView>(R.id.chipAll).setOnClickListener { filter = Filter.ALL; applyFilter() }
        findViewById<TextView>(R.id.chipRunning).setOnClickListener { filter = Filter.RUNNING; applyFilter() }
        findViewById<TextView>(R.id.chipDone).setOnClickListener { filter = Filter.DONE; applyFilter() }
        findViewById<TextView>(R.id.chipCanceled).setOnClickListener { filter = Filter.CANCELED; applyFilter() }

        applyFilter()

        // 진행 중 항목 1초 간격 갱신
        ticker = object : Runnable {
            override fun run() {
                adapter.notifyDataSetChanged()
                handler.postDelayed(this, 1000)
            }
        }
        ticker?.let { handler.post(it) }
    }

    private fun resumeJob(job: TorrentManager.Job) {
        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        val max = sp.getInt("torrent_max", 2)
        val running = TorrentManager.listJobs().count {
            it.state == TorrentManager.State.RUNNING && it.key != job.key
        }
        if (running >= max) {
            JcToast.show(this, getString(R.string.torrent_max_reached, max))
            return
        }
        TorrentManager.resumeJob(job.key)
    }

    private fun applyFilter() {
        val all = TorrentManager.listJobs()
        val shown = when (filter) {
            Filter.ALL -> all
            Filter.RUNNING -> all.filter { it.state == TorrentManager.State.RUNNING || it.state == TorrentManager.State.PAUSED }
            Filter.DONE -> all.filter { it.state == TorrentManager.State.DONE }
            Filter.CANCELED -> all.filter { it.state == TorrentManager.State.CANCELLED || it.state == TorrentManager.State.FAILED }
        }
        adapter.submit(shown)
        findViewById<TextView>(R.id.txtEmpty).visibility =
            if (shown.isEmpty()) View.VISIBLE else View.GONE
        refreshChips()
    }

    private fun refreshChips() {
        fun style(id: Int, selected: Boolean) {
            val chip = findViewById<TextView>(id)
            chip.setBackgroundResource(if (selected) R.drawable.bg_tab_indicator else android.R.color.transparent)
            chip.setTextColor(if (selected) 0xFF1A73E8.toInt() else 0xFF5F6368.toInt())
            chip.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        style(R.id.chipAll, filter == Filter.ALL)
        style(R.id.chipRunning, filter == Filter.RUNNING)
        style(R.id.chipDone, filter == Filter.DONE)
        style(R.id.chipCanceled, filter == Filter.CANCELED)
    }

    override fun onDestroy() {
        ticker?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }

    /** 완료 항목 실행: 영상은 내장 플레이어, 나머지는 시스템 앱으로 */
    private fun openJob(job: TorrentManager.Job) {
        val fs = job.info.files()
        val target = (0 until fs.numFiles())
            .map { File(job.saveDir, fs.filePath(it)) }
            .firstOrNull { it.isFile }
        if (target == null) {
            JcToast.show(this, getString(R.string.tdl_file_missing))
            return
        }
        if (TorrentManager.isVideoFile(target.name)) {
            runCatching {
                startActivity(Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_URL, "file://${target.absolutePath}"))
            }.onFailure { openExternal(target, mimeOf(target.name)) }
        } else {
            openExternal(target, mimeOf(target.name))
        }
    }

    private fun openExternal(f: File, mime: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { JcToast.show(this, getString(R.string.tdl_no_app)) }
    }

    private fun confirmDelete(job: TorrentManager.Job) {
        val alsoFiles = job.state == TorrentManager.State.CANCELLED || job.state == TorrentManager.State.FAILED
        val msg = if (alsoFiles) getString(R.string.tdl_delete_files_msg) else getString(R.string.tdl_delete_msg)
        AlertDialog.Builder(this)
            .setTitle(job.name)
            .setMessage(msg)
            .setPositiveButton(getString(R.string.action_remove)) { _, _ ->
                TorrentManager.deleteJob(job.key, deleteFiles = alsoFiles)
                applyFilter()
                JcToast.show(this, getString(R.string.action_remove) + " ✓")
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"; "mov" -> "video/quicktime"; "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"; "ogg" -> "audio/ogg"; "wav" -> "audio/wav"
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"
        "pdf" -> "application/pdf"; "zip" -> "application/zip"
        "txt" -> "text/plain"; "srt" -> "application/x-subrip"
        else -> "application/octet-stream"
    }

    // ---------------- 어댑터 ----------------

    class JobAdapter(
        private val onOpen: (TorrentManager.Job) -> Unit,
        private val onPause: (TorrentManager.Job) -> Unit,
        private val onResume: (TorrentManager.Job) -> Unit,
        private val onCancel: (TorrentManager.Job) -> Unit,
        private val onDelete: (TorrentManager.Job) -> Unit
    ) : RecyclerView.Adapter<JobAdapter.VH>() {

        private var items = listOf<TorrentManager.Job>()

        fun submit(list: List<TorrentManager.Job>) {
            items = list
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.tjName)
            val status: TextView = v.findViewById(R.id.tjStatus)
            val progress: ProgressBar = v.findViewById(R.id.tjProgress)
            val btnOpen: ImageButton = v.findViewById(R.id.btnOpen)
            val btnPause: ImageButton = v.findViewById(R.id.btnPause)
            val btnResume: ImageButton = v.findViewById(R.id.btnResume)
            val btnCancel: ImageButton = v.findViewById(R.id.btnCancel)
            val btnDelete: ImageButton = v.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_torrent_job, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val job = items[position]
            val ctx = holder.itemView.context
            holder.name.text = job.name
            val pct = (job.progress * 100).toInt()
            val spd = "${fmtRate(job.downRate)}↓ ${fmtRate(job.upRate)}↑"

            when (job.state) {
                TorrentManager.State.RUNNING -> {
                    holder.status.text = ctx.getString(
                        R.string.tdl_status_running, pct, fmtSize(job.doneBytes), fmtSize(job.totalSize), spd,
                        job.seeds, job.peers
                    )
                    holder.progress.visibility = View.VISIBLE
                    holder.progress.progress = pct
                    holder.btnOpen.visibility = View.GONE
                    holder.btnPause.visibility = View.VISIBLE
                    holder.btnResume.visibility = View.GONE
                    holder.btnCancel.visibility = View.VISIBLE
                    holder.btnDelete.visibility = View.GONE
                }
                TorrentManager.State.PAUSED -> {
                    holder.status.text = ctx.getString(
                        R.string.tdl_status_paused, pct, fmtSize(job.doneBytes), fmtSize(job.totalSize)
                    )
                    holder.progress.visibility = View.VISIBLE
                    holder.progress.progress = pct
                    holder.btnOpen.visibility = View.GONE
                    holder.btnPause.visibility = View.GONE
                    holder.btnResume.visibility = View.VISIBLE
                    holder.btnCancel.visibility = View.VISIBLE
                    holder.btnDelete.visibility = View.GONE
                }
                TorrentManager.State.DONE -> {
                    holder.status.text = ctx.getString(R.string.tdl_status_done, fmtSize(job.totalSize))
                    holder.progress.visibility = View.GONE
                    holder.btnOpen.visibility = View.VISIBLE
                    holder.btnPause.visibility = View.GONE
                    holder.btnResume.visibility = View.GONE
                    holder.btnCancel.visibility = View.GONE
                    holder.btnDelete.visibility = View.VISIBLE
                }
                TorrentManager.State.CANCELLED -> {
                    holder.status.text = ctx.getString(
                        R.string.tdl_status_cancelled, pct, fmtSize(job.doneBytes), fmtSize(job.totalSize)
                    )
                    holder.progress.visibility = View.VISIBLE
                    holder.progress.progress = pct
                    holder.btnOpen.visibility = View.GONE
                    holder.btnPause.visibility = View.GONE
                    holder.btnResume.visibility = View.VISIBLE
                    holder.btnCancel.visibility = View.GONE
                    holder.btnDelete.visibility = View.VISIBLE
                }
                TorrentManager.State.FAILED -> {
                    holder.status.text = ctx.getString(R.string.tdl_status_failed, job.error ?: "")
                    holder.progress.visibility = View.GONE
                    holder.btnOpen.visibility = View.GONE
                    holder.btnPause.visibility = View.GONE
                    holder.btnResume.visibility = View.VISIBLE
                    holder.btnCancel.visibility = View.GONE
                    holder.btnDelete.visibility = View.VISIBLE
                }
            }

            holder.btnOpen.setOnClickListener { onOpen(job) }
            holder.btnPause.setOnClickListener { onPause(job) }
            holder.btnResume.setOnClickListener { onResume(job) }
            holder.btnCancel.setOnClickListener { onCancel(job) }
            holder.btnDelete.setOnClickListener { onDelete(job) }
        }

        private fun fmtRate(bps: Int): String = fmtSize(bps.toLong()) + "/s"

        private fun fmtSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var v = bytes.toDouble()
            var u = 0
            while (v >= 1024 && u < units.lastIndex) { v /= 1024; u++ }
            return String.format("%.1f %s", v, units[u])
        }
    }
}
