package com.example.streambrowser.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ProgressBar

/**
 * 아래로 당겨서 새로고침 (Chrome 스타일 pull-to-refresh).
 * - 자식 웹뷰가 스크롤 최상단일 때만 제스처를 가로챈다
 * - 당긴 거리에 비례해 상단 인디케이터 표시, 임계치 이상 놓으면 onRefresh
 */
class PullRefreshLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var onRefresh: (() -> Unit)? = null

    /** 현재 웹뷰가 스크롤 최상단인지 (MainActivity가 주입). false면 인터셉트 안 함 */
    var atTop: (() -> Boolean)? = null

    /** 상단 진행 인디케이터 (가로 ProgressBar) */
    var indicator: ProgressBar? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private val refreshDistance = 110 * density

    private var startY = 0f
    private var pulling = false
    private var lastDy = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = ev.y
                pulling = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pulling && ev.y - startY > slop && atTop?.invoke() == true) {
                    pulling = true
                    showIndicator(0f)
                }
                if (pulling) return true
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                lastDy = (ev.y - startY).coerceAtLeast(0f)
                showIndicator((lastDy / refreshDistance).coerceIn(0f, 1f))
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (pulling && lastDy >= refreshDistance) onRefresh?.invoke()
                reset()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                reset()
                return true
            }
        }
        return true
    }

    private fun showIndicator(fraction: Float) {
        val iv = indicator ?: return
        iv.visibility = VISIBLE
        iv.alpha = 0.3f + 0.7f * fraction
        iv.progress = (fraction * 100).toInt()
    }

    private fun reset() {
        pulling = false
        lastDy = 0f
        indicator?.visibility = GONE
    }
}
