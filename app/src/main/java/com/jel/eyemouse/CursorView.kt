package com.jel.eyemouse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View

class CursorView(ctx: Context) : View(ctx) {
    enum class State { NORMAL, CLOSED, PAUSE_READY, PAUSED, LOST }

    var state = State.NORMAL
    var progress = 0f

    private val d = resources.displayMetrics.density
    private var flashUntil = 0L
    private val oval = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * d; color = Color.WHITE
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4 * d
        strokeCap = Paint.Cap.ROUND; color = 0xFF34C759.toInt()
    }

    fun flash() {
        flashUntil = SystemClock.uptimeMillis() + 250
        invalidate()
        postDelayed({ invalidate() }, 270)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        fill.color = when (state) {
            State.NORMAL -> 0xCC2F7CF6.toInt()
            State.CLOSED -> 0xCCFF9500.toInt()
            State.PAUSE_READY -> 0xCCAF52DE.toInt()
            State.PAUSED -> 0x99888888.toInt()
            State.LOST -> 0x55888888
        }
        val r = 10 * d
        c.drawCircle(cx, cy, r, fill)
        c.drawCircle(cx, cy, r, outline)
        if (progress > 0f) {
            val rr = 19 * d
            oval.set(cx - rr, cy - rr, cx + rr, cy + rr)
            c.drawArc(oval, -90f, 360f * progress, false, ring)
        }
        if (SystemClock.uptimeMillis() < flashUntil) c.drawCircle(cx, cy, 24 * d, ring)
    }
}
