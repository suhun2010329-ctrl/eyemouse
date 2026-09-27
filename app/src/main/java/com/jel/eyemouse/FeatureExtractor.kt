package com.jel.eyemouse

import android.graphics.Bitmap
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

const val EYE_DIM = 12
const val GEO_DIM = 14
const val FEAT_DIM = EYE_DIM + GEO_DIM

/** 특징 벡터 v 안의 위치 (0~11은 눈 모양 특징, 12~25는 기하·조건 특징) */
object G {
    const val Z = 12        // 눈–화면 거리(mm)
    const val EX = 13       // 두 눈 중간 위치 x(mm, 카메라 기준)
    const val EY = 14       // 두 눈 중간 위치 y(mm)
    const val GX = 15       // 머리 안에서 눈동자 좌우 각도(rad)
    const val GY = 16       // 눈동자 상하 각도(rad)
    const val LID = 17      // 눈꺼풀 벌어짐 평균
    const val HX = 18       // 머리 좌우 회전량
    const val HY = 19       // 머리 상하 회전량
    const val IRIS = 20     // 홍채 지름(px, 원본 해상도)
    const val CONF = 21     // 정밀 홍채 신뢰도(0~1)
    const val BRIGHT = 22   // 눈 영역 밝기(0~255)
    const val SHARP = 23    // 눈 영역 선명도
    const val ROLL = 24     // 머리 기울기(rad)
    const val PITCH = 25    // 폰 기울기(°)
}

/**
 * 한 프레임의 특징값.
 * v[0..11]: 눈 모양 특징 (오른눈·왼눈 홍채 위치, 눈꺼풀, 머리 자세, 얼굴 위치·크기)
 * v[12..25]: 기하·조건 특징 (G 참조)
 */
class FaceFeatures(
    val t: Long,
    val headX: Float,
    val headY: Float,
    val blinkL: Float,
    val blinkR: Float,
    val v: FloatArray,
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
    private const val A_OUTER = 33
    private const val A_INNER = 133
    private const val A_TOP = 159
    private const val A_BOTTOM = 145
    private const val B_OUTER = 263
    private const val B_INNER = 362
    private const val B_TOP = 386
    private const val B_BOTTOM = 374
    private const val NOSE_TIP = 1

    /** 평균 홍채 지름(mm) — 사람 간 차이가 작아 거리 측정의 기준 자로 쓴다 */
    private const val IRIS_MM = 11.7f
    /** 눈 폭(mm)과 안구 회전 반지름(mm) 근사값 */
    private const val EYE_WIDTH_MM = 29f
    private const val EYE_RADIUS_MM = 12f

    fun extract(
        lm: List<NormalizedLandmark>,
        blend: List<Category>?,
        full: Bitmap,
        t: Long,
    ): FaceFeatures? {
        if (lm.size < 478) return null
        val imgW = full.width
        val imgH = full.height
        fun p(i: Int) = V(lm[i].x() * imgW, lm[i].y() * imgH)
        fun rad(c: Int): Float {
            val cc = p(c)
            var s = 0f
            for (k in 1..4) s += (p(c + k) - cc).len()
            return s / 4f
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

        // MediaPipe 홍채 중심·반지름을 가까운 눈에 배정
        val cA = (oA + iA) * 0.5f
        val cB = (oB + iB) * 0.5f
        val r1 = p(468); val r2 = p(473)
        val swap = (r1 - cA).len() + (r2 - cB).len() > (r1 - cB).len() + (r2 - cA).len()
        val mpA = if (swap) r2 else r1
        val mpB = if (swap) r1 else r2
        val radA = if (swap) rad(473) else rad(468)
        val radB = if (swap) rad(468) else rad(473)

        val topA = p(A_TOP); val botA = p(A_BOTTOM)
        val topB = p(B_TOP); val botB = p(B_BOTTOM)

        // 고해상도 크롭에서 정밀 홍채 중심
        val refA = EyeCropRefiner.refine(full, oA.x, oA.y, iA.x, iA.y, topA.y, botA.y, mpA.x, mpA.y, radA, true)
        val refB = EyeCropRefiner.refine(full, oB.x, oB.y, iB.x, iB.y, topB.y, botB.y, mpB.x, mpB.y, radB, false)
        fun fuse(mp: V, r: RefineOut?): V {
            if (r == null) return mp
            val k = 0.8f * r.conf
            return V(mp.x + k * (r.cx - mp.x), mp.y + k * (r.cy - mp.y))
        }
        val irisA = fuse(mpA, refA)
        val irisB = fuse(mpB, refB)

        val wA = (iA - oA).len().coerceAtLeast(1f)
        val wB = (iB - oB).len().coerceAtLeast(1f)
        val dA = irisA - cA
        val dB = irisB - cB
        val lidA = (botA - topA).len() / wA
        val lidB = (botB - topB).len() / wB

        var bl = 0f; var br = 0f
        blend?.forEach {
            when (it.categoryName()) {
                "eyeBlinkLeft" -> bl = it.score()
                "eyeBlinkRight" -> br = it.score()
            }
        }

        // 거리: 초점거리(px) × 홍채 지름(mm) ÷ 홍채 지름(px)
        val irisPx = radA + radB   // 두 눈 지름 평균 = (2rA + 2rB) / 2
        val fpx = EyeMouseState.focalRatio * max(imgW, imgH)
        val z = (fpx * IRIS_MM / irisPx.coerceAtLeast(1f)).coerceIn(100f, 1000f)
        val ex = (eyeMid.x - imgW / 2f) * z / fpx
        val ey = (eyeMid.y - imgH / 2f) * z / fpx

        // 머리 안에서 눈동자 각도: 홍채 이동(mm) ÷ 안구 반지름 → 각도
        fun ang(ratio: Float) = asin((ratio * EYE_WIDTH_MM / EYE_RADIUS_MM).coerceIn(-0.95f, 0.95f))
        val cfA = (refA?.conf ?: 0f) + 0.3f
        val cfB = (refB?.conf ?: 0f) + 0.3f
        val wa = cfA * lidA
        val wb = cfB * lidB
        val ws = (wa + wb).coerceAtLeast(1e-4f)
        val gx = (wa * ang(dA.dot(axis) / wA) + wb * ang(dB.dot(axis) / wB)) / ws
        val gy = (wa * ang(dA.dot(perp) / wA) + wb * ang(dB.dot(perp) / wB)) / ws

        val bright = listOfNotNull(refA?.bright, refB?.bright).average().toFloat().let { if (it.isNaN()) 0f else it }
        val sharp = listOfNotNull(refA?.sharp, refB?.sharp).average().toFloat().let { if (it.isNaN()) 0f else it }
        val conf = ((refA?.conf ?: 0f) + (refB?.conf ?: 0f)) / 2f

        val v = floatArrayOf(
            dA.dot(axis) / wA, dA.dot(perp) / wA,
            dB.dot(axis) / wB, dB.dot(perp) / wB,
            lidA, lidB,
            headX, headY, roll,
            eyeMid.x / imgW, eyeMid.y / imgH,
            eyeDist / imgW,
            z, ex, ey, gx, gy, (lidA + lidB) / 2f, headX, headY,
            irisPx, conf, bright, sharp, roll, PoseSensor.pitchDeg,
        )
        return FaceFeatures(t, headX, headY, bl, br, v)
    }
}
