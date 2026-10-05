package com.example.streambrowser.util

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast

/**
 * 전역 커스텀 토스트: 기본 토스트보다 작은 글씨, 반투명 배경, 화면 아래쪽에 표시
 */
object JcToast {

    fun show(ctx: Context, msgRes: Int, long: Boolean = false) {
        show(ctx, ctx.getString(msgRes), long)
    }

    fun show(ctx: Context, msg: CharSequence, long: Boolean = false) {
        val app = ctx.applicationContext
        val d = app.resources.displayMetrics.density
        val padH = (18 * d).toInt()
        val padV = (9 * d).toInt()
        val view = TextView(app).apply {
            text = msg
            textSize = 12f
            setTextColor(0xE6FFFFFF.toInt())
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(0x99262626.toInt())   // 반투명 배경
                cornerRadius = 18f * d
            }
        }
        val t = Toast(app)
        t.view = view
        // 기본 토스트보다 아래쪽에
        t.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, (110 * d).toInt())
        t.duration = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        t.show()
    }
}
