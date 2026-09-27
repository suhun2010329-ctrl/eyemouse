package com.jel.handgesture

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** 인식 결과를 받아 실행하는 쪽 */
interface GestureSink {
    fun onTrigger(t: Trigger)
    fun onNumber(n: Int)
    fun onProgress(label: String?, p: Float)
    /** 쓸기(휠) 이동량 (화면 px). ax, ay = 잡기 시작할 화면 위치 (NaN이면 자동) */
    fun onWheel(dx: Float, dy: Float, ax: Float, ay: Float)
    fun onWheelEnd(fling: Boolean)
    fun onPointerMode(on: Boolean)
    fun onPointers(p: PointerFrame?)
    /** 머무름 클릭 후보 (화면 px, 우선순위 순) */
    fun onPointerClick(xs: FloatArray, ys: FloatArray)
}

/** 포인터 표시용 (화면 px) */
class PointerFrame(
    val x: FloatArray,
    val y: FloatArray,
    val on: BooleanArray,
    val progress: FloatArray,
    val grab: Boolean,
)

/** 1€ 필터: 느릴 땐 떨림을 강하게 줄이고, 빠를 땐 지연 없이 따라간다 */
class OneEuro(var minCutoff: Float, var beta: Float, private val dCutoff: Float = 1f) {
    private var x = 0f
    private var dx = 0f
    private var lastT = 0L
    private var init = false

    fun reset() { init = false }

    fun filter(v: Float, t: Long): Float {
        if (!init) { init = true; x = v; dx = 0f; lastT = t; return v }
        val dt = (t - lastT).coerceAtLeast(1L) / 1000f
        lastT = t
        dx += alpha(dCutoff, dt) * ((v - x) / dt - dx)
        x += alpha(minCutoff + beta * abs(dx), dt) * (v - x)
        return x
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }
}

/**
 * 프레임 흐름 → 제스처 이벤트.
 *  [제스처 모드]
 *  - 쓸기(휠): 손을 모으거나(기본) 검지를 편 채 움직이면 화면이 손을 따라 바로 스크롤된다 (포인터 없음)
 *  - 휘두르기: 손바닥 / 검지 / 두 손가락 / 세 손가락 × 상하좌우, 손바닥 밀기·당기기
 *    → 속도와 거리가 기준을 넘는 순간 바로 실행 (보통 손을 움직이기 시작하고 0.1초 안팎)
 *  - 유지: 손 모양을 멈춘 채 유지 → 단축 동작, 손가락 수 유지 → 숫자 바로가기
 *  [포인터 모드]
 *  - 편 손가락 끝마다 포인터 (최대 5개), 한 곳에 머무르면 클릭, 손을 모아 움직이면 쓸기, ✊ 유지 → 종료
 *
 * 손 모양은 여러 프레임 연속 같아야 확정되고, 되돌아오는 손짓(반대 방향)은 무시한다.
 */
class GestureEngine(private val sink: GestureSink) {

    private class S(val t: Long, val px: Float, val py: Float, val tx: Float, val ty: Float, val size: Float, val group: Int)

    private val hist = ArrayDeque<S>()
    private val raw = ArrayDeque<Pose>()
    private var stable = Pose.NONE
    private var stableSince = 0L
    private var poseFired = false
    private var count = 0
    private var countSince = 0L
    private var countFired = false
    private var holdFrom = 0L

    private var cooldownUntil = 0L
    private var quietUntil = 0L
    private var lastDir = -1
    private var lastDirT = 0L
    private var progressShown = false

    // 쓸기(휠)
    private var wheelOn = false
    private var wheelPrevX = 0f
    private var wheelPrevY = 0f
    private var wheelSize = 0f
    private var wheelVX = 0f
    private var wheelVY = 0f
    private var wheelSentX = 0f
    private var wheelSentY = 0f
    private var wheelAX = Float.NaN
    private var wheelAY = Float.NaN
    private val wfx = OneEuro(2f, 6f)
    private val wfy = OneEuro(2f, 6f)

    // 포인터
    @Volatile var pointerMode = false
        private set
    @Volatile private var pointerReq = 0
    private val fx = Array(6) { OneEuro(1f, 3f) }
    private val fy = Array(6) { OneEuro(1f, 3f) }
    private val ancX = FloatArray(5)
    private val ancY = FloatArray(5)
    private val ancT = LongArray(5)
    private val ancSet = BooleanArray(5)
    private val armed = BooleanArray(5) { true }
    private var pointersShown = false
    /** 주먹으로 켠 직후 같은 주먹으로 바로 꺼지지 않게 */
    private var fistLock = false

    // ───────── 외부 요청 (메인 스레드에서 호출 가능) ─────────

    /** null = 전환, true/false = 켜기/끄기 */
    fun requestPointer(on: Boolean?) {
        pointerReq = when (on) { null -> 1; true -> 2; false -> 3 }
    }

    /** 카메라가 멈출 때 등: 쓸기 끝내고 표시 정리 */
    fun release() {
        endWheel(0L, false)
        hidePointers()
        hideProgress()
    }

    // ───────── 프레임 처리 ─────────

    fun onFrame(f: HandFrame) {
        val t = f.t
        applyRequest()

        // 손 모양 확정: 최근 N프레임이 모두 같을 때만 바뀐다
        raw.addLast(f.pose)
        while (raw.size > 8) raw.removeFirst()
        val need = Settings.poseFrames.coerceIn(1, 8)
        if (f.pose != stable && raw.size >= need && lastSame(need, f.pose)) {
            stable = f.pose; stableSince = t; poseFired = false
        }
        HandState.stablePose = stable
        if (f.pose == stable && f.count != count) { count = f.count; countSince = t; countFired = false }

        hist.addLast(S(t, f.palmX, f.palmY, f.tipX, f.tipY, f.palmSize, groupOf(f.pose)))
        while (hist.isNotEmpty() && t - hist.first().t > 600) hist.removeFirst()

        if (pointerMode) { pointerStep(f, t); return }

        // 1) 쓸기(휠)
        if (wheelPose(stable)) { gestureWheel(f, t); return }
        if (wheelOn) endWheel(t, true)

        if (t < cooldownUntil || t < quietUntil) { hideProgress(); return }

        // 2) 휘두르기 · 밀기
        val sw = swipe(t) ?: push(t)
        if (sw != null) { fire(sw, t); return }

        // 3) 멈춰서 유지
        if (!isStill(t)) { holdFrom = t; hideProgress(); return }
        val holdStart = max(stableSince, holdFrom)

        val trig = holdTrigger(stable)
        if (trig != null) {
            val act = Settings.action(trig)
            if (act != GestureAction.NONE && !poseFired) {
                val p = (t - holdStart).toFloat() / Settings.holdMs
                showProgress("${trig.short} ${act.label}", p)
                if (p >= 1f) { hideProgress(); fire(trig, t) }
                return
            }
        }

        if (Settings.numbersEnabled && count in 1..5 && !countFired) {
            val pkg = Settings.app(count)
            if (pkg != null) {
                val p = (t - max(countSince, holdStart)).toFloat() / Settings.numberHoldMs
                showProgress("$count → ${Settings.appLabel(count) ?: pkg}", p)
                if (p >= 1f) {
                    hideProgress()
                    sink.onNumber(count)
                    cooldownUntil = t + 600
                    hist.clear()
                    resetHolds()
                }
                return
            }
        }
        hideProgress()
    }

    fun onNoHand(t: Long) {
        applyRequest()
        if (hist.isNotEmpty()) hist.clear()
        raw.clear()
        stable = Pose.NONE; count = 0
        HandState.stablePose = Pose.NONE
        poseFired = false; countFired = false
        endWheel(t, false)
        hideProgress()
        hidePointers()
        for (k in 0 until 6) { fx[k].reset(); fy[k].reset() }
        ancSet.fill(false)
    }

    private fun lastSame(n: Int, p: Pose): Boolean {
        var k = 0
        for (i in raw.indices.reversed()) {
            if (raw[i] != p) return false
            if (++k >= n) return true
        }
        return false
    }

    private fun applyRequest() {
        val r = pointerReq
        if (r == 0) return
        pointerReq = 0
        setPointer(when (r) { 1 -> !pointerMode; 2 -> true; else -> false })
    }

    private fun setPointer(on: Boolean) {
        if (pointerMode == on) return
        endWheel(0L, false)
        hidePointers()
        hideProgress()
        pointerMode = on
        HandState.pointerMode.value = on
        for (k in 0 until 6) { fx[k].reset(); fy[k].reset() }
        ancSet.fill(false); armed.fill(true)
        fistLock = true
        resetHolds()
        sink.onPointerMode(on)
    }

    private fun fire(trig: Trigger, t: Long) {
        if (Settings.action(trig) == GestureAction.POINTER_MODE) setPointer(!pointerMode) else sink.onTrigger(trig)
        cooldownUntil = t + Settings.cooldownMs
        hist.clear()
        resetHolds()
    }

    /** 같은 모양을 계속 유지해도 다시 실행되지 않게 (모양이 바뀌면 풀림) */
    private fun resetHolds() { poseFired = true; countFired = true }

    private fun groupOf(p: Pose) = when (p) {
        Pose.OPEN, Pose.FOUR -> 1
        Pose.V -> 2
        Pose.THREE -> 3
        Pose.POINT -> 4
        else -> 0
    }

    private fun holdTrigger(p: Pose): Trigger? = when (p) {
        Pose.THUMB_UP -> Trigger.THUMB_UP
        Pose.THUMB_DOWN -> Trigger.THUMB_DOWN
        Pose.SHAKA -> Trigger.SHAKA
        Pose.OK -> Trigger.OK
        Pose.FIST -> Trigger.FIST
        Pose.ROCK -> Trigger.ROCK
        Pose.LOVE -> Trigger.LOVE
        Pose.GUN -> Trigger.GUN
        else -> null
    }

    // ───────── 쓸기(휠) ─────────

    private fun wheelPose(p: Pose): Boolean {
        if (!Settings.wheelEnabled) return false
        return when (Settings.wheelPose) {
            0 -> p == Pose.GATHER
            1 -> p == Pose.POINT
            else -> p == Pose.GATHER || p == Pose.POINT
        }
    }

    /** 제스처 모드 쓸기: 손바닥 크기 기준 이동량 → 화면 px (거리와 무관) */
    private fun gestureWheel(f: HandFrame, t: Long) {
        val useTip = stable == Pose.POINT
        val x = wfx.filter(if (useTip) f.tipX else f.palmX, t)
        val y = wfy.filter(if (useTip) f.tipY else f.palmY, t)
        if (!wheelOn) {
            startWheel(Float.NaN, Float.NaN)
            wheelPrevX = x; wheelPrevY = y; wheelSize = f.palmSize
            return
        }
        wheelSize += 0.15f * (f.palmSize - wheelSize)
        val dx = (x - wheelPrevX) / wheelSize
        val dy = (y - wheelPrevY) / wheelSize
        wheelPrevX = x; wheelPrevY = y
        if (abs(dx) > 1.5f || abs(dy) > 1.5f) return   // 인식 튐
        val s = if (Settings.wheelReverse) -1f else 1f
        val g = Settings.wheelGain
        if (Settings.wheelHorizontal) wheelVX += dx * g * 0.6f * HandState.screenW * s
        wheelVY += dy * g * 0.35f * HandState.screenH * s
        sendWheel()
    }

    /** 포인터 모드 쓸기: 모은 손끝 위치를 그대로 잡고 끈다 */
    private fun grabWheel(gx: Float, gy: Float) {
        if (!wheelOn) {
            startWheel(gx, gy)
            wheelVX = gx; wheelVY = gy; wheelSentX = gx; wheelSentY = gy
            return
        }
        wheelVX = gx; wheelVY = gy
        sendWheel()
    }

    private fun startWheel(ax: Float, ay: Float) {
        wheelOn = true
        HandState.wheel = true
        wheelVX = 0f; wheelVY = 0f; wheelSentX = 0f; wheelSentY = 0f
        wheelAX = ax; wheelAY = ay
        hideProgress()
    }

    private fun sendWheel() {
        val dx = wheelVX - wheelSentX
        val dy = wheelVY - wheelSentY
        if (abs(dx) + abs(dy) < 1.5f) return
        // 포인터 모드 잡기: 새로 잡을 때는 이번 이동이 시작된 지점(=직전 위치)을 누른다
        val ax = if (wheelAX.isNaN()) Float.NaN else wheelSentX
        val ay = if (wheelAY.isNaN()) Float.NaN else wheelSentY
        wheelSentX = wheelVX; wheelSentY = wheelVY
        sink.onWheel(dx, dy, ax, ay)
    }

    private fun endWheel(t: Long, allowFling: Boolean) {
        if (!wheelOn) return
        wheelOn = false
        HandState.wheel = false
        wfx.reset(); wfy.reset()
        val fling = allowFling && Settings.wheelInertia && recentSpeed(t) > 2.5f
        sink.onWheelEnd(fling)
        if (t > 0L) quietUntil = t + 300
        hist.clear()
    }

    /** 최근 120ms 손 이동 속도 (손바닥 크기/초) */
    private fun recentSpeed(t: Long): Float {
        if (hist.size < 2) return 0f
        val last = hist.last()
        var first: S? = null
        for (s in hist) if (t - s.t <= 120) { first = s; break }
        if (first == null || first === last) return 0f
        val dt = (last.t - first.t).coerceAtLeast(16L) / 1000f
        return hypot(last.px - first.px, last.py - first.py) / last.size / dt
    }

    // ───────── 휘두르기 ─────────

    /**
     * 최근 260ms 안에서 가장 많은 손 모양 그룹으로, 손바닥 크기 × 기준 이상을 기준 속도 이상으로 움직이면
     * 그 순간 바로 실행. 검지는 손끝, 나머지는 손바닥 중심으로 잰다.
     */
    private fun swipe(t: Long): Trigger? {
        if (hist.size < 4) return null
        val cnt = IntArray(5)
        var n = 0
        for (s in hist) if (t - s.t <= SWIPE_WIN) { n++; cnt[s.group]++ }
        var g = 1
        for (k in 2..4) if (cnt[k] > cnt[g]) g = k
        if (cnt[g] < 4 || cnt[g] < n * 0.6f) return null
        if (g == 4 && (stable != Pose.POINT || wheelPose(Pose.POINT))) return null

        var first: S? = null
        var last: S? = null
        var sizeSum = 0f
        var m = 0
        for (s in hist) {
            if (t - s.t > SWIPE_WIN || s.group != g) continue
            if (g == 4 && s.t < stableSince + 100) continue   // 검지를 펴는 동작 자체는 무시
            if (first == null) first = s
            last = s
            sizeSum += s.size; m++
        }
        if (first == null || last == null || m < 3 || first === last) return null
        val size = sizeSum / m
        val tip = g == 4
        val dx = ((if (tip) last.tx else last.px) - (if (tip) first.tx else first.px)) / size
        val dy = ((if (tip) last.ty else last.py) - (if (tip) first.ty else first.py)) / size
        val dt = (last.t - first.t).coerceAtLeast(16L) / 1000f
        val dist = hypot(dx, dy)
        val th = (if (g == 1) 0.9f else 0.75f) / Settings.sensitivity
        val vMin = 3f / Settings.speedSens
        if (dist < th || dist / dt < vMin) return null
        val dir = when {
            abs(dx) > 1.5f * abs(dy) -> if (dx > 0) RIGHT else LEFT
            abs(dy) > 1.5f * abs(dx) -> if (dy > 0) DOWN else UP
            else -> return null
        }
        if (t - lastDirT < Settings.reboundMs && dir == opposite(lastDir)) return null
        val trig = SWIPES[g - 1][dir]
        if (Settings.action(trig) == GestureAction.NONE) return null
        lastDir = dir; lastDirT = t
        return trig
    }

    /** 손바닥을 카메라 쪽으로 밀기(커짐) / 당기기(작아짐) */
    private fun push(t: Long): Trigger? {
        val pushOn = Settings.action(Trigger.PALM_PUSH) != GestureAction.NONE
        val pullOn = Settings.action(Trigger.PALM_PULL) != GestureAction.NONE
        if (!pushOn && !pullOn) return null
        var first: S? = null
        var last: S? = null
        var n = 0; var m = 0
        for (s in hist) {
            if (t - s.t > 320) continue
            n++
            if (s.group != 1) continue
            if (first == null) first = s
            last = s; m++
        }
        if (first == null || last == null || m < 5 || m < n * 0.7f) return null
        val avg = (first.size + last.size) / 2f
        if (hypot(last.px - first.px, last.py - first.py) / avg > 0.6f) return null
        val ratio = last.size / first.size
        val k = Settings.sensitivity.coerceIn(0.5f, 2f)
        return when {
            pushOn && ratio > 1f + 0.3f / k -> Trigger.PALM_PUSH
            pullOn && ratio < 1f / (1f + 0.3f / k) -> Trigger.PALM_PULL
            else -> null
        }
    }

    /** 최근 200ms 손바닥 이동이 손바닥 크기의 30% 미만 */
    private fun isStill(t: Long): Boolean {
        var first: S? = null
        for (s in hist) if (t - s.t <= 200) { first = s; break }
        val last = hist.lastOrNull() ?: return false
        if (first == null) return true
        val dx = (last.px - first.px) / last.size
        val dy = (last.py - first.py) / last.size
        return dx * dx + dy * dy < 0.09f
    }

    // ───────── 포인터 모드 ─────────

    private fun pointerStep(f: HandFrame, t: Long) {
        // ✊ 멈춰서 유지 → 포인터 모드 끄기
        if (stable == Pose.FIST) {
            endWheel(t, false)
            hidePointers()
            if (fistLock) { hideProgress(); return }
            if (!isStill(t)) holdFrom = t
            val p = (t - max(stableSince, holdFrom)).toFloat() / Settings.holdMs
            showProgress("✊ 포인터 모드 끄기", p)
            if (p >= 1f) { hideProgress(); setPointer(false); cooldownUntil = t + 500 }
            return
        }
        fistLock = false
        hideProgress()

        val w = HandState.screenW.toFloat()
        val h = HandState.screenH.toFloat()
        val asp = HandState.aspect
        val range = Settings.pointerRange.coerceIn(0.3f, 1f)
        val mc = 3f - 2.7f * Settings.pointerSmooth.coerceIn(0f, 1f)
        for (k in 0 until 6) { fx[k].minCutoff = mc; fy[k].minCutoff = mc }

        // 손 모으기 → 그 자리를 잡고 쓸기
        if (stable == Pose.GATHER) {
            var sx = 0f; var sy = 0f
            for (i in 0 until 5) { sx += f.pts[TIP[i] * 2]; sy += f.pts[TIP[i] * 2 + 1] }
            val gx = fx[5].filter(map(sx / 5f, range), t) * w
            val gy = fy[5].filter(map(sy / 5f * asp, range), t) * h
            grabWheel(gx, gy)
            val xs = FloatArray(5); val ys = FloatArray(5)
            xs[0] = gx; ys[0] = gy
            showPointers(PointerFrame(xs, ys, BooleanArray(5), FloatArray(5), true))
            ancSet.fill(false)
            return
        }
        if (wheelOn) endWheel(t, true)
        fx[5].reset(); fy[5].reset()

        val radius = Settings.dwellRadiusDp * HandState.density
        val mask = Settings.pointerFingers
        val dwell = Settings.dwellMs.coerceAtLeast(200)
        val xs = FloatArray(5); val ys = FloatArray(5)
        val on = BooleanArray(5); val prog = FloatArray(5)
        var done = false
        for (i in 0 until 5) {
            val x = fx[i].filter(map(f.pts[TIP[i] * 2], range), t) * w
            val y = fy[i].filter(map(f.pts[TIP[i] * 2 + 1] * asp, range), t) * h
            xs[i] = x; ys[i] = y
            on[i] = f.ext[i] && ((mask shr i) and 1) == 1
            if (!on[i]) { ancSet[i] = false; continue }
            if (!ancSet[i] || hypot(x - ancX[i], y - ancY[i]) > radius) {
                ancX[i] = x; ancY[i] = y; ancT[i] = t
                ancSet[i] = true; armed[i] = true
            }
            if (armed[i] && Settings.dwellClick) {
                prog[i] = ((t - ancT[i]).toFloat() / dwell).coerceIn(0f, 1f)
                if (prog[i] >= 1f) done = true
            }
        }
        if (done) {
            val cand = ORDER.filter { on[it] && prog[it] >= 0.8f }
            sink.onPointerClick(FloatArray(cand.size) { xs[cand[it]] }, FloatArray(cand.size) { ys[cand[it]] })
            for (i in 0 until 5) { armed[i] = false; prog[i] = 0f }
            HandState.lastEvent = "포인터 클릭"
        }
        showPointers(PointerFrame(xs, ys, on, prog, false))
    }

    /** 카메라 좌표(0~1) → 화면 비율(0~1): 가운데 range 구간이 화면 전체에 대응 */
    private fun map(v: Float, range: Float) = ((v - 0.5f) / range + 0.5f).coerceIn(0f, 1f)

    private fun showPointers(p: PointerFrame) {
        pointersShown = true
        sink.onPointers(p)
    }

    private fun hidePointers() {
        if (!pointersShown) return
        pointersShown = false
        sink.onPointers(null)
    }

    private fun showProgress(label: String, p: Float) {
        progressShown = true
        sink.onProgress(label, p.coerceIn(0f, 1f))
    }

    private fun hideProgress() {
        if (!progressShown) return
        progressShown = false
        sink.onProgress(null, 0f)
    }

    companion object {
        private const val SWIPE_WIN = 260L
        const val UP = 0
        const val DOWN = 1
        const val LEFT = 2
        const val RIGHT = 3
        private val TIP = intArrayOf(4, 8, 12, 16, 20)
        /** 여러 포인터가 동시에 머무를 때 클릭 우선순위: 검지 > 중지 > 엄지 > 약지 > 새끼 */
        private val ORDER = listOf(1, 2, 0, 3, 4)
        private val SWIPES = arrayOf(
            arrayOf(Trigger.PALM_UP, Trigger.PALM_DOWN, Trigger.PALM_LEFT, Trigger.PALM_RIGHT),
            arrayOf(Trigger.V_UP, Trigger.V_DOWN, Trigger.V_LEFT, Trigger.V_RIGHT),
            arrayOf(Trigger.THREE_UP, Trigger.THREE_DOWN, Trigger.THREE_LEFT, Trigger.THREE_RIGHT),
            arrayOf(Trigger.INDEX_UP, Trigger.INDEX_DOWN, Trigger.INDEX_LEFT, Trigger.INDEX_RIGHT),
        )
        private fun opposite(d: Int) = when (d) { UP -> DOWN; DOWN -> UP; LEFT -> RIGHT; RIGHT -> LEFT; else -> -1 }
    }
}
