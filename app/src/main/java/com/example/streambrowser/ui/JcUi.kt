package com.example.streambrowser.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * JC Browser 공통 UI 스타일 키트.
 *
 * 모든 화면에서 동일한 버튼/색감/폰트를 쓰도록 통일한다.
 * - 파스텔 팔레트 (차분한 톤, 다크모드에서도 튀지 않음)
 * - pill: 높이 40dp 고정 둥근 버튼
 * - circleIcon: 40dp 원형 아이콘 버튼
 * - card: 모서리 16dp 부유 카드
 */
object JcUi {

    // 파스텔 팔레트
    val blue: Int = Color.parseColor("#7C9BD4")
    val green: Int = Color.parseColor("#6FA88C")
    val red: Int = Color.parseColor("#D08484")
    val slate: Int = Color.parseColor("#8E99A8")
    val amber: Int = Color.parseColor("#D4A574")

    fun dp(ctx: Context, n: Int): Int =
        (n * ctx.resources.displayMetrics.density).toInt()

    fun themedColor(ctx: Context, attr: Int, fallback: Int): Int {
        val ta = ctx.theme.obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, fallback)
        ta.recycle()
        return c
    }

    fun textPrimary(ctx: Context): Int =
        themedColor(ctx, android.R.attr.textColorPrimary, Color.BLACK)

    fun textSecondary(ctx: Context): Int =
        themedColor(ctx, android.R.attr.textColorSecondary, Color.GRAY)

    fun cardBg(ctx: Context): Int =
        themedColor(ctx, android.R.attr.colorBackgroundFloating, Color.WHITE)

    /** 둥근 알약 버튼 — 높이 40dp, 라벨 14sp, 흰색 라벨 */
    fun pill(ctx: Context, text: String, bg: Int, onClick: (View) -> Unit): TextView {
        val h = dp(ctx, 9)
        val w = dp(ctx, 18)
        return TextView(ctx).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(w, h, w, h)
            minimumWidth = dp(ctx, 72)
            minimumHeight = dp(ctx, 40)
            background = GradientDrawable().apply {
                setColor(bg)
                cornerRadius = dp(ctx, 20).toFloat()
            }
            setOnClickListener(onClick)
        }
    }

    /** 원형 아이콘 버튼 — 40dp */
    fun circleIcon(
        ctx: Context, desc: String, iconRes: Int, tint: Int,
        onClick: (View) -> Unit
    ): ImageButton {
        val size = dp(ctx, 40)
        return ImageButton(ctx).apply {
            contentDescription = desc
            setImageResource(iconRes)
            setColorFilter(tint)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x14000000)
            }
            setOnClickListener(onClick)
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    /** 부유 카드 컨테이너 — 모서리 16dp, 낮은 그림자 */
    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val pd = dp(ctx, 18)
        setPadding(pd, pd, pd, pd)
        background = GradientDrawable().apply {
            setColor(cardBg(ctx))
            cornerRadius = dp(ctx, 16).toFloat()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            elevation = dp(ctx, 2).toFloat()
        }
    }

    /** 공통 제목 텍스트 (18sp bold) */
    fun title(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(textPrimary(ctx))
    }

    /** 공통 보조 라벨 (13sp) */
    fun label(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTextColor(textSecondary(ctx))
    }

    /** 카드 납작 버튼 행 여백 유틸 */
    fun rowMargins(ctx: Context, view: View, start: Int = 0, top: Int = 0, end: Int = 0, bottom: Int = 0) {
        val lp = view.layoutParams as? LinearLayout.LayoutParams ?: return
        lp.marginStart = dp(ctx, start)
        lp.topMargin = dp(ctx, top)
        lp.marginEnd = dp(ctx, end)
        lp.bottomMargin = dp(ctx, bottom)
        view.layoutParams = lp
    }

    /** 카드 전체 너비 레이아웃 파라미터 */
    fun matchWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
