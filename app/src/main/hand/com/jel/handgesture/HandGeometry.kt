package com.jel.handgesture

import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** 손 모양 */
enum class Pose(val label: String) {
    NONE("-"), OPEN("손바닥"), FOUR("네 손가락"), FIST("주먹"), POINT("검지"), V("브이"), THREE("세 손가락"),
    THUMB_UP("엄지 위"), THUMB_DOWN("엄지 아래"), OK("OK"), SHAKA("샤카"), PINCH("집기"),
    GATHER("손 모으기"), ROCK("검지+새끼"), LOVE("엄지+검지+새끼"), GUN("L자"), OTHER("기타"),
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
 * 3D 월드 좌표(미터)의 관절 각도로 판정하므로 손 방향·크기·거리와 무관하고,
 * 손가락마다 세 가지 조건(마디 곧음, 손바닥 방향과 일치, 손목에서 멀어짐)을 모두 봐서
 * 반쯤 굽힌 손가락을 편 것으로 잘못 보지 않는다. 경계에서는 히스테리시스로 깜빡임을 막는다.
 */
object HandGeometry {
    private val FINGERS = arrayOf(
        intArrayOf(5, 6, 7, 8), intArrayOf(9, 10, 11, 12),
        intArrayOf(13, 14, 15, 16), intArrayOf(17, 18, 19, 20),
    )
    private val TIPS = intArrayOf(4, 8, 12, 16, 20)
    private val ext = BooleanArray(5)
    private var pinch = false
    private var gather = false
    private val ix = FloatArray(21)
    private val iy = FloatArray(21)
    private val wx = FloatArray(21)
    private val wy = FloatArray(21)
    private val wz = FloatArray(21)

    fun reset() { ext.fill(false); pinch = false; gather = false }

    private fun d(a: Int, b: Int): Float {
        val dx = wx[a] - wx[b]; val dy = wy[a] - wy[b]; val dz = wz[a] - wz[b]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** 벡터 (b - a)와 (c' - b')의 cos */
    private fun cosDir(a: Int, b: Int, c: Int, e: Int): Float {
        val ax = wx[b] - wx[a]; val ay = wy[b] - wy[a]; val az = wz[b] - wz[a]
        val bx = wx[e] - wx[c]; val by = wy[e] - wy[c]; val bz = wz[e] - wz[c]
        val n = sqrt(ax * ax + ay * ay + az * az) * sqrt(bx * bx + by * by + bz * bz)
        return if (n < 1e-9f) 0f else (ax * bx + ay * by + az * bz) / n
    }

    /** j에서 a, b를 향한 두 벡터 사이 각의 cos (곧게 펴지면 -1) */
    private fun cosAt(a: Int, j: Int, b: Int): Float {
        val ax = wx[a] - wx[j]; val ay = wy[a] - wy[j]; val az = wz[a] - wz[j]
        val bx = wx[b] - wx[j]; val by = wy[b] - wy[j]; val bz = wz[b] - wz[j]
        val n = sqrt(ax * ax + ay * ay + az * az) * sqrt(bx * bx + by * by + bz * bz)
        return if (n < 1e-9f) 0f else (ax * bx + ay * by + az * bz) / n
    }

    fun analyze(t: Long, img: List<NormalizedLandmark>, world: List<Landmark>?, aspect: Float): HandFrame? {
        if (img.size < 21) return null
        val ys = 1f / aspect
        val wl = if (world != null && world.size >= 21) world else null
        for (k in 0 until 21) {
            val p = img[k]
            ix[k] = p.x(); iy[k] = p.y() * ys
            if (wl != null) {
                val q = wl[k]; wx[k] = q.x(); wy[k] = q.y(); wz[k] = q.z()
            } else {
                wx[k] = ix[k]; wy[k] = iy[k]; wz[k] = p.z()
            }
        }

        val palm = d(0, 9).coerceAtLeast(1e-4f)

        // 검지~새끼: 마디가 곧고(PIP·DIP), 손바닥 방향과 같은 쪽을 향하고, 끝이 손목에서 멀어야 '펴짐'
        for (f in 0 until 4) {
            val j = FINGERS[f]
            val straight = cosAt(j[0], j[1], j[3])
            val dip = cosAt(j[1], j[2], j[3])
            val along = cosDir(0, j[0], j[0], j[3])
            val reach = d(0, j[3]) / d(0, j[1]).coerceAtLeast(1e-4f)
            val on = straight < -0.78f && dip < -0.55f && along > 0.3f && reach > 1.1f
            val off = straight > -0.5f || along < 0.05f || reach < 1.0f
            if (!ext[f + 1] && on) ext[f + 1] = true else if (ext[f + 1] && off) ext[f + 1] = false
        }

        // 엄지: 검지 뿌리에서 벌어지고, 중지 뿌리에서도 멀고, 곧아야 '펴짐'
        val spread = d(4, 5) / palm
        val away = d(4, 9) / palm
        val tStraight = cosAt(2, 3, 4)
        val tOn = spread > 0.5f && away > 0.62f && tStraight < -0.7f
        val tOff = spread < 0.38f || away < 0.5f || tStraight > -0.35f
        if (!ext[0] && tOn) ext[0] = true else if (ext[0] && tOff) ext[0] = false

        val pd = d(4, 8) / palm
        if (!pinch && pd < 0.28f) pinch = true else if (pinch && pd > 0.42f) pinch = false

        // 손 모으기: 다섯 손끝이 한 점에 모이고, 그 점이 손바닥에서 앞으로 나와 있음 (주먹과 구분)
        var cx = 0f; var cy = 0f; var cz = 0f
        for (k in TIPS) { cx += wx[k]; cy += wy[k]; cz += wz[k] }
        cx /= 5f; cy /= 5f; cz /= 5f
        var gs = 0f
        for (k in TIPS) {
            val dx = wx[k] - cx; val dy = wy[k] - cy; val dz = wz[k] - cz
            gs = max(gs, sqrt(dx * dx + dy * dy + dz * dz))
        }
        gs /= palm
        val pcx = (wx[0] + wx[5] + wx[9] + wx[13] + wx[17]) / 5f
        val pcy = (wy[0] + wy[5] + wy[9] + wy[13] + wy[17]) / 5f
        val pcz = (wz[0] + wz[5] + wz[9] + wz[13] + wz[17]) / 5f
        val gr = sqrt((cx - pcx) * (cx - pcx) + (cy - pcy) * (cy - pcy) + (cz - pcz) * (cz - pcz)) / palm
        if (!gather && gs < 0.36f && gr > 0.7f) gather = true else if (gather && (gs > 0.5f || gr < 0.55f)) gather = false

        // 이미지 평면 기준 손바닥 중심·크기
        val palmX = (ix[0] + ix[5] + ix[9] + ix[13] + ix[17]) / 5f
        val palmY = (iy[0] + iy[5] + iy[9] + iy[13] + iy[17]) / 5f
        val palmImg = max(hypot(ix[9] - ix[0], iy[9] - iy[0]), 1.2f * hypot(ix[17] - ix[5], iy[17] - iy[5]))
            .coerceAtLeast(1e-3f)

        val th = ext[0]; val i = ext[1]; val m = ext[2]; val r = ext[3]; val p = ext[4]
        val pose = when {
            gather -> Pose.GATHER
            pinch && m && r && p -> Pose.OK
            pinch && !m && !r && !p -> Pose.PINCH
            i && m && r && p -> if (th) Pose.OPEN else Pose.FOUR
            i && m && r && !p -> Pose.THREE
            i && m && !r && !p -> Pose.V
            i && !m && !r && p -> if (th) Pose.LOVE else Pose.ROCK
            i && !m && !r && !p -> if (th) Pose.GUN else Pose.POINT
            th && p && !i && !m && !r -> Pose.SHAKA
            th && !i && !m && !r && !p -> when {
                iy[4] < iy[2] - 0.4f * palmImg -> Pose.THUMB_UP
                iy[4] > iy[2] + 0.4f * palmImg -> Pose.THUMB_DOWN
                else -> Pose.OTHER
            }
            !th && !i && !m && !r && !p -> Pose.FIST
            else -> Pose.OTHER
        }
        val countable = pose == Pose.POINT || pose == Pose.V || pose == Pose.THREE || pose == Pose.FOUR ||
            pose == Pose.OPEN || pose == Pose.GUN || pose == Pose.OTHER
        val n = ext.count { it }
        val count = if (countable && n in 1..5 && (n > 1 || i)) n else 0

        val pts = FloatArray(42)
        for (k in 0 until 21) { pts[k * 2] = ix[k]; pts[k * 2 + 1] = iy[k] }
        return HandFrame(t, pts, ext.copyOf(), pinch, pose, count, palmX, palmY, ix[8], iy[8], palmImg)
    }
}
