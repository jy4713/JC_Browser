package com.example.streambrowser.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.streambrowser.R
import com.example.streambrowser.download.DlItem
import com.example.streambrowser.download.DlStatus
import com.example.streambrowser.download.DownloadStore
import com.example.streambrowser.download.VideoDownloadService
import java.io.File

/** 다운로드 관리 화면: 진행률/%/크기, 취소, 재생, 이름 변경, 삭제 */
class DownloadsActivity : Activity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.example.streambrowser.util.LocaleHelper.wrap(newBase))
    }

    private lateinit var adapter: DlAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = DlAdapter(
            onPlay = { item -> playFile(item) },
            onCancel = { item ->
                VideoDownloadService.cancel(item.id)
                Toast.makeText(this, "취소 요청됨", Toast.LENGTH_SHORT).show()
            },
            onRename = { item -> renameItem(item) },
            onDelete = { item -> deleteItem(item) }
        )
        list.adapter = adapter

        DownloadStore.listener = { runOnUiThread { adapter.submit(DownloadStore.items) } }
        adapter.submit(DownloadStore.items)

        // 진행 중 0.5초 간격 갱신
        ticker = object : Runnable {
            override fun run() {
                adapter.notifyDataSetChanged()
                handler.postDelayed(this, 500)
            }
        }
        ticker?.let { handler.post(it) }
    }

    override fun onDestroy() {
        DownloadStore.listener = null
        ticker?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }

    private fun playFile(item: DlItem) {
        val f = item.file
        if (f == null || !f.exists()) {
            Toast.makeText(this, "파일이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val mime = if (item.kind == "IMG") "image/*" else "video/*"
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(i) }.onFailure {
            Toast.makeText(this, "재생할 앱이 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renameItem(item: DlItem) {
        val input = EditText(this).apply {
            setText(item.name)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("이름 변경")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                val newName = input.text.toString().trim().ifEmpty { return@setPositiveButton }
                runCatching {
                    val old = item.file
                    if (old != null && old.exists()) {
                        val renamed = File(old.parentFile, newName + "." + item.ext)
                        if (old.renameTo(renamed)) {
                            DownloadStore.upsert(item.copy(name = newName, file = renamed))
                        }
                    } else {
                        DownloadStore.upsert(item.copy(name = newName))
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun deleteItem(item: DlItem) {
        if (item.status == DlStatus.RUNNING || item.status == DlStatus.PENDING) {
            VideoDownloadService.cancel(item.id)
        }
        item.file?.delete()
        DownloadStore.remove(item.id)
    }

    // ---------------- 어댑터 ----------------

    class DlAdapter(
        private val onPlay: (DlItem) -> Unit,
        private val onCancel: (DlItem) -> Unit,
        private val onRename: (DlItem) -> Unit,
        private val onDelete: (DlItem) -> Unit
    ) : RecyclerView.Adapter<DlAdapter.VH>() {

        private var items = listOf<DlItem>()

        fun submit(list: List<DlItem>) {
            items = list
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.dlName)
            val status: TextView = v.findViewById(R.id.dlStatus)
            val progress: ProgressBar = v.findViewById(R.id.dlProgress)
            val btnPlay: ImageButton = v.findViewById(R.id.btnPlay)
            val btnCancel: ImageButton = v.findViewById(R.id.btnCancel)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.name.text = item.name

            when (item.status) {
                DlStatus.PENDING -> {
                    holder.status.text = "대기 중…"
                    holder.progress.visibility = View.GONE
                    holder.btnPlay.visibility = View.GONE
                    holder.btnCancel.visibility = View.VISIBLE
                }
                DlStatus.RUNNING -> {
                    val mb = item.doneBytes / 1048576.0
                    val spd = item.speedBps / 1048576.0
                    holder.status.text = if (item.totalDurationMs > 0 && item.currentTimeMs > 0) {
                        val p = (item.currentTimeMs * 100 / item.totalDurationMs).coerceIn(0, 100).toInt()
                        holder.progress.visibility = View.VISIBLE
                        holder.progress.progress = p
                        String.format("%d%%  ·  %.1f MB  ·  %.1f MB/s", p, mb, spd)
                    } else {
                        holder.progress.visibility = View.GONE
                        String.format("%.1f MB  ·  %.1f MB/s", mb, spd)
                    }
                    holder.btnPlay.visibility = View.GONE
                    holder.btnCancel.visibility = View.VISIBLE
                }
                DlStatus.DONE -> {
                    val sz = (item.file?.length() ?: 0) / 1048576.0
                    holder.status.text = String.format("완료 · %.1f MB", sz)
                    holder.progress.visibility = View.GONE
                    holder.btnPlay.visibility = View.VISIBLE
                    holder.btnCancel.visibility = View.GONE
                }
                DlStatus.CANCELED -> {
                    holder.status.text = "취소됨"
                    holder.progress.visibility = View.GONE
                    holder.btnPlay.visibility = View.GONE
                    holder.btnCancel.visibility = View.GONE
                }
                DlStatus.FAILED -> {
                    holder.status.text = "실패 (길게 눌러 삭제)"
                    holder.progress.visibility = View.GONE
                    holder.btnPlay.visibility = View.GONE
                    holder.btnCancel.visibility = View.GONE
                }
            }

            holder.btnPlay.setOnClickListener { onPlay(item) }
            holder.btnCancel.setOnClickListener { onCancel(item) }
            holder.itemView.setOnLongClickListener {
                val opts = arrayOf("이름 변경", "삭제")
                AlertDialog.Builder(holder.itemView.context)
                    .setTitle(item.name)
                    .setItems(opts) { _, which ->
                        when (which) {
                            0 -> onRename(item)
                            1 -> onDelete(item)
                        }
                    }
                    .show()
                true
            }
        }
    }
}
