package com.jel.handgesture

import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** 손 모양 */
enum class Pose(val label: String) {
    NONE("-"), OPEN("손바닥"), FOUR("네 손가락"), FIST("주먹"), POINT("검지"), V("브이"), THREE("세 손가락"),
    THUMB_UP("엄지 위"), THUMB_DOWN("엄지 아래"), OK("OK"), SHAKA("샤카"), PINCH("집기"), OTHER("기타"),
}

/**
 * 한 프레임 분석 결과.
 * 좌표는 거울 방향 이미지 기준, x = 가로 비율(0~1), y = 가로 폭 단위(세로 비율 × 세로/가로)로 맞춰 등방성 유지.
 */
class HandFrame(
    val t: Long,
    val pts: FloatArray,        // 21점 × (x, y)
    val ext: BooleanArray,      // 엄지·검지·중지·약지·새끼 펴짐
    val pinch: Boolean,
    val pose: Pose,
    val count: Int,             // 펴진 손가락 수 (숫자 바로가기용, 해당 없으면 0)
    val palmX: Float,
    val palmY: Float,
    val tipX: Float,
    val tipY: Float,
    val palmSize: Float,
)

/**
 * 21개 랜드마크 → 손가락 펴짐, 손 모양.
 * 3D 월드 좌표의 관절 각도로 판정하므로 손 방향·크기·거리와 무관하고,
 * 히스테리시스로 경계에서 깜빡이지 않는다.
 */
object HandGeometry {
    private val FINGERS = arrayOf(
        intArrayOf(5, 6, 7, 8), intArrayOf(9, 10, 11, 12),
        intArrayOf(13, 14, 15, 16), intArrayOf(17, 18, 19, 20),
    )
    private val ext = BooleanArray(5)
    private var pinch = false

    fun reset() { ext.fill(false); pinch = false }

    fun analyze(t: Long, img: List<NormalizedLandmark>, world: List<Landmark>?, aspect: Float): HandFrame? {
        if (img.size < 21) return null
        val ys = 1f / aspect
        val ix = FloatArray(21) { img[it].x() }
        val iy = FloatArray(21) { img[it].y() * ys }
        val w = world != null && world.size >= 21
        val wx = FloatArray(21) { if (w) world!![it].x() else ix[it] }
        val wy = FloatArray(21) { if (w) world!![it].y() else iy[it] }
        val wz = FloatArray(21) { if (w) world!![it].z() else img[it].z() }

        fun d(a: Int, b: Int): Float {
            val dx = wx[a] - wx[b]; val dy = wy[a] - wy[b]; val dz = wz[a] - wz[b]
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
        /** j에서 a, b 방향 벡터 사이 각의 cos (곧게 펴지면 -1) */
        fun cosAt(a: Int, j: Int, b: Int): Float {
            val ax = wx[a] - wx[j]; val ay = wy[a] - wy[j]; val az = wz[a] - wz[j]
            val bx = wx[b] - wx[j]; val by = wy[b] - wy[j]; val bz = wz[b] - wz[j]
            val n = sqrt(ax * ax + ay * ay + az * az) * sqrt(bx * bx + by * by + bz * bz)
            return if (n < 1e-9f) 0f else (ax * bx + ay * by + az * bz) / n
        }

        val palm = d(0, 9).coerceAtLeast(1e-4f)
        for (f in 0 until 4) {
            val j = FINGERS[f]
            val c = cosAt(j[0], j[1], j[3])
            val farther = d(0, j[3]) > d(0, j[1]) * 1.05f
            val on = c < -0.75f && farther
            val off = c > -0.45f || !farther
            if (!ext[f + 1] && on) ext[f + 1] = true else if (ext[f + 1] && off) ext[f + 1] = false
        }
        val spread = d(4, 5) / palm
        val straight = cosAt(2, 3, 4)
        val tOn = spread > 0.6f && straight < -0.7f
        val tOff = spread < 0.42f || straight > -0.35f
        if (!ext[0] && tOn) ext[0] = true else if (ext[0] && tOff) ext[0] = false
        val pd = d(4, 8) / palm
        if (!pinch && pd < 0.28f) pinch = true else if (pinch && pd > 0.42f) pinch = false

        // 이미지 평면 기준 손바닥 중심·크기
        val palmX = (ix[0] + ix[5] + ix[9] + ix[13] + ix[17]) / 5f
        val palmY = (iy[0] + iy[5] + iy[9] + iy[13] + iy[17]) / 5f
        val palmImg = max(hypot(ix[9] - ix[0], iy[9] - iy[0]), 1.2f * hypot(ix[17] - ix[5], iy[17] - iy[5]))
            .coerceAtLeast(1e-3f)

        val th = ext[0]; val i = ext[1]; val m = ext[2]; val r = ext[3]; val p = ext[4]
        val pose = when {
            pinch && m && r && p -> Pose.OK
            pinch && !m && !r && !p -> Pose.PINCH
            i && m && r && p -> if (th) Pose.OPEN else Pose.FOUR
            i && m && r && !p -> Pose.THREE
            i && m && !r && !p -> Pose.V
            i && !m && !r && !p -> Pose.POINT
            th && p && !i && !m && !r -> Pose.SHAKA
            th && !i && !m && !r && !p -> when {
                iy[4] < iy[2] - 0.4f * palmImg -> Pose.THUMB_UP
                iy[4] > iy[2] + 0.4f * palmImg -> Pose.THUMB_DOWN
                else -> Pose.OTHER
            }
            !th && !i && !m && !r && !p -> Pose.FIST
            else -> Pose.OTHER
        }
        val countable = pose != Pose.OK && pose != Pose.PINCH && pose != Pose.SHAKA &&
            pose != Pose.THUMB_UP && pose != Pose.THUMB_DOWN && pose != Pose.FIST
        val n = ext.count { it }
        val count = if (countable && n in 1..5 && (n > 1 || i)) n else 0

        val pts = FloatArray(42)
        for (k in 0 until 21) { pts[k * 2] = ix[k]; pts[k * 2 + 1] = iy[k] }
        return HandFrame(t, pts, ext.copyOf(), pinch, pose, count, palmX, palmY, ix[8], iy[8], palmImg)
    }
}
