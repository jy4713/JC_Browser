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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.streambrowser.MainActivity
import com.example.streambrowser.R
import com.example.streambrowser.browser.DetectedVideo
import com.example.streambrowser.browser.VideoStore

/**
 * 감지된 미디어 목록 어댑터: 영상(HLS/MP4/...) + 이미지(IMG)
 * - 미리보기: 외부 플레이어/뷰어로 확인
 * - 받기: 이름 + 확장자 지정 다이얼로그 (기본값 자동 추천, 수정 가능)
 */
class VideoAdapter : RecyclerView.Adapter<VideoAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val info: TextView = v.findViewById(R.id.txtVideoInfo)
        val btnDownload: Button = v.findViewById(R.id.btnDownload)
        val btnPreview: ImageButton = v.findViewById(R.id.btnPreview)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))

    override fun getItemCount(): Int = VideoStore.videos.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val v = VideoStore.videos[position]
        holder.info.text = "[${v.kind}] ${v.url}"

        if (v.url.startsWith("blob:")) {
            holder.btnDownload.text = "불가"
            holder.btnDownload.isEnabled = false
            holder.btnPreview.visibility = View.GONE
            holder.btnDownload.setOnClickListener(null)
            holder.btnPreview.setOnClickListener(null)
            return
        }

        val isImage = v.kind == "IMG"

        // 미리보기: 외부 앱으로 재생/표시해 어떤 미디어인지 확인
        holder.btnPreview.visibility = View.VISIBLE
        holder.btnPreview.setOnClickListener {
            val mime = if (isImage) "image/*" else "video/*"
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(v.url), mime)
            }
            runCatching { holder.btnPreview.context.startActivity(i) }.onFailure {
                Toast.makeText(
                    holder.btnPreview.context,
                    if (isImage) "이미지를 볼 앱이 없습니다." else "재생 가능한 앱이 없습니다 (VLC 등 설치).",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        holder.btnDownload.text = "받기"
        holder.btnDownload.isEnabled = true
        holder.btnDownload.setOnClickListener {
            val ctx = holder.btnDownload.context
            showNameExtDialog(ctx, v, isImage) { name, ext ->
                if (isImage) {
                    ImageDownloader.download(ctx, v.url, v.page, name, ext)
                } else {
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
                }
                holder.btnDownload.text = "시작됨"
                holder.btnDownload.isEnabled = false
            }
        }
    }

    /** 이름 + 확장자 입력 다이얼로그. 기본값 자동 추천, 둘 다 수정 가능 */
    private fun showNameExtDialog(
        ctx: Context,
        v: DetectedVideo,
        isImage: Boolean,
        onOk: (name: String, ext: String) -> Unit
    ) {
        // 기본 이름: 탭 제목 > 페이지 제목 > 호스트
        val suggested = ((ctx as? MainActivity)?.currentTabTitle() ?: "")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            .ifEmpty { (if (isImage) "image_" else "video_") + System.currentTimeMillis() }
        // 기본 확장자: URL 경로의 확장자 > 종류별 기본값
        val defaultExt = defaultExt(v, isImage)

        val input = EditText(ctx).apply {
            setText(suggested)
            setSingleLine()
            hint = "파일 이름"
        }
        val extInput = EditText(ctx).apply {
            setText(defaultExt)
            setSingleLine()
            hint = "확장자 (예: ${if (isImage) "jpg, png" else "mp4, mkv"})"
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * ctx.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
            addView(extInput)
        }
        AlertDialog.Builder(ctx)
            .setTitle(if (isImage) "이미지 다운로드 (이름/확장자 지정)" else "다운로드 (이름/확장자 지정)")
            .setView(box)
            .setPositiveButton("다운로드") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { suggested }
                val ext = extInput.text.toString().trim()
                    .removePrefix(".").ifEmpty { defaultExt }
                onOk(name, ext)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun defaultExt(v: DetectedVideo, isImage: Boolean): String {
        val fromUrl = Regex("\\.([A-Za-z0-9]{2,5})(?:\\?.*)?$")
            .find(Uri.parse(v.url).path ?: "")
            ?.groupValues?.get(1)?.lowercase()
        return when {
            isImage -> when (fromUrl) {
                "jpg", "jpeg", "png", "gif", "webp", "bmp" -> fromUrl
                else -> "jpg"
            }
            v.url.startsWith("blob:") -> "mp4"
            v.kind == "HLS" -> "mp4"
            else -> fromUrl ?: "mp4"
        }
    }
}
