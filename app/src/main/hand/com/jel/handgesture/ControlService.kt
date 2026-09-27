package com.jel.handgesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/** 동작 실행 (쓸기·시스템 동작·앱 실행·포인터 클릭) + 화면 표시 (상단 알림, 포인터) */
class ControlService : AccessibilityService() {

    companion object {
        @Volatile var instance: ControlService? = null
            private set
        private const val SEG = 24L
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("hand-a11y").apply { start() }
    private val work = Handler(worker.looper)
    private lateinit var wm: WindowManager
    private lateinit var audio: AudioManager
    private var hud: HudView? = null
    private var pointerView: PointerView? = null
    private var screenW = 1080
    private var screenH = 2520
    private var density = 3f
    private val hideHud = Runnable { hud?.visibility = View.GONE }
    private val drag = Dragger()

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        updateSize()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        clearOverlays()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        clearOverlays()
        worker.quitSafely()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::wm.isInitialized) updateSize()
    }

    private fun updateSize() {
        val p = Point()
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds; p.set(b.width(), b.height())
        } else {
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(p)
        }
        screenW = p.x; screenH = p.y
        density = resources.displayMetrics.density
        HandState.screenW = screenW
        HandState.screenH = screenH
        HandState.density = density
    }

    // ───────── 단축 동작 ─────────

    fun perform(a: GestureAction, label: String) {
        main.post {
            showHud(label)
            val half = Settings.swipeDistance.coerceIn(0.15f, 0.75f) / 2f
            when (a) {
                GestureAction.SWIPE_UP -> scroll(0.5f, 0.5f + half, 0.5f, 0.5f - half)
                GestureAction.SWIPE_DOWN -> scroll(0.5f, 0.5f - half, 0.5f, 0.5f + half)
                GestureAction.FLING_UP -> fling(0.5f, 0.78f, 0.5f, 0.22f, 130)
                GestureAction.FLING_DOWN -> fling(0.5f, 0.22f, 0.5f, 0.78f, 130)
                GestureAction.SWIPE_LEFT -> fling(0.85f, 0.5f, 0.15f, 0.5f, 200)
                GestureAction.SWIPE_RIGHT -> fling(0.15f, 0.5f, 0.85f, 0.5f, 200)
                GestureAction.BACK -> global(GLOBAL_ACTION_BACK)
                GestureAction.HOME -> global(GLOBAL_ACTION_HOME)
                GestureAction.RECENTS -> global(GLOBAL_ACTION_RECENTS)
                GestureAction.LAST_APP -> {
                    global(GLOBAL_ACTION_RECENTS)
                    main.postDelayed({ global(GLOBAL_ACTION_RECENTS) }, 280)
                }
                GestureAction.ALL_APPS -> {
                    if (Build.VERSION.SDK_INT >= 31) global(GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS) else global(GLOBAL_ACTION_HOME)
                }
                GestureAction.NOTIFICATIONS -> global(GLOBAL_ACTION_NOTIFICATIONS)
                GestureAction.QUICK_SETTINGS -> global(GLOBAL_ACTION_QUICK_SETTINGS)
                GestureAction.CLOSE_SHADE -> {
                    if (Build.VERSION.SDK_INT >= 31) global(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) else global(GLOBAL_ACTION_BACK)
                }
                GestureAction.SCREENSHOT -> { if (Build.VERSION.SDK_INT >= 28) global(GLOBAL_ACTION_TAKE_SCREENSHOT) }
                GestureAction.LOCK -> { if (Build.VERSION.SDK_INT >= 28) global(GLOBAL_ACTION_LOCK_SCREEN) }
                GestureAction.POWER_MENU -> global(GLOBAL_ACTION_POWER_DIALOG)
                GestureAction.SPLIT_SCREEN -> global(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                GestureAction.MEDIA_PLAY_PAUSE -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                GestureAction.MEDIA_NEXT -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                GestureAction.MEDIA_PREV -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                GestureAction.VOLUME_UP -> volume(AudioManager.ADJUST_RAISE)
                GestureAction.VOLUME_DOWN -> volume(AudioManager.ADJUST_LOWER)
                GestureAction.POINTER_MODE, GestureAction.NONE -> {}
            }
            Unit
        }
    }

    private fun global(action: Int) {
        drag.cancel()
        performGlobalAction(action)
    }

    private fun mediaKey(code: Int) {
        val t = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    private fun volume(dir: Int) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
    }

    fun launchApp(pkg: String, label: String) {
        main.post {
            val i = packageManager.getLaunchIntentForPackage(pkg)
            if (i == null) { showHud("앱을 찾을 수 없어요"); return@post }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            try { startActivity(i); showHud(label) } catch (e: Exception) { showHud("앱을 열 수 없어요") }
        }
    }

    private fun strokeOf(path: Path, dur: Long, cont: Boolean = false) =
        GestureDescription.StrokeDescription(path, 0, dur, cont)

    private fun dispatch(s: GestureDescription.StrokeDescription, cb: GestureResultCallback? = null): Boolean =
        try {
            dispatchGesture(GestureDescription.Builder().addStroke(s).build(), cb, main)
        } catch (e: Exception) {
            false
        }

    /** 빠르게 쓸어 넘기기 (관성 있음) */
    private fun fling(x0: Float, y0: Float, x1: Float, y1: Float, dur: Long) {
        drag.cancel()
        val p = Path().apply { moveTo(x0 * screenW, y0 * screenH); lineTo(x1 * screenW, y1 * screenH) }
        dispatch(strokeOf(p, dur))
    }

    /** 정해진 거리만 쓸기: 끝에서 잠깐 멈췄다 떼서 관성으로 더 밀려가지 않는다 */
    private fun scroll(x0: Float, y0: Float, x1: Float, y1: Float) {
        drag.cancel()
        val ex = x1 * screenW
        val ey = y1 * screenH
        val p = Path().apply { moveTo(x0 * screenW, y0 * screenH); lineTo(ex, ey) }
        val s1 = strokeOf(p, 240, true)
        dispatch(s1, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                val q = Path().apply { moveTo(ex, ey); lineTo(ex, ey + 1f) }
                try { dispatch(s1.continueStroke(q, 0, 140, false)) } catch (_: Exception) {}
            }
        })
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x.coerceAtLeast(0f), y.coerceAtLeast(0f)) }
        dispatch(strokeOf(path, 50))
    }

    // ───────── 쓸기 (휠) ─────────

    fun wheel(dx: Float, dy: Float, ax: Float, ay: Float) { main.post { drag.feed(dx, dy, ax, ay) } }

    fun wheelEnd(fling: Boolean) { main.post { drag.release(fling) } }

    /**
     * 손 움직임을 이어지는 터치 한 번으로 주입한다 (손을 떼기 전까지 손가락이 화면에 닿아 있는 것처럼).
     * 24ms 조각을 앞 조각이 끝나는 즉시 이어 붙여 손을 그대로 따라가고,
     * 화면 끝에 닿으면 떼었다가 반대쪽에서 다시 잡아 계속 쓸 수 있다.
     * 모두 메인 스레드에서만 다룬다.
     */
    private inner class Dragger {
        private var stroke: GestureDescription.StrokeDescription? = null
        private var busy = false
        private var x = 0f
        private var y = 0f
        private var pdx = 0f
        private var pdy = 0f
        private var ax = Float.NaN
        private var ay = Float.NaN
        private var axis = 0            // 0 자유 · 1 세로 · 2 가로
        private var releaseReq = false
        private var releaseFling = false
        private var vx = 0f
        private var vy = 0f
        private var gen = 0
        private val idle = Runnable { if (stroke != null || busy) release(false) }

        fun feed(dx: Float, dy: Float, sx: Float, sy: Float) {
            if (stroke == null && !busy && pdx == 0f && pdy == 0f) { ax = sx; ay = sy }
            pdx += dx; pdy += dy
            releaseReq = false
            main.removeCallbacks(idle)
            main.postDelayed(idle, 400)
            pump()
        }

        fun release(fling: Boolean) {
            main.removeCallbacks(idle)
            if (stroke == null && !busy) { reset(); return }
            releaseReq = true
            releaseFling = fling
            pump()
        }

        /** 다른 동작을 실행하기 전에 호출 (진행 중인 터치는 시스템이 취소한다) */
        fun cancel() {
            main.removeCallbacks(idle)
            gen++
            stroke = null
            busy = false
            reset()
        }

        private fun reset() {
            pdx = 0f; pdy = 0f; axis = 0; releaseReq = false; ax = Float.NaN; ay = Float.NaN
        }

        private fun lockAxis() {
            if (axis == 1) pdx = 0f else if (axis == 2) pdy = 0f
        }

        private fun pump() {
            if (busy) return
            val w = screenW.toFloat()
            val h = screenH.toFloat()
            val x0 = w * 0.08f; val x1 = w * 0.92f
            val y0 = h * 0.14f; val y1 = h * 0.88f
            val s = stroke
            if (s == null) {
                if (releaseReq) { reset(); return }
                val start = 12f * density
                if (abs(pdx) < start && abs(pdy) < start) return
                axis = if (!Settings.wheelAxisLock) 0 else if (abs(pdy) >= abs(pdx)) 1 else 2
                lockAxis()
                val sx: Float
                val sy: Float
                if (!ax.isNaN() && !ay.isNaN()) {
                    sx = ax.coerceIn(x0, x1); sy = ay.coerceIn(y0, y1)
                } else {
                    sx = if (abs(pdx) < 1f) w * 0.5f else if (pdx < 0f) w * 0.78f else w * 0.22f
                    sy = if (abs(pdy) < 1f) h * 0.5f else if (pdy < 0f) h * 0.74f else h * 0.26f
                }
                val tx = (sx + pdx).coerceIn(x0, x1)
                val ty = (sy + pdy).coerceIn(y0, y1)
                pdx -= tx - sx; pdy -= ty - sy
                vx = (tx - sx) / SEG; vy = (ty - sy) / SEG
                x = tx; y = ty
                ax = Float.NaN; ay = Float.NaN
                send(strokeOf(Path().apply { moveTo(sx, sy); lineTo(tx, ty) }, SEG, true))
                return
            }
            if (releaseReq) { finish(s, releaseFling, false); return }
            lockAxis()
            if (abs(pdx) < 1f && abs(pdy) < 1f) return
            val tx = (x + pdx).coerceIn(x0, x1)
            val ty = (y + pdy).coerceIn(y0, y1)
            if (abs(tx - x) < 0.5f && abs(ty - y) < 0.5f) {
                // 화면 끝: 떼고 반대쪽에서 다시 잡는다 (남은 이동량은 유지)
                finish(s, false, true)
                return
            }
            pdx -= tx - x; pdy -= ty - y
            vx = (tx - x) / SEG; vy = (ty - y) / SEG
            val path = Path().apply { moveTo(x, y); lineTo(tx, ty) }
            x = tx; y = ty
            send(s.continueStroke(path, 0, SEG, true))
        }

        /** 손 떼기. fling이면 마지막 속도 그대로 떼서 관성으로 미끄러지게, 아니면 멈춘 뒤 뗀다 */
        private fun finish(s: GestureDescription.StrokeDescription, fling: Boolean, keepPending: Boolean) {
            releaseReq = false
            val w = screenW.toFloat()
            val h = screenH.toFloat()
            val path = Path()
            val dur: Long
            path.moveTo(x, y)
            if (fling) {
                var mx = pdx; var my = pdy
                if (abs(mx) < 2f && abs(my) < 2f) { mx = vx * SEG; my = vy * SEG }
                var tx = (x + mx).coerceIn(0f, w - 1f)
                val ty = (y + my).coerceIn(0f, h - 1f)
                if (tx == x && ty == y) tx = if (x > 1f) x - 1f else x + 1f
                path.lineTo(tx, ty)
                dur = SEG
            } else {
                path.lineTo(x, if (y < h - 2f) y + 1f else y - 1f)
                dur = 150
            }
            if (!keepPending) { pdx = 0f; pdy = 0f }
            send(s.continueStroke(path, 0, dur, false))
        }

        private fun send(st: GestureDescription.StrokeDescription) {
            busy = true
            val g = gen
            val ok = dispatch(st, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (g != gen) return
                    busy = false
                    if (st.willContinue()) {
                        stroke = st
                    } else {
                        stroke = null; axis = 0
                    }
                    pump()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (g != gen) return
                    busy = false
                    stroke = null; axis = 0
                    if (releaseReq) reset()
                    pump()
                }
            })
            if (!ok) { busy = false; stroke = null; reset() }
        }
    }

    // ───────── 포인터 모드 ─────────

    fun pointerMode(on: Boolean) {
        main.post {
            drag.cancel()
            if (on) {
                if (pointerView == null) {
                    val v = PointerView(this)
                    val p = baseParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT).apply {
                        gravity = Gravity.TOP or Gravity.START
                    }
                    try { wm.addView(v, p); pointerView = v } catch (_: Exception) {}
                }
                showHud("🖐 포인터 모드 · 머무르면 클릭 · 손 모아 쓸기 · ✊ 유지로 끄기", 2600)
            } else {
                pointerView?.let { runCatching { wm.removeView(it) } }
                pointerView = null
                showHud("포인터 모드 끔")
            }
        }
    }

    fun pointers(p: PointerFrame?) { main.post { pointerView?.update(p) } }

    /** 후보 중 누를 수 있는 곳 위에 있는 포인터를 우선으로 한 곳만 클릭 */
    fun pointerClick(xs: FloatArray, ys: FloatArray) {
        if (xs.isEmpty()) return
        work.post {
            var pick = 0
            if (Settings.clickableFirst && xs.size > 1) {
                for (i in xs.indices) {
                    if (clickableAt(xs[i].toInt(), ys[i].toInt())) { pick = i; break }
                }
            }
            val x = xs[pick]
            val y = ys[pick]
            main.post {
                drag.cancel()
                pointerView?.ripple(x, y)
                tap(x, y)
            }
        }
    }

    private fun clickableAt(x: Int, y: Int): Boolean {
        val ws = try { windows } catch (e: Exception) { return false }
        val r = Rect()
        for (w in ws.sortedByDescending { it.layer }) {
            w.getBoundsInScreen(r)
            if (!r.contains(x, y)) continue
            val root = w.root ?: continue
            return hit(root, x, y, 0)
        }
        return false
    }

    private fun hit(n: AccessibilityNodeInfo, x: Int, y: Int, depth: Int): Boolean {
        if (depth > 40) return false
        val r = Rect()
        n.getBoundsInScreen(r)
        if (!r.contains(x, y) || !n.isVisibleToUser) return false
        if (n.isClickable || n.isLongClickable || n.isEditable || n.isCheckable) return true
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (hit(c, x, y, depth + 1)) return true
        }
        return false
    }

    // ───────── 상단 표시 ─────────

    fun progress(label: String?, p: Float) {
        main.post {
            if (!Settings.showHud) return@post
            if (label == null) { hud?.let { if (it.progress >= 0f) it.visibility = View.GONE; it.progress = -1f }; return@post }
            val v = hud ?: createHud() ?: return@post
            main.removeCallbacks(hideHud)
            v.text = label; v.progress = p
            v.visibility = View.VISIBLE
            v.requestLayout(); v.invalidate()
        }
    }

    private fun showHud(text: String, ms: Long = 1100) {
        if (!Settings.showHud) return
        val v = hud ?: createHud() ?: return
        v.text = text; v.progress = -1f
        v.visibility = View.VISIBLE
        v.requestLayout(); v.invalidate()
        main.removeCallbacks(hideHud)
        main.postDelayed(hideHud, ms)
    }

    private fun createHud(): HudView? {
        val v = HudView(this)
        val p = baseParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (72 * resources.displayMetrics.density).toInt()
        }
        return try { wm.addView(v, p); hud = v; v } catch (e: Exception) { null }
    }

    private fun baseParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    fun clearOverlays() {
        main.post {
            drag.cancel()
            hud?.let { runCatching { wm.removeView(it) } }
            pointerView?.let { runCatching { wm.removeView(it) } }
            hud = null; pointerView = null
        }
    }
}
