package com.jel.eyemouse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 정밀 홍채 검출 결과 (좌표는 원본 프레임 픽셀) */
class RefineOut(val cx: Float, val cy: Float, val conf: Float, val bright: Float, val sharp: Float)

/**
 * 고해상도 눈 크롭에서 홍채 중심을 픽셀 이하 정밀도로 찾는다.
 * 방법: 홍채 경계(어두운 홍채 → 밝은 흰자) 기울기들이 한 점에서 퍼져 나가는 중심을 찾는
 * '기울기 방향 투표' + 어두움 가중. 눈꺼풀에 가린 부분은 투표에서 뺀다.
 */
object EyeCropRefiner {
    @Volatile var debugCrop: Bitmap? = null
    private var debugCounter = 0

    fun refine(
        full: Bitmap,
        outerX: Float, outerY: Float, innerX: Float, innerY: Float,
        lidTop: Float, lidBottom: Float,
        initX: Float, initY: Float, radius: Float,
        debug: Boolean,
    ): RefineOut? {
        if (radius < 3f) return null
        val eyeW = hypot(innerX - outerX, innerY - outerY)
        val halfW = max(eyeW * 0.7f, radius * 2.2f)
        val halfH = max(radius * 1.8f, (lidBottom - lidTop) * 0.9f)
        val x0 = (initX - halfW).toInt().coerceIn(0, full.width - 1)
        val y0 = (initY - halfH).toInt().coerceIn(0, full.height - 1)
        val x1 = (initX + halfW).toInt().coerceIn(0, full.width)
        val y1 = (initY + halfH).toInt().coerceIn(0, full.height)
        val w = x1 - x0
        val h = y1 - y0
        if (w < 10 || h < 10) return null

        val px = IntArray(w * h)
        full.getPixels(px, 0, w, x0, y0, w, h)
        val g = FloatArray(w * h)
        var sum = 0f
        for (i in px.indices) {
            val c = px[i]
            val l = 0.299f * ((c shr 16) and 0xff) + 0.587f * ((c shr 8) and 0xff) + 0.114f * (c and 0xff)
            g[i] = l; sum += l
        }
        val bright = sum / g.size

        // 3×3 평활 (어두움 가중용) + 라플라시안 분산 (선명도)
        val sm = FloatArray(w * h)
        var lapSum = 0.0
        var lapSq = 0.0
        var lapN = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            var s = 0f
            for (dy in -1..1) for (dx in -1..1) s += g[i + dy * w + dx]
            sm[i] = s / 9f
            val lap = (4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w]).toDouble()
            lapSum += lap; lapSq += lap * lap; lapN++
        }
        val lapMean = lapSum / max(lapN, 1)
        val sharp = (lapSq / max(lapN, 1) - lapMean * lapMean).toFloat()

        // 홍채 경계 후보: 기울기가 크고, 예상 반지름 근처 고리 안, 눈꺼풀 사이
        val cx0 = initX - x0
        val cy0 = initY - y0
        val ptsX = FloatArray(1200)
        val ptsY = FloatArray(1200)
        val gxs = FloatArray(1200)
        val gys = FloatArray(1200)
        var n = 0
        var magSum = 0f
        var magCnt = 0
        val mags = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gxv = (g[i - w + 1] + 2 * g[i + 1] + g[i + w + 1]) - (g[i - w - 1] + 2 * g[i - 1] + g[i + w - 1])
            val gyv = (g[i + w - 1] + 2 * g[i + w] + g[i + w + 1]) - (g[i - w - 1] + 2 * g[i - w] + g[i - w + 1])
            val m = sqrt(gxv * gxv + gyv * gyv)
            mags[i] = m; magSum += m; magCnt++
        }
        val thr = 1.5f * magSum / max(magCnt, 1)
        val topLocal = lidTop - y0 + 1.5f
        val botLocal = lidBottom - y0 - 1.5f
        loop@ for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val m = mags[i]
            if (m < thr) continue
            if (y < topLocal || y > botLocal) continue
            val d = hypot(x - cx0, y - cy0)
            if (abs(d - radius) > 0.45f * radius) continue
            val gxv = (g[i - w + 1] + 2 * g[i + 1] + g[i + w + 1]) - (g[i - w - 1] + 2 * g[i - 1] + g[i + w - 1])
            val gyv = (g[i + w - 1] + 2 * g[i + w] + g[i + w + 1]) - (g[i - w - 1] + 2 * g[i - w] + g[i - w + 1])
            ptsX[n] = x.toFloat(); ptsY[n] = y.toFloat(); gxs[n] = gxv / m; gys[n] = gyv / m
            if (++n >= ptsX.size) break@loop
        }
        if (n < 12) return RefineOut(initX, initY, 0f, bright, sharp)

        // 후보 중심 격자 탐색 (초기값 주변 ±0.5r)
        val span = 0.5f * radius
        val step = max(0.5f, radius / 20f)
        var bestS = -1f
        var bestX = cx0
        var bestY = cy0
        var cy = cy0 - span
        while (cy <= cy0 + span) {
            var cx = cx0 - span
            while (cx <= cx0 + span) {
                var s = 0f
                for (k in 0 until n) {
                    val dx = ptsX[k] - cx
                    val dy = ptsY[k] - cy
                    val dl = sqrt(dx * dx + dy * dy)
                    if (dl < 1e-3f) continue
                    val dot = (dx * gxs[k] + dy * gys[k]) / dl
                    if (dot > 0f) s += dot * dot
                }
                val ix = cx.toInt().coerceIn(0, w - 1)
                val iy = cy.toInt().coerceIn(0, h - 1)
                val dark = (255f - sm[iy * w + ix]) / 255f
                s *= (0.5f + dark)
                if (s > bestS) { bestS = s; bestX = cx; bestY = cy }
                cx += step
            }
            cy += step
        }
        val meanScore = bestS / n / 1.5f
        val coverage = n / (6.28f * radius)
        val conf = (min(1f, meanScore * 1.2f) * min(1f, coverage * 1.5f)).coerceIn(0f, 1f)
        val outX = bestX + x0
        val outY = bestY + y0

        if (debug && ++debugCounter % 6 == 0) {
            val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
            val cv = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.GREEN }
            cv.drawCircle(bestX, bestY, radius, p)
            p.color = Color.RED
            cv.drawCircle(cx0, cy0, 1.5f, p)
            p.color = Color.GREEN
            cv.drawLine(bestX - 3, bestY, bestX + 3, bestY, p)
            cv.drawLine(bestX, bestY - 3, bestX, bestY + 3, p)
            debugCrop = bmp
        }
        return RefineOut(outX, outY, conf, bright, sharp)
    }
}
