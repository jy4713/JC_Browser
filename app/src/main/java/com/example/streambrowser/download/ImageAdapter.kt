package com.example.streambrowser.download

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.example.streambrowser.R
import com.example.streambrowser.browser.DetectedVideo
import com.example.streambrowser.browser.TabMedia
import com.example.streambrowser.browser.VideoStore

/** 이미지 그리드 어댑터: 탭으로 멀티 선택 → 선택 항목 일괄 다운로드 */
class ImageAdapter(
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<ImageAdapter.VH>() {

    private val selected = LinkedHashSet<String>()

    /** 현재 탭의 미디어 목록 제공자 (MainActivity가 주입) */
    var provider: (() -> TabMedia?)? = null
    private fun media() = provider?.invoke()

    fun selectedItems(): List<DetectedVideo> =
        media()?.images?.filter { selected.contains(it.url) } ?: emptyList()

    fun selectAll() {
        selected.clear()
        media()?.images?.forEach { selected.add(it.url) }
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    fun clearSelection() {
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.imgThumb)
        val scrim: View = v.findViewById(R.id.imgScrim)
        val check: ImageView = v.findViewById(R.id.imgCheck)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_image, parent, false))

    override fun getItemCount(): Int = media()?.images?.size ?: 0

    override fun onBindViewHolder(holder: VH, position: Int) {
        val v = media()?.images?.get(position) ?: return
        ThumbLoader.load(v.url, holder.thumb)
        val isSel = selected.contains(v.url)
        holder.scrim.visibility = if (isSel) View.VISIBLE else View.GONE
        holder.check.visibility = if (isSel) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener {
            if (isSel) selected.remove(v.url) else selected.add(v.url)
            notifyItemChanged(position)
            onSelectionChanged(selected.size)
        }
    }
}
