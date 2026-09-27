package com.jel.eyemouse

import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.sqrt

/**
 * 한 프레임에서 뽑은 특징값.
 * headX/headY: 두 눈 바깥쪽 끝을 잇는 선 기준 코끝 위치(눈 간 거리로 정규화) → 머리 회전량
 * gazeX/gazeY: 눈 모서리 중심 대비 홍채 위치(눈 폭으로 정규화, 두 눈 평균) → 시선 방향
 */
data class FaceFeatures(
    val t: Long,
    val headX: Float,
    val headY: Float,
    val gazeX: Float,
    val gazeY: Float,
    val blinkL: Float,
    val blinkR: Float,
)

private data class V(val x: Float, val y: Float) {
    operator fun plus(o: V) = V(x + o.x, y + o.y)
    operator fun minus(o: V) = V(x - o.x, y - o.y)
    operator fun times(s: Float) = V(x * s, y * s)
    operator fun div(s: Float) = V(x / s, y / s)
    fun dot(o: V) = x * o.x + y * o.y
    fun len() = sqrt(x * x + y * y)
}

object FeatureExtractor {
    // MediaPipe Face Mesh 인덱스
    private const val EYE_A_OUTER = 33
    private const val EYE_A_INNER = 133
    private const val EYE_B_OUTER = 263
    private const val EYE_B_INNER = 362
    private const val IRIS_1 = 468
    private const val IRIS_2 = 473
    private const val NOSE_TIP = 1

    fun extract(
        lm: List<NormalizedLandmark>,
        blend: List<Category>?,
        imgW: Int,
        imgH: Int,
        t: Long,
    ): FaceFeatures? {
        if (lm.size < 478) return null
        // 정규화 좌표 → 픽셀 비율 (가로/세로 비율 왜곡 제거)
        fun p(i: Int) = V(lm[i].x() * imgW, lm[i].y() * imgH)

        val oA = p(EYE_A_OUTER); val iA = p(EYE_A_INNER)
        val oB = p(EYE_B_OUTER); val iB = p(EYE_B_INNER)

        var axis = oB - oA
        if (axis.x < 0) axis = axis * -1f          // 항상 이미지 오른쪽을 향하게
        val eyeDist = axis.len()
        if (eyeDist < 1f) return null
        axis /= eyeDist
        val perp = V(-axis.y, axis.x)              // 이미지 아래쪽 방향

        // 머리 회전
        val eyeMid = (oA + oB) * 0.5f
        val nv = p(NOSE_TIP) - eyeMid
        val headX = nv.dot(axis) / eyeDist
        val headY = nv.dot(perp) / eyeDist

        // 홍채를 가까운 눈에 배정 (인덱스 순서에 의존하지 않도록)
        val cA = (oA + iA) * 0.5f
        val cB = (oB + iB) * 0.5f
        val r1 = p(IRIS_1); val r2 = p(IRIS_2)
        val swap = (r1 - cA).len() + (r2 - cB).len() > (r1 - cB).len() + (r2 - cA).len()
        val irisA = if (swap) r2 else r1
        val irisB = if (swap) r1 else r2

        fun eye(c: V, o: V, i: V, iris: V): V {
            val w = (i - o).len().coerceAtLeast(1f)
            val d = iris - c
            return V(d.dot(axis) / w, d.dot(perp) / w)
        }
        val gA = eye(cA, oA, iA, irisA)
        val gB = eye(cB, oB, iB, irisB)

        var bl = 0f; var br = 0f
        blend?.forEach {
            when (it.categoryName()) {
                "eyeBlinkLeft" -> bl = it.score()
                "eyeBlinkRight" -> br = it.score()
            }
        }

        return FaceFeatures(
            t = t,
            headX = headX,
            headY = headY,
            gazeX = (gA.x + gB.x) / 2f,
            gazeY = (gA.y + gB.y) / 2f,
            blinkL = bl,
            blinkR = br,
        )
    }
}
