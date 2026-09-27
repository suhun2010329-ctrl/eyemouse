package com.jel.eyemouse

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.min

enum class TrackState { GOOD, DEGRADED, LOST }

/** 품질이 떨어진 원인 → 사용자 안내 문구 */
enum class Cause(val msg: String) {
    FACE("얼굴이 화면 안에 오게 해 주세요"),
    NEAR("조금 더 멀리 해 주세요"),
    FAR("조금 더 가까이 해 주세요"),
    HEAD("화면을 정면으로 봐 주세요"),
    MOTION("폰을 잠시 고정해 주세요"),
    IRIS("눈이 작게 잡혀요 · 조금 더 가까이"),
    EYES("눈을 편하게 떠 주세요"),
    DARK("밝은 곳으로 이동해 주세요"),
    BLUR("렌즈를 닦거나 폰을 고정해 주세요"),
    ENVELOPE("이 자세에서 빠른 보정을 해 주세요"),
}

/**
 * 프레임별 추적 품질 점수(0~100)와 3단계 상태.
 * 항목별 점수(0~1)를 곱하므로 한 항목만 나빠도 전체가 떨어진다.
 * 내려갈 땐 3프레임, 올라올 땐 0.5초 연속 조건을 만족해야 상태가 바뀐다.
 */
object QualityMonitor {
    @Volatile var score = 0f
        private set
    @Volatile var state = TrackState.LOST
        private set
    @Volatile var cause: Cause? = Cause.FACE
        private set
    @Volatile var fps = 0f
        private set
    @Volatile var lidBase = 0f
        private set
    @Volatile var sharpBase = 0f
        private set
    @Volatile var yawDeg = 0f
        private set

    // 세션 통계 (ms)
    private val stateMs = LongArray(3)
    private val causeMs = LongArray(Cause.values().size)
    private var sessionStart = 0L

    private var lastT = 0L
    private var downCount = 0
    private var upSince = -1L

    @Synchronized
    fun reset() {
        state = TrackState.LOST; score = 0f; cause = Cause.FACE
        stateMs.fill(0); causeMs.fill(0); lastT = 0L; downCount = 0; upSince = -1L
        sessionStart = System.currentTimeMillis()
    }

    private fun ramp(v: Float, bad: Float, good: Float): Float =
        ((v - bad) / (good - bad)).coerceIn(0f, 1f)

    /** envelope: 학습 범위 안이면 1, 벗어날수록 0 (모델 없으면 1) */
    @Synchronized
    fun update(f: FaceFeatures, envelope: Float): TrackState {
        val v = f.v
        val t = f.t
        val z = v[G.Z]
        val iris = v[G.IRIS]
        val lid = v[G.LID]
        val bright = v[G.BRIGHT]
        val sharp = v[G.SHARP]
        val gyro = PoseSensor.gyroDps
        yawDeg = Math.toDegrees(asin((v[G.HX] / 0.35f).coerceIn(-1f, 1f).toDouble())).toFloat()

        // 개인 기준값 (좋은 상태에서만 천천히 학습)
        val eyesOpen = min(f.blinkL, f.blinkR) < 0.3f
        if (eyesOpen && gyro < 10f) {
            lidBase = if (lidBase == 0f) lid else lidBase + 0.01f * (lid - lidBase)
            if (sharp > 0f) sharpBase = if (sharpBase == 0f) sharp else sharpBase + 0.01f * (sharp - sharpBase)
        }

        val parts = FloatArray(Cause.values().size) { 1f }
        parts[Cause.NEAR.ordinal] = ramp(z, 180f, 250f)
        parts[Cause.FAR.ordinal] = 1f - ramp(z, 400f, 500f)
        parts[Cause.HEAD.ordinal] = 1f - ramp(abs(yawDeg), 15f, 35f)
        parts[Cause.MOTION.ordinal] = 1f - ramp(gyro, 5f, 40f)
        parts[Cause.IRIS.ordinal] = ramp(iris, 14f, 26f)
        parts[Cause.EYES.ordinal] = if (lidBase > 0f && eyesOpen) ramp(lid / lidBase, 0.4f, 0.8f) else 1f
        parts[Cause.DARK.ordinal] = if (bright > 0f) min(ramp(bright, 40f, 80f), 1f - ramp(bright, 200f, 240f)) else 1f
        parts[Cause.BLUR.ordinal] = if (sharpBase > 0f && sharp > 0f) ramp(sharp / sharpBase, 0.25f, 0.6f) else 1f
        parts[Cause.ENVELOPE.ordinal] = envelope

        var prod = 1f
        var worst = -1
        var worstV = 1f
        for (i in parts.indices) {
            prod *= parts[i]
            if (parts[i] < worstV) { worstV = parts[i]; worst = i }
        }
        score = 100f * prod
        cause = if (worstV < 0.7f && worst >= 0) Cause.values()[worst] else null

        val raw = when {
            gyro > 40f -> TrackState.LOST
            score >= 70f -> TrackState.GOOD
            score >= 40f -> TrackState.DEGRADED
            else -> TrackState.LOST
        }
        transition(raw, t, gyro > 40f)
        return state
    }

    @Synchronized
    fun noFace(t: Long) {
        score = 0f
        cause = Cause.FACE
        transition(TrackState.LOST, t, true)
    }

    private fun transition(raw: TrackState, t: Long, immediate: Boolean) {
        if (lastT > 0) {
            val dt = (t - lastT).coerceIn(0L, 500L)
            stateMs[state.ordinal] += dt
            cause?.let { if (state != TrackState.GOOD) causeMs[it.ordinal] += dt }
            if (dt > 0) fps += 0.1f * (1000f / dt - fps)
        }
        lastT = t
        when {
            raw.ordinal > state.ordinal -> {   // 나빠짐
                upSince = -1
                if (immediate || ++downCount >= 3) { state = raw; downCount = 0 }
            }
            raw.ordinal < state.ordinal -> {   // 좋아짐
                downCount = 0
                if (upSince < 0) upSince = t
                if (t - upSince >= 500) { state = raw; upSince = -1 }
            }
            else -> { downCount = 0; upSince = -1 }
        }
    }

    /** 세션 통계: 상태별 시간 비율(%) */
    @Synchronized
    fun statePercents(): FloatArray {
        val total = stateMs.sum().coerceAtLeast(1)
        return FloatArray(3) { 100f * stateMs[it] / total }
    }

    /** 세션 통계: 품질 저하 원인 상위 3개 (원인, 초) */
    @Synchronized
    fun topCauses(): List<Pair<Cause, Float>> =
        Cause.values().map { it to causeMs[it.ordinal] / 1000f }
            .filter { it.second >= 1f }
            .sortedByDescending { it.second }
            .take(3)

    /** 지금 가장 효과가 큰 조치 한 가지 */
    fun recommendation(): String {
        cause?.let { return it.msg }
        val top = topCauses().firstOrNull()
        return when {
            GazeTrainer.model == null -> "먼저 '전체 학습'을 해 주세요"
            top != null && top.first == Cause.ENVELOPE -> "자주 쓰는 자세에서 '빠른 보정'을 해 주세요"
            top != null -> "자주 생긴 문제: ${top.first.msg}"
            !Settings.accuracyMm.isNaN() && Settings.accuracyMm > 10f -> "'정밀 학습'으로 정확도를 올려 보세요"
            else -> "지금 상태가 좋아요"
        }
    }
}
