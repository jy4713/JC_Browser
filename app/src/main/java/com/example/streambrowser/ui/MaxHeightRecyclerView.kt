package com.example.streambrowser.ui

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * wrap_content 와 최대 높이를 동시에 지원하는 RecyclerView.
 * android:maxHeight 는 RecyclerView 에서 미적용이라 측정 스펙을 AT_MOST(max) 로 바꿔 처리.
 * - 내용이 적으면 내용만큼만 (wrap 동작)
 * - 내용이 많으면 최대 높이로 고정 — 측정이 매 프레임 결정적이라 메뉴 펼침/접힘 때
 *   시트 높이가 재측정되며 툭 튀는 문제가 없음
 */
class MaxHeightRecyclerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : RecyclerView(context, attrs) {

    var maxHeightPx: Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (maxHeightPx > 0) {
            MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        } else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, h)
    }
}
