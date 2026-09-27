package com.jel.eyemouse

import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.atan2
import kotlin.math.sqrt

const val EYE_DIM = 12

/**
 * 한 프레임의 특징값.
 * headX/headY: 머리 회전량 (머리 모드용)
 * eye: 시선 학습용 12차원 벡터
 *   0,1  오른눈 홍채 위치(가로, 세로) — 눈 폭으로 정규화
 *   2,3  왼눈 홍채 위치
 *   4,5  눈꺼풀 벌어짐(오른/왼) — 위아래 시선에 민감
 *   6,7,8  머리 좌우·상하 회전, 기울기
 *   9,10 얼굴 화면 내 위치, 11 얼굴 크기(거리)
 */
class FaceFeatures(
    val t: Long,
    val headX: Float,
    val headY: Float,
    val blinkL: Float,
    val blinkR: Float,
    val eye: FloatArray,
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
    private const val A_OUTER = 33
    private const val A_INNER = 133
    private const val A_TOP = 159
    private const val A_BOTTOM = 145
    private const val B_OUTER = 263
    private const val B_INNER = 362
    private const val B_TOP = 386
    private const val B_BOTTOM = 374
    private const val NOSE_TIP = 1

    fun extract(
        lm: List<NormalizedLandmark>,
        blend: List<Category>?,
        imgW: Int,
        imgH: Int,
        t: Long,
    ): FaceFeatures? {
        if (lm.size < 478) return null
        fun p(i: Int) = V(lm[i].x() * imgW, lm[i].y() * imgH)
        fun avg(from: Int, to: Int): V {
            var sx = 0f; var sy = 0f
            for (i in from..to) { val q = p(i); sx += q.x; sy += q.y }
            val n = (to - from + 1).toFloat()
            return V(sx / n, sy / n)
        }

        val oA = p(A_OUTER); val iA = p(A_INNER)
        val oB = p(B_OUTER); val iB = p(B_INNER)

        var axis = oB - oA
        if (axis.x < 0) axis = axis * -1f
        val eyeDist = axis.len()
        if (eyeDist < 1f) return null
        axis /= eyeDist
        val perp = V(-axis.y, axis.x)
        val roll = atan2(axis.y, axis.x)

        val eyeMid = (oA + oB) * 0.5f
        val nv = p(NOSE_TIP) - eyeMid
        val headX = nv.dot(axis) / eyeDist
        val headY = nv.dot(perp) / eyeDist

        // 홍채 중심 = 홍채 5점 평균, 가까운 눈에 배정
        val cA = (oA + iA) * 0.5f
        val cB = (oB + iB) * 0.5f
        val r1 = avg(468, 472)
        val r2 = avg(473, 477)
        val swap = (r1 - cA).len() + (r2 - cB).len() > (r1 - cB).len() + (r2 - cA).len()
        val irisA = if (swap) r2 else r1
        val irisB = if (swap) r1 else r2

        val wA = (iA - oA).len().coerceAtLeast(1f)
        val wB = (iB - oB).len().coerceAtLeast(1f)
        val dA = irisA - cA
        val dB = irisB - cB
        val lidA = (p(A_BOTTOM) - p(A_TOP)).len() / wA
        val lidB = (p(B_BOTTOM) - p(B_TOP)).len() / wB

        var bl = 0f; var br = 0f
        blend?.forEach {
            when (it.categoryName()) {
                "eyeBlinkLeft" -> bl = it.score()
                "eyeBlinkRight" -> br = it.score()
            }
        }

        val eye = floatArrayOf(
            dA.dot(axis) / wA, dA.dot(perp) / wA,
            dB.dot(axis) / wB, dB.dot(perp) / wB,
            lidA, lidB,
            headX, headY, roll,
            eyeMid.x / imgW, eyeMid.y / imgH,
            eyeDist / imgW,
        )
        return FaceFeatures(t, headX, headY, bl, br, eye)
    }
}
