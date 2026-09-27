package com.jel.handgesture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
 * 포인터 모드 표시: 편 손가락 끝마다 색 점 (엄지 주황 · 검지 파랑 · 중지 초록 · 약지 보라 · 새끼 분홍),
 * 머무르는 동안 둘레에 진행 고리, 클릭하면 퍼지는 원, 손을 모으면 큰 '잡기' 점 하나.
 */
class PointerView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val colors = intArrayOf(0xFFFF9F0A.toInt(), 0xFF2F7CF6.toInt(), 0xFF34C759.toInt(), 0xFFAF52DE.toInt(), 0xFFFF375F.toInt())
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = Color.WHITE }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND }
    private val ripple = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = Color.WHITE }
    private val oval = RectF()
    private val loc = IntArray(2)
    private var frame: PointerFrame? = null
    private var rx = 0f
    private var ry = 0f
    private var rt = 0L

    fun update(p: PointerFrame?) {
        frame = p
        invalidate()
    }

    fun ripple(x: Float, y: Float) {
        rx = x; ry = y; rt = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        getLocationOnScreen(loc)
        val ox = -loc[0].toFloat()
        val oy = -loc[1].toFloat()
        val r = 11 * d * Settings.pointerSize
        val p = frame
        if (p != null) {
            if (p.grab) {
                fill.color = 0xCC2F7CF6.toInt()
                c.drawCircle(p.x[0] + ox, p.y[0] + oy, r * 1.7f, fill)
                c.drawCircle(p.x[0] + ox, p.y[0] + oy, r * 1.7f, ring)
            } else {
                for (i in 0 until 5) {
                    if (!p.on[i]) continue
                    val x = p.x[i] + ox
                    val y = p.y[i] + oy
                    fill.color = colors[i]
                    fill.alpha = 215
                    c.drawCircle(x, y, r, fill)
                    c.drawCircle(x, y, r, ring)
                    val g = p.progress[i]
                    if (g > 0.05f) {
                        arc.color = colors[i]
                        oval.set(x - r * 1.9f, y - r * 1.9f, x + r * 1.9f, y + r * 1.9f)
                        c.drawArc(oval, -90f, 360f * g, false, arc)
                    }
                }
            }
        }
        val k = (SystemClock.uptimeMillis() - rt) / 350f
        if (rt > 0L && k <= 1f) {
            ripple.alpha = ((1f - k) * 255).toInt()
            c.drawCircle(rx + ox, ry + oy, r * (1.2f + 3f * k), ripple)
            postInvalidateOnAnimation()
        }
    }
}
