package com.jel.eyemouse

import android.graphics.PointF
import android.os.Build
import android.util.DisplayMetrics
import kotlin.math.max
import kotlin.math.min

/**
 * 화면 물리 크기(mm) 계산.
 * 시선 모델은 픽셀이 아니라 mm 단위로 학습해서, 해상도 설정(FHD+/HD+)이 바뀌어도 그대로 동작한다.
 */
object DisplayProfile {
    /** Galaxy Z Flip7 메인 디스플레이: 6.9", 2520×1080, 약 397ppi, 21:9, 전면 카메라 상단 중앙 */
    private const val FLIP7_SHORT_MM = 69.1f   // 1080 / 397 * 25.4
    private const val FLIP7_LONG_MM = 161.2f   // 2520 / 397 * 25.4

    val isZFlip7: Boolean = Build.MODEL.uppercase().startsWith("SM-F766")

    val name: String =
        if (isZFlip7) "Galaxy Z Flip7 (6.9\", 69×161mm)" else "${Build.MANUFACTURER} ${Build.MODEL}"

    /** mm당 픽셀 수 (x, y) */
    fun pxPerMm(w: Int, h: Int, dm: DisplayMetrics): PointF {
        val shortPx = min(w, h).toFloat()
        val longPx = max(w, h).toFloat()
        // 커버 화면(1048×948)이 아니라 21:9 메인 화면일 때만 Flip7 실측값 사용
        if (isZFlip7 && longPx / shortPx > 2.0f) {
            val s = shortPx / FLIP7_SHORT_MM
            val l = longPx / FLIP7_LONG_MM
            return if (w <= h) PointF(s, l) else PointF(l, s)
        }
        return PointF(dm.xdpi / 25.4f, dm.ydpi / 25.4f)
    }
}
