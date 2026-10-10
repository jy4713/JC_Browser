package com.example.streambrowser.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.streambrowser.R
import com.example.streambrowser.download.DlFormat
import com.example.streambrowser.download.DlItem
import com.example.streambrowser.download.DlStatus
import com.example.streambrowser.download.DownloadStore
import com.example.streambrowser.download.ThumbLoader
import com.example.streambrowser.download.VideoDownloadService
import java.io.File

/** 다운로드 관리 화면: 진행률/%/크기, 취소, 재생, 이름 변경, 삭제 */
class DownloadsActivity : Activity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.example.streambrowser.util.LocaleHelper.wrap(com.example.streambrowser.util.ThemeHelper.wrap(newBase)))
    }

    private lateinit var adapter: DlAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    private enum class Filter { ALL, RUNNING, DONE, CANCELED }
    private var filter = Filter.ALL

    /** MEDIA: 앱 내 미디어 다운로드 / FILES: 시스템 다운로드 매니저의 일반 파일 */
    private enum class Mode { MEDIA, FILES }
    private var mode = Mode.MEDIA

    private lateinit var chipAll: TextView
    private lateinit var chipRunning: TextView
    private lateinit var chipDone: TextView
    private lateinit var chipCanceled: TextView
    private lateinit var txtEmpty: TextView
    private var sysAdapter: SysAdapter? = null
    private var filesAdapter: FilesAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.streambrowser.util.ThemeHelper.apply(this)
        setContentView(R.layout.activity_downloads)

        DownloadStore.init(this)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        chipAll = findViewById(R.id.chipAll)
        chipRunning = findViewById(R.id.chipRunning)
        chipDone = findViewById(R.id.chipDone)
        chipCanceled = findViewById(R.id.chipCanceled)
        txtEmpty = findViewById(R.id.txtEmpty)
        chipAll.setOnClickListener { setFilter(Filter.ALL) }
        chipRunning.setOnClickListener { setFilter(Filter.RUNNING) }
        chipDone.setOnClickListener { setFilter(Filter.DONE) }
        chipCanceled.setOnClickListener { setFilter(Filter.CANCELED) }

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = DlAdapter(
            onPlay = { item -> playFile(item) },
            onCancel = { item ->
                VideoDownloadService.cancel(item.id)
                com.example.streambrowser.util.JcToast.show(this, getString(R.string.common_cancel_requested))
            },
            onRename = { item -> renameItem(item) },
            onDelete = { item -> deleteItem(item) },
            onCopy = { item -> copyUrl(item) },
            onPause = { item -> VideoDownloadService.pause(item.id) },
            onResume = { item -> resumeDl(item) }
        )
        sysAdapter = SysAdapter(
            onOpen = { item -> openSysFile(item) },
            onDelete = { item -> deleteSysFile(item) },
            onReloaded = { n ->
                if (mode == Mode.FILES) {
                    txtEmpty.text = getString(R.string.sys_dl_empty)
                    txtEmpty.visibility = if (n == 0 && adapter.currentCount() == 0) View.VISIBLE else View.GONE
                }
            }
        )
        filesAdapter = FilesAdapter(adapter, sysAdapter!!)
        list.adapter = adapter

        // 미디어/파일 탭
        findViewById<TextView>(R.id.tabMedia).setOnClickListener { setMode(Mode.MEDIA) }
        findViewById<TextView>(R.id.tabFiles).setOnClickListener { setMode(Mode.FILES) }

        DownloadStore.listener = { runOnUiThread { if (mode == Mode.FILES) refreshFiles() else applyFilter() } }
        setMode(Mode.MEDIA)

        // 진행 중 0.5초 간격 갱신
        ticker = object : Runnable {
            override fun run() {
                if (mode == Mode.MEDIA) {
                    adapter.notifyDataSetChanged()
                } else {
                    sysAdapter?.reload { querySystemDownloads() }
                }
                handler.postDelayed(this, 500)
            }
        }
        ticker?.let { handler.post(it) }
    }

    /** 파일 탭 갱신: 앱에서 받은 이미지 + 시스템 다운로드 일반 파일 */
    private fun refreshFiles() {
        val images = DownloadStore.items.filter { it.kind == "IMG" }.sortedByDescending { it.id }
        adapter.submit(images)
        filesAdapter?.notifyDataSetChanged()
        sysAdapter?.reload { querySystemDownloads() }
    }

    private fun setMode(m: Mode) {
        mode = m
        val list = findViewById<RecyclerView>(R.id.list)
        val chipRow = findViewById<LinearLayout>(R.id.chipRow)
        fun style(tab: TextView, selected: Boolean) {
            tab.setBackgroundResource(if (selected) R.drawable.bg_tab_indicator else android.R.color.transparent)
            tab.setTextColor(if (selected) 0xFF1A73E8.toInt() else 0xFF5F6368.toInt())
            tab.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        style(findViewById(R.id.tabMedia), m == Mode.MEDIA)
        style(findViewById(R.id.tabFiles), m == Mode.FILES)
        if (m == Mode.MEDIA) {
            chipRow.visibility = View.VISIBLE
            list.adapter = adapter
            applyFilter()
        } else {
            chipRow.visibility = View.GONE
            list.adapter = filesAdapter
            refreshFiles()
        }
    }

    /** 파일 탭 어댑터: 이미지(DlAdapter) + 시스템 파일(SysAdapter) 결합 */
    class FilesAdapter(
        private val imgAdapter: DlAdapter,
        private val sysAdapter: SysAdapter
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int) =
            if (position < imgAdapter.currentCount()) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == 0) imgAdapter.onCreateViewHolder(parent, viewType)
            else sysAdapter.onCreateViewHolder(parent, viewType)

        override fun getItemCount() = imgAdapter.currentCount() + sysAdapter.itemCount

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (getItemViewType(position) == 0) {
                imgAdapter.onBindViewHolder(holder as DlAdapter.VH, position)
            } else {
                sysAdapter.onBindViewHolder(holder as SysAdapter.VH, position - imgAdapter.currentCount())
            }
        }
    }

    private fun setFilter(f: Filter) {
        filter = f
        applyFilter()
    }

    /** 선택한 탭에 따라 목록 필터링 + 빈 화면/칩 스타일 갱신 */
    private fun applyFilter() {
        // 비디오 탭: 스트리밍 영상(HLS/MP4 등)만 — 이미지는 파일 탭에서
        val all = DownloadStore.items.filter { it.kind != "IMG" }.sortedByDescending { it.id }
        val shown = when (filter) {
            Filter.ALL -> all
            Filter.RUNNING -> all.filter { it.status == DlStatus.PENDING || it.status == DlStatus.RUNNING || it.status == DlStatus.PAUSED }
            Filter.DONE -> all.filter { it.status == DlStatus.DONE }
            Filter.CANCELED -> all.filter { it.status == DlStatus.CANCELED || it.status == DlStatus.FAILED }
        }
        adapter.submit(shown)
        txtEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        txtEmpty.text = getString(R.string.no_downloads)
        refreshChips()
    }

    private fun refreshChips() {
        fun style(chip: TextView, selected: Boolean) {
            chip.setBackgroundResource(if (selected) R.drawable.bg_tab_indicator else android.R.color.transparent)
            chip.setTextColor(if (selected) 0xFF1A73E8.toInt() else 0xFF5F6368.toInt())
            chip.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        style(chipAll, filter == Filter.ALL)
        style(chipRunning, filter == Filter.RUNNING)
        style(chipDone, filter == Filter.DONE)
        style(chipCanceled, filter == Filter.CANCELED)
    }

    override fun onDestroy() {
        DownloadStore.listener = null
        ticker?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }

    private fun playFile(item: DlItem) {
        val f = item.file
        if (f == null || !f.exists()) {
            com.example.streambrowser.util.JcToast.show(this, getString(R.string.common_no_file))
            return
        }
        // 영상은 외부 앱 대신 내장 플레이어로 (외부 플레이어가 HLS 병합본을 못 먹는 경우 방지)
        if (item.kind != "IMG") {
            runCatching {
                startActivity(Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_URL, "file://${f.absolutePath}")
                    .putExtra(PlayerActivity.EXTRA_PAGE, item.page))
            }.onFailure { openWithExternalApp(f, item.kind) }
            return
        }
        openWithExternalApp(f, item.kind)
    }

    private fun openWithExternalApp(f: File, kind: String) {
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val mime = if (kind == "IMG") "image/*" else "video/*"
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(i) }.onFailure {
            com.example.streambrowser.util.JcToast.show(this, getString(R.string.common_no_app))
        }
    }

    private fun renameItem(item: DlItem) {
        val input = EditText(this).apply {
            setText(item.name)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.common_rename))
            .setView(com.example.streambrowser.ui.JcUi.fieldBox(this, input))
            .setPositiveButton(getString(R.string.common_save)) { _, _ ->
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
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun deleteItem(item: DlItem) {
        if (item.status == DlStatus.RUNNING || item.status == DlStatus.PENDING) {
            VideoDownloadService.cancel(item.id)
        }
        item.file?.delete()
        // 일시 중지된 분할 다운로드의 부분 파일 정리
        File(cacheDir, "hls_${item.id}").deleteRecursively()
        File(cacheDir, "split_${item.id}").deleteRecursively()
        DownloadStore.remove(item.id)
    }

    private fun copyUrl(item: DlItem) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("url", item.url))
        com.example.streambrowser.util.JcToast.show(this, R.string.link_copied)
    }

    /** 일시 중지된 다운로드 이어받기: 같은 항목으로 서비스 재시작 (고속 경로는 부분 파일 이어서 받음) */
    private fun resumeDl(item: DlItem) {
        item.status = DlStatus.PENDING
        item.speedBps = 0
        DownloadStore.upsert(item)
        val i = Intent(this, VideoDownloadService::class.java).apply {
            putExtra(VideoDownloadService.EXTRA_ID, item.id)
            putExtra(VideoDownloadService.EXTRA_URL, item.url)
            putExtra(VideoDownloadService.EXTRA_PAGE, item.page)
            putExtra(VideoDownloadService.EXTRA_KIND, item.kind)
            putExtra(VideoDownloadService.EXTRA_NAME, item.name)
            putExtra(VideoDownloadService.EXTRA_EXT, item.ext)
            putExtra(VideoDownloadService.EXTRA_HEADERS, item.headers)
        }
        startForegroundService(i)
    }

    // ---------------- 시스템 다운로드 (zip/pdf 등 일반 파일) ----------------

    class SysDl(
        val id: Long,
        val title: String,
        val mime: String,
        val status: Int,   // DownloadManager.STATUS_*
        val total: Long,
        val done: Long
    )

    private fun querySystemDownloads(): List<SysDl> {
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val c = runCatching { dm.query(android.app.DownloadManager.Query()) }.getOrNull()
            ?: return emptyList()
        val out = mutableListOf<SysDl>()
        c.use {
            val iId = it.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_ID)
            val iTitle = it.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TITLE)
            val iMime = it.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_MEDIA_TYPE)
            val iStatus = it.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS)
            val iTotal = it.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val iDone = it.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            while (it.moveToNext()) {
                out += SysDl(
                    it.getLong(iId),
                    it.getString(iTitle) ?: "file",
                    it.getString(iMime) ?: "*/*",
                    it.getInt(iStatus),
                    it.getLong(iTotal),
                    it.getLong(iDone)
                )
            }
        }
        return out.sortedByDescending { it.id }
    }

    private fun openSysFile(item: SysDl) {
        if (item.status != android.app.DownloadManager.STATUS_SUCCESSFUL) {
            com.example.streambrowser.util.JcToast.show(this, getString(R.string.dl_running))
            return
        }
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val uri = runCatching { dm.getUriForDownloadedFile(item.id) }.getOrNull()
        if (uri == null) {
            com.example.streambrowser.util.JcToast.show(this, getString(R.string.dl_file_not_found))
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure {
            com.example.streambrowser.util.JcToast.show(this, getString(R.string.common_no_app))
        }
    }

    private fun deleteSysFile(item: SysDl) {
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setMessage(getString(R.string.dl_delete_confirm))
            .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                runCatching { dm.remove(item.id) }
                sysAdapter?.reload { querySystemDownloads() }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /** 시스템 다운로드 어댑터 — 목록 질의 + 진행률/속도 표시 + 열기/삭제 */
    class SysAdapter(
        private val onOpen: (SysDl) -> Unit,
        private val onDelete: (SysDl) -> Unit,
        private val onReloaded: (Int) -> Unit
    ) : RecyclerView.Adapter<SysAdapter.VH>() {

        private var items = listOf<SysDl>()

        /** id → (직전 수신 바이트, 직전 시각) — 속도 계산용 */
        private val prevBytes = HashMap<Long, Pair<Long, Long>>()

        /** id → 직전 계산된 속도(B/s) — reload 시점에 계산해 바인딩에서 읽음 */
        private val speeds = HashMap<Long, Long>()

        fun reload(query: () -> List<SysDl>) {
            items = query()
            val now = SystemClock.elapsedRealtime()
            val live = HashSet<Long>()
            for (it in items) {
                live.add(it.id)
                if (it.status == android.app.DownloadManager.STATUS_RUNNING) {
                    val p = prevBytes[it.id]
                    var spd = 0L
                    if (p != null) {
                        val dt = now - p.second
                        val delta = it.done - p.first
                        // 갱신 주기(500ms)의 3배를 넘기면 멈췄다 재개한 것으로 보고 속도 0
                        if (dt in 1..1500 && delta > 0) spd = delta * 1000 / dt
                    }
                    speeds[it.id] = spd
                    prevBytes[it.id] = it.done to now
                } else {
                    speeds.remove(it.id)
                    prevBytes.remove(it.id)
                }
            }
            prevBytes.keys.retainAll(live)
            speeds.keys.retainAll(live)
            notifyDataSetChanged()
            onReloaded(items.size)
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.sdName)
            val status: TextView = v.findViewById(R.id.sdStatus)
            val progress: android.widget.ProgressBar = v.findViewById(R.id.sdProgress)
            val btnOpen: ImageButton = v.findViewById(R.id.btnOpen)
            val btnDelete: ImageButton = v.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_sys_download, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.name.text = item.title
            when (item.status) {
                android.app.DownloadManager.STATUS_RUNNING -> {
                    val speed = speeds[item.id] ?: 0L
                    val pct = if (item.total > 0) (item.done * 100 / item.total).toInt() else 0
                    holder.status.text = holder.itemView.context.getString(R.string.dl_downloading) + " · " + fmtMb(item.done) + " / " + fmtMb(item.total) +
                            " (" + pct + "%)" + (if (speed > 0) " · " + fmtSpeed(speed) else "")
                    holder.progress.visibility = View.VISIBLE
                    holder.progress.progress = pct
                }
                else -> {
                    holder.progress.visibility = View.GONE
                    holder.status.text = when (item.status) {
                        android.app.DownloadManager.STATUS_SUCCESSFUL ->
                            holder.itemView.context.getString(R.string.dl_done_mb, item.total / 1048576.0)
                        android.app.DownloadManager.STATUS_PENDING -> holder.itemView.context.getString(R.string.dl_waiting)
                        android.app.DownloadManager.STATUS_PAUSED -> holder.itemView.context.getString(R.string.dl_paused)
                        else -> holder.itemView.context.getString(R.string.dl_failed)
                    }
                }
            }
            holder.btnOpen.visibility =
                if (item.status == android.app.DownloadManager.STATUS_SUCCESSFUL) View.VISIBLE else View.GONE
            holder.btnOpen.setOnClickListener { onOpen(item) }
            holder.btnDelete.setOnClickListener { onDelete(item) }
        }

        private fun fmtMb(bytes: Long): String = String.format("%.1f MB", bytes / 1048576.0)

        private fun fmtSpeed(bps: Long): String {
            val kb = bps / 1024.0
            return if (kb >= 1024) String.format("%.1f MB/s", kb / 1024.0)
            else String.format("%.0f KB/s", kb)
        }
    }

    // ---------------- 어댑터 ----------------

    class DlAdapter(
        private val onPlay: (DlItem) -> Unit,
        private val onCancel: (DlItem) -> Unit,
        private val onRename: (DlItem) -> Unit,
        private val onDelete: (DlItem) -> Unit,
        private val onCopy: (DlItem) -> Unit,
        private val onPause: (DlItem) -> Unit,
        private val onResume: (DlItem) -> Unit
    ) : RecyclerView.Adapter<DlAdapter.VH>() {

        private var items = listOf<DlItem>()

        fun submit(list: List<DlItem>) {
            items = list
            notifyDataSetChanged()
        }

        /** 현재 목록 크기 (FilesAdapter 결합용) */
        fun currentCount() = items.size

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.dlThumb)
            val name: TextView = v.findViewById(R.id.dlName)
            val status: TextView = v.findViewById(R.id.dlStatus)
            val progress: ProgressBar = v.findViewById(R.id.dlProgress)
            val btnPlay: ImageButton = v.findViewById(R.id.btnPlay)
            val btnPause: ImageButton = v.findViewById(R.id.btnPause)
            val btnCancel: ImageButton = v.findViewById(R.id.btnCancel)
            val btnCopy: ImageButton = v.findViewById(R.id.btnCopy)
            val btnDelete: ImageButton = v.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.name.text = item.name

            // 썸네일: 완료 파일이 있으면 프레임/이미지 캡처, 없으면 종류 타일
            val f = item.file
            val tile = ThumbLoader.kindTile(item.kind)
            when {
                item.kind == "IMG" && f != null && f.exists() ->
                    ThumbLoader.loadLocalImage(f, holder.thumb, tile)
                item.kind != "IMG" && f != null && f.exists() ->
                    ThumbLoader.loadLocalFrame(f, holder.thumb, tile)
                else -> holder.thumb.setImageBitmap(tile)
            }

            when (item.status) {
                DlStatus.PENDING -> {
                    holder.status.text = holder.itemView.context.getString(R.string.dl_waiting)
                    holder.progress.visibility = View.GONE
                    holder.btnPlay.visibility = View.GONE
                    holder.btnPause.visibility = View.GONE
                    holder.btnCancel.visibility = View.VISIBLE
                    holder.btnCopy.visibility = View.GONE
                    holder.btnDelete.visibility = View.GONE
                }
                DlStatus.RUNNING -> {
                    holder.status.text = DlFormat.progress(item)
                    if (item.totalDurationMs > 0 && item.currentTimeMs > 0) {
                        val p = (item.currentTimeMs * 100 / item.totalDurationMs).coerceIn(0, 100).toInt()
                        holder.progress.visibility = View.VISIBLE
                        holder.progress.progress = p
                    } else {
                        holder.progress.visibility = View.GONE
                    }
                    holder.btnPlay.visibility = View.GONE
                    holder.btnPause.visibility = View.VISIBLE
                    holder.btnCancel.visibility = View.VISIBLE
                    holder.btnCopy.visibility = View.GONE
                    holder.btnDelete.visibility = View.GONE
                }
                DlStatus.PAUSED -> {
                    holder.status.text = holder.itemView.context.getString(R.string.dl_paused) + " · " + DlFormat.progress(item)
                    if (item.totalDurationMs > 0) {
                        val p = (item.currentTimeMs * 100 / item.totalDurationMs).coerceIn(0, 100).toInt()
                        holder.progress.visibility = View.VISIBLE
                        holder.progress.progress = p
                    } else {
                        holder.progress.visibility = View.GONE
                    }
                    // 재생 버튼을 이어받기(재개)로 사용
                    holder.btnPlay.visibility = View.VISIBLE
                    holder.btnPause.visibility = View.GONE
                    holder.btnCancel.visibility = View.GONE
                    holder.btnCopy.visibility = View.VISIBLE
                    holder.btnDelete.visibility = View.VISIBLE
                }
                DlStatus.DONE -> {
                    val sz = (item.file?.length() ?: 0) / 1048576.0
                    holder.status.text = holder.itemView.context.getString(R.string.dl_done_mb, sz)
                    holder.progress.visibility = View.GONE
                    holder.btnPlay.visibility = View.VISIBLE
                    holder.btnPause.visibility = View.GONE
                    holder.btnCancel.visibility = View.GONE
                    holder.btnCopy.visibility = View.VISIBLE
                    holder.btnDelete.visibility = View.VISIBLE
                }
                DlStatus.CANCELED -> {
                    holder.status.text = holder.itemView.context.getString(R.string.dl_cancelled)
                    holder.progress.visibility = View.GONE
                    holder.btnPlay.visibility = View.GONE
                    holder.btnPause.visibility = View.GONE
                    holder.btnCancel.visibility = View.GONE
                    holder.btnCopy.visibility = View.VISIBLE
                    holder.btnDelete.visibility = View.VISIBLE
                }
                DlStatus.FAILED -> {
                    holder.status.text = holder.itemView.context.getString(R.string.dl_failed)
                    holder.progress.visibility = View.GONE
                    holder.btnPlay.visibility = View.GONE
                    holder.btnPause.visibility = View.GONE
                    holder.btnCancel.visibility = View.GONE
                    holder.btnCopy.visibility = View.VISIBLE
                    holder.btnDelete.visibility = View.VISIBLE
                }
            }

            holder.btnPlay.setOnClickListener {
                // 일시 중지 상태면 이어받기, 완료 상태면 파일 재생
                if (item.status == DlStatus.PAUSED) onResume(item) else onPlay(item)
            }
            holder.btnPause.setOnClickListener { onPause(item) }
            holder.btnCancel.setOnClickListener { onCancel(item) }
            holder.btnCopy.setOnClickListener { onCopy(item) }
            holder.btnDelete.setOnClickListener { onDelete(item) }
            holder.itemView.setOnLongClickListener {
                val opts = arrayOf(holder.itemView.context.getString(R.string.common_rename), holder.itemView.context.getString(R.string.btn_delete))
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
