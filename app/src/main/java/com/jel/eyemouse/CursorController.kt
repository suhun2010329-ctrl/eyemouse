package com.jel.eyemouse

import android.content.Context
import android.os.SystemClock
import kotlin.math.hypot
import kotlin.math.min

/**
 * 특징값 → 커서 위치, 클릭 판정, 사용 중 학습 샘플 수집.
 * MediaPipe 결과 스레드(단일)에서만 onFeatures/onNoFace가 호출된다.
 */
class CursorController(ctx: Context) {
    private val density = ctx.resources.displayMetrics.density
    private val fx = OneEuroFilter()
    private val fy = OneEuroFilter()

    @Volatile private var recenterRequested = false
    @Volatile private var resetRequested = false

    private var x = -1f
    private var y = -1f
    private var lastFaceT = 0L
    private val startT = SystemClock.uptimeMillis()

    // 머리 모드 기준 자세 수집
    private var recenterLeft = 0
    private var sumX = 0.0
    private var sumY = 0.0
    private var sumN = 0

    // 눈 감김 (기준값을 따라가는 적응형 임계값 — 아래를 볼 때 눈꺼풀이 내려가도 오작동 방지)
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

    // 시선: 급속 이동(saccade) 감지
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
        val s = svc ?: return

        if (EyeMouseState.calibrating) {
            s.hideCursor(); resetDwell(); closedSince = -1
            return
        }
        if (resetRequested) {
            resetRequested = false
            fx.reset(); fy.reset(); resetDwell()
        }

        histT[histPos] = f.t; histE[histPos] = f.eye
        histPos = (histPos + 1) % HIST

        val w = EyeMouseState.screenW.toFloat()
        val h = EyeMouseState.screenH.toFloat()
        val ppmX = EyeMouseState.ppmX
        val ppmY = EyeMouseState.ppmY
        val eyeModel = GazeTrainer.model
        // 시선 모델은 세로 화면으로 학습됨
        val useEye = Settings.mode == TrackMode.EYE && eyeModel != null && h >= w

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
                !EyeMouseState.paused && frozenX >= 0
            ) {
                click(frozenX, frozenY, if (frozenEye) closedFeat else null)
            }
            resetDwell()
            fx.reset(); fy.reset()   // 눈 뜬 직후 튐 방지
        }

        // ── 목표 좌표 ──
        val tx: Float
        val ty: Float
        if (useEye) {
            val mm = eyeModel!!.predictMm(f.eye)
            tx = mm.x * ppmX; ty = mm.y * ppmY
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

        // ── 스무딩 ──
        val stab = Settings.stability.toDouble()
        if (useEye) {
            // 시선은 '고정(fixation)'과 '급속 이동(saccade)'이 번갈아 일어남:
            // 고정 중엔 강하게 안정화, 큰 점프가 연속되면 즉시 따라감
            if (x >= 0 && hypot(cx - x, cy - y) > SACCADE_MM * ppmX) {
                if (++saccadeCount >= 2) { fx.reset(); fy.reset(); saccadeCount = 0 }
            } else {
                saccadeCount = 0
            }
            val minCut = 1.5 - 1.3 * stab
            fx.minCutoff = minCut; fy.minCutoff = minCut
            fx.beta = 0.0015; fy.beta = 0.0015
        } else {
            val minCut = 2.5 - 2.2 * stab
            fx.minCutoff = minCut; fy.minCutoff = minCut
            fx.beta = 0.004; fy.beta = 0.004
        }
        x = fx.filter(cx.toDouble(), f.t).toFloat().coerceIn(0f, w - 1)
        y = fy.filter(cy.toDouble(), f.t).toFloat().coerceIn(0f, h - 1)

        // ── 응시 클릭 ──
        var progress = 0f
        val paused = EyeMouseState.paused
        if (!paused && Settings.clickMode != ClickMode.BLINK && f.t - startT > START_GRACE_MS) {
            val radius = if (useEye) DWELL_RADIUS_EYE_MM * ppmX else DWELL_RADIUS_HEAD_DP * density
            if (hypot(x - anchorX, y - anchorY) > radius) {
                anchorX = x; anchorY = y; dwellStart = f.t; armed = true
            }
            if (armed) {
                val dms = Settings.dwellMs
                progress = ((f.t - dwellStart).toFloat() / dms).coerceIn(0f, 1f)
                if (f.t - dwellStart >= dms) {
                    val feat = if (useEye) meanFeatures(dwellStart + 150, f.t) else null
                    click(anchorX, anchorY, feat)
                    armed = false   // 다시 누르려면 일단 벗어났다 돌아와야 함
                    progress = 0f
                }
            }
        }
        s.showCursor(x, y, if (paused) CursorView.State.PAUSED else CursorView.State.NORMAL, progress)
    }

    /** 탭 실행. feat != null 이면(시선 모드) 스냅된 버튼 위치로 학습 샘플 추가 */
    private fun click(px: Float, py: Float, feat: FloatArray?) {
        val s = svc ?: return
        val ppmX = EyeMouseState.ppmX
        val ppmY = EyeMouseState.ppmY
        val eye = feat != null
        val snapR = if (Settings.snap) (if (eye) SNAP_EYE_MM else SNAP_HEAD_MM) * ppmX else 0f
        val learn = eye && Settings.autoLearn
        s.tapSmart(px, py, snapR) { r ->
            if (learn && !r.learnX.isNaN()) GazeTrainer.addImplicit(feat!!, r.learnX / ppmX, r.learnY / ppmY)
        }
    }

    /** 시각 [from, to] 사이 특징 벡터 평균 (3프레임 이상일 때만) */
    private fun meanFeatures(from: Long, to: Long): FloatArray? {
        val acc = FloatArray(EYE_DIM)
        var n = 0
        for (i in 0 until HIST) {
            val e = histE[i] ?: continue
            if (histT[i] in from..to) {
                for (k in 0 until EYE_DIM) acc[k] += e[k]
                n++
            }
        }
        if (n < 3) return null
        val inv = 1f / n
        for (k in 0 until EYE_DIM) acc[k] = acc[k] * inv
        return acc
    }

    fun onNoFace(t: Long) {
        if (t - lastFaceT < NO_FACE_MS) return
        val s = svc ?: return
        closedSince = -1
        resetDwell()
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
        private const val BLINK_MIN_MS = 300L   // 자연스러운 깜빡임(~150ms)은 무시
        private const val BLINK_MAX_MS = 1200L
        private const val PAUSE_MS = 2000L
        private const val NO_FACE_MS = 400L
        private const val START_GRACE_MS = 2000L
        private const val DWELL_RADIUS_HEAD_DP = 36f
        private const val DWELL_RADIUS_EYE_MM = 7f
        private const val SACCADE_MM = 12f
        private const val SNAP_EYE_MM = 9f
        private const val SNAP_HEAD_MM = 4f
    }
}
