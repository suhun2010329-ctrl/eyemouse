package com.jel.eyemouse

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 사용 중 드리프트 보정: 버튼 스냅이 일어날 때마다
 * '예측 위치 → 버튼 중심' 차이만큼 화면 전체 오프셋(mm)을 조금씩 옮긴다.
 * 자세(거리·머리 각도)가 크게 바뀌면 0으로 되돌린다.
 */
class DriftCorrector {
    @Volatile var bx = 0f
        private set
    @Volatile var by = 0f
        private set
    private var refZ = Float.NaN
    private var refHx = 0f
    private var refHy = 0f

    fun reset() { bx = 0f; by = 0f; refZ = Float.NaN }

    fun onFrame(v: FloatArray) {
        if (refZ.isNaN()) return
        if (abs(v[G.Z] - refZ) > 60f || abs(v[G.HX] - refHx) > 0.08f || abs(v[G.HY] - refHy) > 0.08f) reset()
    }

    /** pred: 보정이 적용된 예측(mm), target: 스냅된 버튼 중심(mm) */
    fun onSnap(predX: Float, predY: Float, targetX: Float, targetY: Float, v: FloatArray) {
        val ex = targetX - predX
        val ey = targetY - predY
        if (hypot(ex, ey) > 15f) return
        bx = (bx + 0.3f * ex).coerceIn(-12f, 12f)
        by = (by + 0.3f * ey).coerceIn(-12f, 12f)
        refZ = v[G.Z]; refHx = v[G.HX]; refHy = v[G.HY]
    }
}
