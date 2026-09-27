package com.jel.handgesture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.View

/** 화면 상단 알약 모양 표시: 실행된 동작 이름, 유지 중이면 진행 막대 */
class HudView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6202124.toInt() }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15 * d; textAlign = Paint.Align.CENTER }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4C8DFF.toInt() }
    private val r = RectF()
    var text: String? = null
    var progress = -1f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val tw = txt.measureText(text ?: "")
        setMeasuredDimension((tw + 40 * d).toInt().coerceAtLeast((120 * d).toInt()), (44 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val t = text ?: return
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(r, height / 2f, height / 2f, bg)
        c.drawText(t, width / 2f, height / 2f + txt.textSize / 3f, txt)
        if (progress >= 0f) {
            val m = 18 * d
            val w = (width - 2 * m) * progress
            r.set(m, height - 7 * d, m + w, height - 4 * d)
            c.drawRoundRect(r, 2 * d, 2 * d, bar)
        }
    }
}

/**
 * 파란 선택 띠: 키보드 방향키로 이동할 때처럼 선택된 요소를 파란 테두리로 감싼다.
 * 다음 요소로 옮길 때 140ms 동안 미끄러지듯 이동한다.
 */
class FocusView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4 * d; color = 0xFF2F7CF6.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x332F7CF6 }
    private val from = RectF()
    private val to = RectF()
    private val cur = RectF()
    private var animStart = 0L
    private var has = false
    private val loc = IntArray(2)

    fun moveTo(screen: Rect) {
        getLocationOnScreen(loc)
        val target = RectF(screen.left - loc[0] - 4 * d, screen.top - loc[1] - 4 * d, screen.right - loc[0] + 4 * d, screen.bottom - loc[1] + 4 * d)
        if (!has) { from.set(target); has = true } else from.set(cur)
        to.set(target)
        animStart = SystemClock.uptimeMillis()
        visibility = VISIBLE
        invalidate()
    }

    fun clear() { has = false; visibility = GONE }

    override fun onDraw(c: Canvas) {
        if (!has) return
        val k = ((SystemClock.uptimeMillis() - animStart) / 140f).coerceIn(0f, 1f)
        val e = 1f - (1f - k) * (1f - k)
        cur.set(
            from.left + (to.left - from.left) * e, from.top + (to.top - from.top) * e,
            from.right + (to.right - from.right) * e, from.bottom + (to.bottom - from.bottom) * e,
        )
        c.drawRoundRect(cur, 10 * d, 10 * d, fill)
        c.drawRoundRect(cur, 10 * d, 10 * d, stroke)
        if (k < 1f) postInvalidateOnAnimation()
    }
}
