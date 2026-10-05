package com.example.streambrowser.download

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.streambrowser.MainActivity
import com.example.streambrowser.R
import com.example.streambrowser.browser.DetectedVideo
import com.example.streambrowser.browser.TabMedia
import com.example.streambrowser.browser.VideoStore
import com.example.streambrowser.ui.PlayerActivity

/**
 * 감지된 동영상 목록 어댑터.
 * - 썸네일(poster > 페이지 스냅샷), 낭부 플레이어 재생, 이름/확장자 지정 다운로드
 * - BLOB 등 불가 항목은 기본 숨김 (상단 토글로 표시 가능)
 * - 햄버거 메뉴: 새 탭/시크릿 탭/링크 복사/공유
 */
class VideoAdapter : RecyclerView.Adapter<VideoAdapter.VH>() {

    /** 다운로드 불가 항목 표시 여부 */
    var showBlocked = false

    /** 현재 탭의 미디어 목록 제공자 (MainActivity가 주입) */
    var provider: (() -> TabMedia?)? = null
    private fun media() = provider?.invoke()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.videoThumb)
        val kind: TextView = v.findViewById(R.id.txtVideoKind)
        val info: TextView = v.findViewById(R.id.txtVideoInfo)
        val btnDownload: Button = v.findViewById(R.id.btnDownload)
        val btnPreview: ImageButton = v.findViewById(R.id.btnPreview)
        val btnMenu: ImageButton = v.findViewById(R.id.btnItemMenu)
    }

    /** 현재 표시 대상 목록 (불가 항목 제외 규칙 적용) */
    private fun items(): List<DetectedVideo> =
        (media()?.videos ?: emptyList()).filter { showBlocked || !it.unavailable }

    fun blockedCount(): Int = media()?.videos?.count { it.unavailable } ?: 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))

    override fun getItemCount(): Int = items().size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val v = items()[position]
        holder.kind.text = v.kind
        holder.info.text = v.url

        // 썸네일: poster URL 로드, 실패/없으면 페이지 스냅샷
        val poster = media()?.posterByUrl?.get(v.url)
        if (poster != null) {
            ThumbLoader.load(poster, holder.thumb, ThumbLoader.PageSnapshot.bitmap)
        } else {
            holder.thumb.setImageBitmap(ThumbLoader.PageSnapshot.bitmap)
        }

        if (v.unavailable) {
            holder.btnDownload.text = holder.itemView.context.getString(R.string.blob_unavailable)
            holder.btnDownload.isEnabled = false
        } else {
            // 동일 URL 다운로드 상태 반영: 진행 중이면 버튼이 진행률로 변경
            val dl = DownloadStore.byUrl(v.url)
            when (dl?.status) {
                DlStatus.PENDING, DlStatus.RUNNING -> {
                    holder.btnDownload.text = DlFormat.progress(dl)
                    holder.btnDownload.textSize = 11f
                    holder.btnDownload.isEnabled = true
                    holder.btnDownload.setOnClickListener {
                        com.example.streambrowser.util.JcToast.show(holder.itemView.context, R.string.already_downloading)
                    }
                }
                DlStatus.PAUSED -> {
                    holder.btnDownload.text = holder.itemView.context.getString(R.string.dl_paused)
                    holder.btnDownload.textSize = 12f
                    holder.btnDownload.isEnabled = true
                    holder.btnDownload.setOnClickListener {
                        val ctx = holder.itemView.context
                        ctx.startActivity(Intent(ctx, com.example.streambrowser.ui.DownloadsActivity::class.java))
                    }
                }
                DlStatus.DONE -> {
                    holder.btnDownload.text = holder.itemView.context.getString(R.string.btn_done)
                    holder.btnDownload.textSize = 12f
                    holder.btnDownload.isEnabled = true
                    holder.btnDownload.setOnClickListener {
                        val ctx = holder.itemView.context
                        ctx.startActivity(Intent(ctx, com.example.streambrowser.ui.DownloadsActivity::class.java))
                    }
                }
                else -> {
                    holder.btnDownload.text = holder.itemView.context.getString(R.string.item_download)
                    holder.btnDownload.textSize = 12f
                    holder.btnDownload.isEnabled = true
                    holder.btnDownload.setOnClickListener {
                        showNameExtDialog(holder.itemView.context, v)
                    }
                }
            }
        }

        // 재생: 낭부 HTML 플레이어 팝업 (외부 앱 미사용)
        holder.btnPreview.setOnClickListener {
            val ctx = holder.itemView.context
            ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_URL, v.url)
                    .putExtra(PlayerActivity.EXTRA_PAGE, v.page)
            )
        }

        // 햄버거 메뉴
        holder.btnMenu.setOnClickListener { anchor ->
            val ctx = anchor.context
            val pm = PopupMenu(ctx, anchor)
            pm.menu.add(0, 1, 0, ctx.getString(R.string.item_open_tab))
            pm.menu.add(0, 2, 1, ctx.getString(R.string.item_open_incognito))
            pm.menu.add(0, 3, 2, ctx.getString(R.string.item_copy_link))
            pm.menu.add(0, 4, 3, ctx.getString(R.string.item_share))
            pm.menu.add(0, 5, 4, ctx.getString(R.string.item_info))
            pm.setOnMenuItemClickListener { mi ->
                when (mi.itemId) {
                    1 -> (ctx as? MainActivity)?.openInNewTab(v.url, false)
                    2 -> (ctx as? MainActivity)?.openInNewTab(v.url, true)
                    3 -> (ctx as? MainActivity)?.copyTextPublic(v.url, ctx.getString(R.string.link_copied))
                    4 -> {
                        val i = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, v.url)
                        }
                        runCatching { ctx.startActivity(Intent.createChooser(i, null)) }
                    }
                    5 -> showInfoDialog(ctx, v)
                }
                true
            }
            pm.show()
        }
    }

    /** 동영상 상세 정보: 종류/풀 링크/페이지 표시 + 링크 복사 (여러 링크 구분용) */
    private fun showInfoDialog(ctx: Context, v: DetectedVideo) {
        val density = ctx.resources.displayMetrics.density
        fun dp(x: Int) = (x * density).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, dp(10), pad, 0)
        }
        fun row(label: String, value: String) {
            val l = TextView(ctx).apply {
                text = label
                setTextColor(0xFF5F6368.toInt())
                textSize = 12f
            }
            val t = TextView(ctx).apply {
                text = value
                setTextColor(0xFF202124.toInt())
                textSize = 14f
                setTextIsSelectable(true)
            }
            box.addView(l)
            box.addView(t)
            val sp = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(12))
            }
            box.addView(sp)
        }
        row(ctx.getString(R.string.info_type), v.kind)
        row(ctx.getString(R.string.info_link), v.url)
        if (v.page.isNotEmpty()) row(ctx.getString(R.string.info_page), v.page)

        AlertDialog.Builder(ctx)
            .setTitle(R.string.dlg_video_info)
            .setView(box)
            .setPositiveButton(android.R.string.copy) { _, _ ->
                (ctx as? MainActivity)?.copyTextPublic(v.url, ctx.getString(R.string.link_copied))
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** 이름 + 확장자 입력 다이얼로그. 기본값 자동 추천, 둘 다 수정 가능 */
    private fun showNameExtDialog(ctx: Context, v: DetectedVideo) {
        val suggested = ((ctx as? MainActivity)?.currentTabTitle() ?: "")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            .ifEmpty { "video_" + System.currentTimeMillis() }
        val defaultExt = defaultExt(v)

        val input = EditText(ctx).apply {
            setText(suggested)
            setSingleLine()
            hint = ctx.getString(R.string.hint_filename)
        }
        val extInput = EditText(ctx).apply {
            setText(defaultExt)
            setSingleLine()
            hint = ctx.getString(R.string.hint_ext)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * ctx.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
            addView(extInput)
        }
        AlertDialog.Builder(ctx)
            .setTitle(ctx.getString(R.string.dlg_name_ext_title))
            .setView(box)
            .setPositiveButton(ctx.getString(R.string.btn_download)) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { suggested }
                val ext = extInput.text.toString().trim()
                    .removePrefix(".").ifEmpty { defaultExt }
                startDownload(ctx, v, name, ext)
            }
            .setNegativeButton(ctx.getString(R.string.btn_cancel), null)
            .show()
    }

    private fun startDownload(ctx: Context, v: DetectedVideo, name: String, ext: String) {
        val id = System.currentTimeMillis()
        DownloadStore.upsert(DlItem(id, v.url, v.page, v.kind, name, ext))
        val i = Intent(ctx, VideoDownloadService::class.java).apply {
            putExtra(VideoDownloadService.EXTRA_ID, id)
            putExtra(VideoDownloadService.EXTRA_URL, v.url)
            putExtra(VideoDownloadService.EXTRA_PAGE, v.page)
            putExtra(VideoDownloadService.EXTRA_KIND, v.kind)
            putExtra(VideoDownloadService.EXTRA_NAME, name)
            putExtra(VideoDownloadService.EXTRA_EXT, ext)
        }
        ctx.startForegroundService(i)
        com.example.streambrowser.util.JcToast.show(ctx, ctx.getString(R.string.video_dl_started))
        notifyDataSetChanged()
    }

    private fun defaultExt(v: DetectedVideo): String {
        val fromUrl = Regex("\\.([A-Za-z0-9]{2,5})(?:\\?.*)?$")
            .find(Uri.parse(v.url).path ?: "")
            ?.groupValues?.get(1)?.lowercase()
        return when {
            v.url.startsWith("blob:") -> "mp4"
            v.kind == "HLS" -> "mp4"
            else -> fromUrl ?: "mp4"
        }
    }
}
