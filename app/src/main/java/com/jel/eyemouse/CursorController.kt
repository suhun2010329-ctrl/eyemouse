package com.jel.eyemouse

import android.content.Context
import android.os.SystemClock
import kotlin.math.hypot
import kotlin.math.min

/**
 * 특징값 → 커서 위치, 클릭 판정.
 * MediaPipe 결과 스레드(단일)에서만 onFeatures/onNoFace가 호출된다.
 */
class CursorController(ctx: Context) {
    private val density = ctx.resources.displayMetrics.density
    private val fx = OneEuroFilter()
    private val fy = OneEuroFilter()

    @Volatile private var model: GazeModel? = GazeModel.fromJson(Settings.gazeModel)
    @Volatile private var recenterRequested = false
    @Volatile private var resetRequested = false

    private var x = -1f
    private var y = -1f
    private var lastFaceT = 0L
    private val startT = SystemClock.uptimeMillis()

    // 기준 자세 수집
    private var recenterLeft = 0
    private var sumX = 0.0
    private var sumY = 0.0
    private var sumN = 0

    // 눈 감김
    private var closedSince = -1L
    private var frozenX = 0f
    private var frozenY = 0f

    // 응시 클릭
    private var anchorX = FAR
    private var anchorY = FAR
    private var dwellStart = 0L
    private var armed = false

    private val svc get() = EyeMouseAccessibilityService.instance

    fun reloadModel() { model = GazeModel.fromJson(Settings.gazeModel); resetRequested = true }
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

        val w = EyeMouseState.screenW.toFloat()
        val h = EyeMouseState.screenH.toFloat()
        val mode = Settings.mode
        val eyeModel = model
        val useEye = mode == TrackMode.EYE && eyeModel != null

        // ── 기준 자세(센터) ──
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

        // ── 눈 감김: 짧게 = 클릭, 길게 = 일시정지 토글 ──
        val th = if (closedSince >= 0) BLINK_OPEN_TH else BLINK_CLOSE_TH
        val eyesClosed = min(f.blinkL, f.blinkR) > th
        if (eyesClosed) {
            if (closedSince < 0) { closedSince = f.t; frozenX = x; frozenY = y }
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
                s.tap(frozenX, frozenY)
            }
            resetDwell()
        }

        // ── 목표 좌표 ──
        val tx: Float
        val ty: Float
        if (useEye) {
            val (px, py) = eyeModel!!.predict(f)
            tx = px * w; ty = py * h
        } else {
            var dx = f.headX - nX
            var dy = f.headY - nY
            if (Settings.invertX) dx = -dx
            if (Settings.invertY) dy = -dy
            val g = Settings.headGain
            tx = w / 2 + dx * g * w
            ty = h / 2 + dy * g * VERTICAL_GAIN * h
        }

        // ── 스무딩 ──
        val stab = Settings.stability.toDouble()
        val minCut = (2.5 - 2.2 * stab) * (if (useEye) 0.6 else 1.0)
        fx.minCutoff = minCut; fy.minCutoff = minCut
        val beta = if (useEye) 0.002 else 0.004
        fx.beta = beta; fy.beta = beta
        x = fx.filter(tx.coerceIn(0f, w - 1).toDouble(), f.t).toFloat().coerceIn(0f, w - 1)
        y = fy.filter(ty.coerceIn(0f, h - 1).toDouble(), f.t).toFloat().coerceIn(0f, h - 1)

        // ── 응시 클릭 ──
        var progress = 0f
        val paused = EyeMouseState.paused
        if (!paused && Settings.clickMode != ClickMode.BLINK && f.t - startT > START_GRACE_MS) {
            val radius = (if (useEye) DWELL_RADIUS_EYE_DP else DWELL_RADIUS_HEAD_DP) * density
            if (hypot(x - anchorX, y - anchorY) > radius) {
                anchorX = x; anchorY = y; dwellStart = f.t; armed = true
            }
            if (armed) {
                val dms = Settings.dwellMs
                progress = ((f.t - dwellStart).toFloat() / dms).coerceIn(0f, 1f)
                if (f.t - dwellStart >= dms) {
                    s.tap(anchorX, anchorY)
                    armed = false   // 다시 누르려면 일단 벗어났다 돌아와야 함
                    progress = 0f
                }
            }
        }
        s.showCursor(x, y, if (paused) CursorView.State.PAUSED else CursorView.State.NORMAL, progress)
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
        private const val RECENTER_FRAMES = 15
        private const val VERTICAL_GAIN = 1.2f
        private const val BLINK_CLOSE_TH = 0.5f
        private const val BLINK_OPEN_TH = 0.35f
        private const val BLINK_MIN_MS = 300L   // 자연스러운 깜빡임(~150ms)은 무시
        private const val BLINK_MAX_MS = 1200L
        private const val PAUSE_MS = 2000L
        private const val NO_FACE_MS = 400L
        private const val START_GRACE_MS = 2000L
        private const val DWELL_RADIUS_HEAD_DP = 36f
        private const val DWELL_RADIUS_EYE_DP = 60f
    }
}
