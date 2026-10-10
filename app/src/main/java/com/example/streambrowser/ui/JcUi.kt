package com.example.streambrowser.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
 * 모든 화면에서 탭/즐겨찾기와 동일한 버튼 컨셉을 쓰도록 통일한다.
 * - 투명 배경 + 물결(ripple) 버튼 (bg_btn_ripple 과 동일 컨셉)
 * - 라벨 버튼: 아이콘+텍스트, 13sp
 * - 아이콘 버튼: 40dp, 투명+ripple
 * - 색상: 탭/즐겨찾기 팔레트 (#1A73E8 / #188038 / #D93025 / #5F6368 / #F9AB00)
 */
object JcUi {

    // 탭/즐겨찾기 팔레트
    val blue: Int = Color.parseColor("#1A73E8")
    val green: Int = Color.parseColor("#188038")
    val red: Int = Color.parseColor("#D93025")
    val slate: Int = Color.parseColor("#5F6368")
    val amber: Int = Color.parseColor("#F9AB00")

    fun dp(ctx: Context, n: Int): Int =
        (n * ctx.resources.displayMetrics.density).toInt()

    /**
     * 다이얼로그 입력창 래퍼 — AlertDialog.setView() 에 그대로 넣으면
     * EditText 가 대화상자 가장자리에 딱 붙어 보이므로 좌우 여백(20dp)을 둔 세로 박스로 감쌈.
     */
    fun fieldBox(ctx: Context, vararg fields: View): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val h = dp(ctx, 20)
            setPadding(h, dp(ctx, 4), h, 0)
            fields.forEach { addView(it) }
        }

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

    /** 은은한 색 배경 + 물결 (버튼 느낌이 보이는 ripple) */
    fun rippleBg(ctx: Context, radiusDp: Int = 24, softColor: Int = Color.TRANSPARENT): RippleDrawable {
        val content = GradientDrawable().apply {
            setColor(softColor)
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }
        return RippleDrawable(ColorStateList.valueOf(0x1A000000), content, null)
    }

    /** 라벨 색의 옅은 톤 (alpha 4%) — 버튼 배경을 바탕과 비슷하게 */
    fun soft(color: Int): Int =
        Color.argb(0x0A, Color.red(color), Color.green(color), Color.blue(color))

    /** 플랫 라벨 버튼 — 투명+ripple, 아이콘+텍스트, 13sp */
    fun pill(
        ctx: Context, text: String, color: Int, iconRes: Int = 0,
        onClick: (View) -> Unit
    ): TextView {
        val h = dp(ctx, 10)
        val w = dp(ctx, 12)
        return TextView(ctx).apply {
            this.text = text
            setTextColor(color)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(w, h, w, h)
            minimumWidth = dp(ctx, 72)
            minimumHeight = dp(ctx, 40)
            background = rippleBg(ctx, 6, soft(color))
            if (iconRes != 0) {
                ctx.getDrawable(iconRes)?.mutate()?.let { d ->
                    d.setTint(color)
                    val sz = dp(ctx, 18)
                    d.setBounds(0, 0, sz, sz)
                    compoundDrawablePadding = dp(ctx, 6)
                    setCompoundDrawables(d, null, null, null)
                }
            }
            setOnClickListener(onClick)
        }
    }

    /** 원형 아이콘 버튼 — 40dp, 투명+ripple */
    fun circleIcon(
        ctx: Context, desc: String, iconRes: Int, tint: Int,
        onClick: (View) -> Unit
    ): ImageButton {
        val size = dp(ctx, 40)
        return ImageButton(ctx).apply {
            contentDescription = desc
            setImageResource(iconRes)
            setColorFilter(tint)
            background = rippleBg(ctx, 20)
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
