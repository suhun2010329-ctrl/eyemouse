package com.jel.eyemouse

import android.content.Context
import android.os.SystemClock
import kotlin.math.hypot
import kotlin.math.min

/**
 * 특징값 → 품질 판정 → 커서 위치, 클릭 판정, 사용 중 학습.
 * MediaPipe 결과 스레드(단일)에서만 onFeatures/onNoFace가 호출된다.
 */
class CursorController(ctx: Context) {
    private val density = ctx.resources.displayMetrics.density
    private val fx = OneEuroFilter()
    private val fy = OneEuroFilter()
    val drift = DriftCorrector()

    @Volatile private var recenterRequested = false
    @Volatile private var resetRequested = false

    private var x = -1f
    private var y = -1f
    private var lastFaceT = 0L
    private val startT = SystemClock.uptimeMillis()
    private var clickBlockUntil = 0L
    private var prevState = TrackState.LOST

    // 머리 모드 기준 자세 수집
    private var recenterLeft = 0
    private var sumX = 0.0
    private var sumY = 0.0
    private var sumN = 0

    // 눈 감김 (적응형 임계값 — 아래를 볼 때 눈꺼풀이 내려가도 오작동 방지)
    private var blinkBase = 0.1f
    private var closedSince = -1L
    private var frozenX = 0f
    private var frozenY = 0f
    private var frozenEye = false
    private var closedFeat: FloatArray? = null

    // 응시 클릭
    private var anchorX = FAR
    private var anchorY = FAR
    private var dwellStart = 0L
    private var armed = false

    // 시선 급속 이동(saccade) 감지
    private var saccadeCount = 0

    // 최근 특징 기록 (클릭 순간 '보고 있던' 특징 평균용)
    private val histT = LongArray(HIST)
    private val histE = arrayOfNulls<FloatArray>(HIST)
    private var histPos = 0

    private val svc get() = EyeMouseAccessibilityService.instance

    fun reloadModel() { resetRequested = true }
    fun requestRecenter() { recenterRequested = true }
    fun onRotation() { resetRequested = true }

    fun togglePause() {
        EyeMouseState.paused = !EyeMouseState.paused
        armed = false
        anchorX = FAR
    }

    fun onFeatures(f: FaceFeatures) {
        lastFaceT = f.t
        EyeMouseState.latest = f
        val eyeModel = GazeTrainer.model
        val w = EyeMouseState.screenW.toFloat()
        val h = EyeMouseState.screenH.toFloat()
        val useEye = Settings.mode == TrackMode.EYE && eyeModel != null && h >= w

        // ── 품질 판정 (분석 화면·캘리브레이션도 이 값을 본다) ──
        val env = if (useEye) eyeModel!!.envelopeScore(f.v) else 1f
        val state = QualityMonitor.update(f, env)
        val s = svc ?: return

        if (EyeMouseState.calibrating) {
            s.hideCursor(); resetDwell(); closedSince = -1
            return
        }
        if (resetRequested) {
            resetRequested = false
            fx.reset(); fy.reset(); resetDwell(); drift.reset()
        }

        histT[histPos] = f.t; histE[histPos] = f.v
        histPos = (histPos + 1) % HIST
        drift.onFrame(f.v)

        // ── 상실: 커서 정지, 클릭 차단 / 복구 직후 0.3초 클릭 차단 ──
        if (state == TrackState.LOST) {
            prevState = state
            resetDwell(); closedSince = -1
            if (x >= 0) s.showCursor(x, y, CursorView.State.LOST, 0f)
            return
        }
        if (prevState == TrackState.LOST) {
            fx.reset(); fy.reset(); saccadeCount = 0
            clickBlockUntil = f.t + RECOVER_BLOCK_MS
        }
        prevState = state
        // 폰이 움직이는 중: 커서 고정
        if (PoseSensor.gyroDps > MOTION_FREEZE_DPS) {
            resetDwell()
            clickBlockUntil = f.t + RECOVER_BLOCK_MS
            if (x >= 0) s.showCursor(x, y, CursorView.State.DEGRADED, 0f)
            return
        }

        val ppmX = EyeMouseState.ppmX
        val ppmY = EyeMouseState.ppmY

        // ── 머리 기준 자세 ──
        if ((recenterRequested || Settings.neutralX.isNaN()) && recenterLeft == 0) {
            recenterRequested = false
            recenterLeft = RECENTER_FRAMES; sumX = 0.0; sumY = 0.0; sumN = 0
        }
        if (recenterLeft > 0) {
            sumX += f.headX; sumY += f.headY; sumN++
            if (--recenterLeft == 0) {
                Settings.neutralX = (sumX / sumN).toFloat()
                Settings.neutralY = (sumY / sumN).toFloat()
                fx.reset(); fy.reset(); resetDwell()
            }
        }
        val nX = Settings.neutralX
        val nY = Settings.neutralY
        if (!useEye && nX.isNaN()) {
            s.showCursor(w / 2, h / 2, CursorView.State.LOST, 0f)
            return
        }

        // ── 눈 감김: 짧게 = 클릭, 2초 이상 = 일시정지 토글 ──
        val lo = min(f.blinkL, f.blinkR)
        val closeTh = (blinkBase + 0.35f).coerceIn(0.5f, 0.85f)
        val th = if (closedSince >= 0) closeTh - 0.15f else closeTh
        val eyesClosed = lo > th
        if (!eyesClosed && closedSince < 0) blinkBase += 0.03f * (lo - blinkBase)
        if (eyesClosed) {
            if (closedSince < 0) {
                closedSince = f.t; frozenX = x; frozenY = y; frozenEye = useEye
                closedFeat = meanFeatures(f.t - 450, f.t - 60)
            }
            val d = f.t - closedSince
            if (x >= 0) {
                val st = if (d >= PAUSE_MS) CursorView.State.PAUSE_READY else CursorView.State.CLOSED
                s.showCursor(x, y, st, 0f)
            }
            return
        }
        if (closedSince >= 0) {
            val d = f.t - closedSince
            closedSince = -1
            if (d >= PAUSE_MS) {
                togglePause()
            } else if (d in BLINK_MIN_MS..BLINK_MAX_MS &&
                Settings.clickMode != ClickMode.DWELL &&
                !EyeMouseState.paused && frozenX >= 0 && f.t >= clickBlockUntil
            ) {
                click(frozenX, frozenY, if (frozenEye) closedFeat else null, state)
            }
            resetDwell()
            fx.reset(); fy.reset()   // 눈 뜬 직후 튐 방지
        }

        // ── 목표 좌표 ──
        val tx: Float
        val ty: Float
        if (useEye) {
            val mm = eyeModel!!.predictMm(f.v)
            tx = (mm.x + drift.bx) * ppmX; ty = (mm.y + drift.by) * ppmY
        } else {
            var dx = f.headX - nX
            var dy = f.headY - nY
            if (Settings.invertX) dx = -dx
            if (Settings.invertY) dy = -dy
            val g = Settings.headGain
            tx = w / 2 + dx * g * w
            ty = h / 2 + dy * g * VERTICAL_GAIN * h
        }
        val cx = tx.coerceIn(0f, w - 1)
        val cy = ty.coerceIn(0f, h - 1)

        // ── 스무딩 (품질 저하 시 더 강하게) ──
        val stab = Settings.stability.toDouble()
        val degradeK = if (state == TrackState.DEGRADED) 0.5 else 1.0
        if (useEye) {
            if (x >= 0 && hypot(cx - x, cy - y) > SACCADE_MM * ppmX) {
                if (++saccadeCount >= 2) { fx.reset(); fy.reset(); saccadeCount = 0 }
            } else {
                saccadeCount = 0
            }
            val minCut = (1.5 - 1.3 * stab) * degradeK
            fx.minCutoff = minCut; fy.minCutoff = minCut
            fx.beta = 0.0015; fy.beta = 0.0015
        } else {
            val minCut = (2.5 - 2.2 * stab) * degradeK
            fx.minCutoff = minCut; fy.minCutoff = minCut
            fx.beta = 0.004; fy.beta = 0.004
        }
        x = fx.filter(cx.toDouble(), f.t).toFloat().coerceIn(0f, w - 1)
        y = fy.filter(cy.toDouble(), f.t).toFloat().coerceIn(0f, h - 1)

        // ── 응시 클릭 ──
        var progress = 0f
        val paused = EyeMouseState.paused
        if (!paused && Settings.clickMode != ClickMode.BLINK && f.t - startT > START_GRACE_MS && f.t >= clickBlockUntil) {
            val radius = if (useEye) DWELL_RADIUS_EYE_MM * ppmX else DWELL_RADIUS_HEAD_DP * density
            if (hypot(x - anchorX, y - anchorY) > radius) {
                anchorX = x; anchorY = y; dwellStart = f.t; armed = true
            }
            if (armed) {
                val dms = Settings.dwellMs
                progress = ((f.t - dwellStart).toFloat() / dms).coerceIn(0f, 1f)
                if (f.t - dwellStart >= dms) {
                    val feat = if (useEye) meanFeatures(dwellStart + 150, f.t) else null
                    click(anchorX, anchorY, feat, state)
                    armed = false
                    progress = 0f
                }
            }
        }
        val cs = when {
            paused -> CursorView.State.PAUSED
            state == TrackState.DEGRADED -> CursorView.State.DEGRADED
            else -> CursorView.State.NORMAL
        }
        s.showCursor(x, y, cs, progress)
    }

    /** 탭 실행. 시선 모드 + 좋은 품질이면 스냅된 버튼 위치로 드리프트 보정·학습 */
    private fun click(px: Float, py: Float, feat: FloatArray?, state: TrackState) {
        val s = svc ?: return
        val ppmX = EyeMouseState.ppmX
        val ppmY = EyeMouseState.ppmY
        val eye = feat != null
        val snapR = if (Settings.snap) (if (eye) SNAP_EYE_MM else SNAP_HEAD_MM) * ppmX else 0f
        val good = eye && state == TrackState.GOOD
        val learn = good && Settings.autoLearn
        s.tapSmart(px, py, snapR) { r ->
            if (good && feat != null && !r.learnX.isNaN()) {
                val fv: FloatArray = feat
                drift.onSnap(px / ppmX, py / ppmY, r.learnX / ppmX, r.learnY / ppmY, fv)
                if (learn) GazeTrainer.addImplicit(fv, r.learnX / ppmX, r.learnY / ppmY)
            }
        }
    }

    /** 시각 [from, to] 사이 특징 벡터 평균 (3프레임 이상일 때만) */
    private fun meanFeatures(from: Long, to: Long): FloatArray? {
        val acc = FloatArray(FEAT_DIM)
        var n = 0
        for (i in 0 until HIST) {
            val e = histE[i] ?: continue
            if (histT[i] in from..to) {
                for (k in 0 until FEAT_DIM) acc[k] += e[k]
                n++
            }
        }
        if (n < 3) return null
        val inv = 1f / n
        for (k in 0 until FEAT_DIM) acc[k] = acc[k] * inv
        return acc
    }

    fun onNoFace(t: Long) {
        QualityMonitor.noFace(t)
        if (t - lastFaceT < NO_FACE_MS) return
        val s = svc ?: return
        closedSince = -1
        resetDwell()
        prevState = TrackState.LOST
        if (EyeMouseState.calibrating) s.hideCursor()
        else if (x >= 0) s.showCursor(x, y, CursorView.State.LOST, 0f)
    }

    private fun resetDwell() {
        armed = false
        anchorX = FAR; anchorY = FAR
    }

    companion object {
        private const val FAR = -100000f
        private const val HIST = 48
        private const val RECENTER_FRAMES = 15
        private const val VERTICAL_GAIN = 1.2f
        private const val BLINK_MIN_MS = 300L
        private const val BLINK_MAX_MS = 1200L
        private const val PAUSE_MS = 2000L
        private const val NO_FACE_MS = 400L
        private const val START_GRACE_MS = 2000L
        private const val RECOVER_BLOCK_MS = 300L
        private const val MOTION_FREEZE_DPS = 20f
        private const val DWELL_RADIUS_HEAD_DP = 36f
        private const val DWELL_RADIUS_EYE_MM = 7f
        private const val SACCADE_MM = 12f
        private const val SNAP_EYE_MM = 9f
        private const val SNAP_HEAD_MM = 4f
    }
}
