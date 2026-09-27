package com.jel.handgesture

import kotlin.math.abs

/** 인식 결과를 받아 실행하는 쪽 */
interface GestureSink {
    fun onTrigger(t: Trigger)
    fun onNumber(n: Int)
    fun onFocusEnter()
    fun onFocusMove(dx: Int, dy: Int)
    fun onFocusClick()
    fun onFocusExit()
    fun onProgress(label: String?, p: Float)
}

/**
 * 프레임 흐름 → 제스처 이벤트.
 *  - 움직임: 손바닥/브이/세 손가락을 빠르게 휘두르기 (손바닥 크기 기준 이동량이라 거리와 무관)
 *  - 미세 동작: 검지만 편 채 손끝을 살짝 튕기기 → 파란 선택 띠 이동, 엄지·검지 집기 → 선택
 *  - 유지: 손 모양을 멈춘 채 유지 → 단축 동작, 손가락 수 유지 → 숫자 바로가기
 * 되돌아오는 손짓(반대 방향)은 0.7초 동안 무시해 한 번 휘두르면 한 번만 실행된다.
 */
class GestureEngine(private val sink: GestureSink) {

    private class S(val t: Long, val px: Float, val py: Float, val tx: Float, val ty: Float, val size: Float, val group: Int)

    private val hist = ArrayDeque<S>()
    private var cooldownUntil = 0L
    private var lastDir = -1
    private var lastDirT = 0L

    private var pose = Pose.NONE
    private var poseSince = 0L
    private var poseFired = false
    private var count = 0
    private var countSince = 0L
    private var countFired = false

    @Volatile var focusActive = false
        private set
    private var lastFlickT = 0L
    private var lastFocusActivity = 0L
    private var lastHandT = 0L
    private var wasPinch = false
    private var progressShown = false

    fun onFrame(f: HandFrame) {
        val t = f.t
        lastHandT = t
        val group = groupOf(f.pose)
        hist.addLast(S(t, f.palmX, f.palmY, f.tipX, f.tipY, f.palmSize, group))
        while (hist.isNotEmpty() && t - hist.first().t > 600) hist.removeFirst()

        // 파란 띠 모드: 집기 = 선택
        val pinchNow = f.pose == Pose.PINCH
        if (focusActive && pinchNow && !wasPinch && t >= cooldownUntil) {
            sink.onFocusClick()
            lastFocusActivity = t
            cooldownUntil = t + 300
        }
        wasPinch = pinchNow
        if (t < cooldownUntil) { hideProgress(); return }

        // 1) 휘두르기
        val sw = swipe(t, group)
        if (sw != null) {
            if (focusActive) exitFocus()
            sink.onTrigger(sw)
            fired(t, 450)
            return
        }

        // 2) 검지 튕기기 → 파란 띠 이동
        if (Settings.focusEnabled && f.pose == Pose.POINT) {
            val dir = flick(t)
            if (dir != null) {
                if (!focusActive) {
                    focusActive = true
                    sink.onFocusEnter()
                } else {
                    sink.onFocusMove(dir.first, dir.second)
                }
                lastFlickT = t
                lastFocusActivity = t
                trimTips()
                resetHolds(t)
                hideProgress()
                return
            }
        }

        // 3) 멈춰서 유지하는 동작
        if (f.pose != pose) { pose = f.pose; poseSince = t; poseFired = false }
        if (f.count != count) { count = f.count; countSince = t; countFired = false }
        if (!isStill(t)) { poseSince = t; countSince = t; hideProgress(); return }

        if (focusActive) {
            if ((group == 1 && t - poseSince > 500) || t - lastFocusActivity > 10_000) exitFocus()
            hideProgress()
            return
        }

        val trig = when (pose) {
            Pose.THUMB_UP -> Trigger.THUMB_UP
            Pose.THUMB_DOWN -> Trigger.THUMB_DOWN
            Pose.SHAKA -> Trigger.SHAKA
            Pose.OK -> Trigger.OK
            Pose.FIST -> Trigger.FIST
            else -> null
        }
        if (trig != null) {
            val act = Settings.action(trig)
            if (act != GestureAction.NONE && !poseFired) {
                val p = (t - poseSince).toFloat() / Settings.holdMs
                showProgress("${trig.short} ${act.label}", p)
                if (p >= 1f) {
                    poseFired = true
                    hideProgress()
                    sink.onTrigger(trig)
                    fired(t, 400)
                }
                return
            }
        }

        if (Settings.numbersEnabled && count in 1..5 && !countFired) {
            val pkg = Settings.app(count)
            if (pkg != null) {
                val p = (t - countSince).toFloat() / Settings.numberHoldMs
                showProgress("$count → ${Settings.appLabel(count) ?: pkg}", p)
                if (p >= 1f) {
                    countFired = true
                    hideProgress()
                    sink.onNumber(count)
                    fired(t, 600)
                }
                return
            }
        }
        hideProgress()
    }

    fun onNoHand(t: Long) {
        if (hist.isNotEmpty()) hist.clear()
        pose = Pose.NONE; count = 0
        poseFired = false; countFired = false
        wasPinch = false
        hideProgress()
        if (focusActive && t - lastHandT > 5000) exitFocus()
    }

    fun exitFocus() {
        if (!focusActive) return
        focusActive = false
        sink.onFocusExit()
    }

    private fun groupOf(p: Pose) = when (p) {
        Pose.OPEN, Pose.FOUR -> 1
        Pose.V -> 2
        Pose.THREE -> 3
        Pose.POINT -> 4
        else -> 0
    }

    private fun fired(t: Long, cooldown: Long) {
        cooldownUntil = t + cooldown
        hist.clear()
        resetHolds(t)
    }

    private fun resetHolds(t: Long) {
        poseSince = t; countSince = t
        poseFired = true; countFired = true   // 같은 모양을 유지해도 다시 실행되지 않게 (모양이 바뀌면 풀림)
    }

    /** 휘두르기 판정: 최근 280ms 동안 같은 손 모양으로 손바닥 크기 × 임계값 이상 이동 */
    private fun swipe(t: Long, group: Int): Trigger? {
        if (group !in 1..3) return null
        var n = 0; var same = 0
        var first: S? = null
        var sizeSum = 0f
        for (s in hist) {
            if (t - s.t > SWIPE_WIN) continue
            n++
            if (s.group == group) { same++; if (first == null) first = s; sizeSum += s.size }
        }
        if (n < 5 || same < n * 0.7f || first == null) return null
        val last = hist.last()
        val size = sizeSum / same
        val dx = (last.px - first.px) / size
        val dy = (last.py - first.py) / size
        val sens = Settings.sensitivity
        val dir: Int = when {
            group == 1 && abs(dx) > 1.6f * abs(dy) && abs(dx) > 1.4f / sens -> if (dx > 0) RIGHT else LEFT
            abs(dy) > 1.6f * abs(dx) && abs(dy) > (if (group == 1) 1.2f else 1.0f) / sens -> if (dy > 0) DOWN else UP
            else -> return null
        }
        // 되돌아오는 손짓 무시
        if (t - lastDirT < 700 && dir == opposite(lastDir)) return null
        lastDir = dir; lastDirT = t
        return when (group) {
            1 -> when (dir) { UP -> Trigger.PALM_UP; DOWN -> Trigger.PALM_DOWN; LEFT -> Trigger.PALM_LEFT; else -> Trigger.PALM_RIGHT }
            2 -> if (dir == UP) Trigger.V_UP else Trigger.V_DOWN
            else -> if (dir == UP) Trigger.THREE_UP else Trigger.THREE_DOWN
        }
    }

    /** 검지 끝 튕기기: 160ms 안에 손바닥 크기 × 0.5 이상 한 방향 이동 */
    private fun flick(t: Long): Pair<Int, Int>? {
        if (t - lastFlickT < 180) return null
        var first: S? = null
        var n = 0
        for (s in hist) {
            if (t - s.t > FLICK_WIN || s.group != 4) continue
            if (first == null) first = s
            n++
        }
        if (n < 3 || first == null) return null
        val last = hist.last()
        val dx = (last.tx - first.tx) / last.size
        val dy = (last.ty - first.ty) / last.size
        val th = 0.5f / Settings.sensitivity
        return when {
            abs(dx) > 1.4f * abs(dy) && abs(dx) > th -> (if (dx > 0) 1 else -1) to 0
            abs(dy) > 1.4f * abs(dx) && abs(dy) > th -> 0 to (if (dy > 0) 1 else -1)
            else -> null
        }
    }

    private fun trimTips() {
        val last = hist.lastOrNull() ?: return
        hist.clear(); hist.addLast(last)
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
        private const val SWIPE_WIN = 280L
        private const val FLICK_WIN = 160L
        const val UP = 0
        const val DOWN = 1
        const val LEFT = 2
        const val RIGHT = 3
        private fun opposite(d: Int) = when (d) { UP -> DOWN; DOWN -> UP; LEFT -> RIGHT; RIGHT -> LEFT; else -> -1 }
    }
}
