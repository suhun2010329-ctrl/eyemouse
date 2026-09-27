package com.jel.handgesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/** 동작 실행 (스와이프·시스템 동작·앱 실행) + 화면 표시(상단 알림, 파란 선택 띠) */
class ControlService : AccessibilityService() {

    companion object {
        @Volatile var instance: ControlService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("hand-a11y").apply { start() }
    private val work = Handler(worker.looper)
    private lateinit var wm: WindowManager
    private var hud: HudView? = null
    private var hudLp: WindowManager.LayoutParams? = null
    private var focusView: FocusView? = null
    private lateinit var nav: FocusNavigator
    private var screenW = 1080
    private var screenH = 2520
    private val hideHud = Runnable { hud?.visibility = View.GONE }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        nav = FocusNavigator(this)
        updateSize()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        main.post { clearOverlays() }
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
    }

    // ───────── 동작 실행 ─────────

    fun perform(a: GestureAction, label: String) {
        main.post {
            showHud(label)
            when (a) {
                GestureAction.SWIPE_UP -> swipe(0.5f, 0.72f, 0.5f, 0.28f)
                GestureAction.SWIPE_DOWN -> swipe(0.5f, 0.28f, 0.5f, 0.72f)
                GestureAction.SWIPE_LEFT -> swipe(0.8f, 0.5f, 0.2f, 0.5f)
                GestureAction.SWIPE_RIGHT -> swipe(0.2f, 0.5f, 0.8f, 0.5f)
                GestureAction.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                GestureAction.QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                GestureAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
                GestureAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                GestureAction.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                GestureAction.SCREENSHOT -> { if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) }
                GestureAction.LOCK -> { if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) }
                GestureAction.NONE -> {}
            }
            Unit
        }
    }

    fun launchApp(pkg: String, label: String) {
        main.post {
            val i = packageManager.getLaunchIntentForPackage(pkg)
            if (i == null) { showHud("앱을 찾을 수 없어요"); return@post }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            try { startActivity(i); showHud(label) } catch (e: Exception) { showHud("앱을 열 수 없어요") }
        }
    }

    private fun swipe(x0: Float, y0: Float, x1: Float, y1: Float, dur: Long = 220) {
        val path = Path().apply {
            moveTo(x0 * screenW, y0 * screenH)
            lineTo(x1 * screenW, y1 * screenH)
        }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build(), null, null)
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build(), null, null)
    }

    // ───────── 파란 선택 띠 ─────────

    fun focusEnter() {
        work.post {
            val r = nav.enter(screenW, screenH)
            main.post { if (r != null) { showFocus(r); showHud("☝ 선택 모드 · 검지 튕기기로 이동, 집기로 선택") } else showHud("선택할 항목이 없어요") }
        }
    }

    fun focusMove(dx: Int, dy: Int) {
        work.post {
            val r = nav.move(dx, dy)
            if (r != null) { main.post { showFocus(r) }; return@post }
            // 끝에 닿으면 그 방향으로 화면을 넘긴 뒤 가까운 요소를 다시 찾는다
            main.post {
                when {
                    dy > 0 -> swipe(0.5f, 0.7f, 0.5f, 0.35f, 250)
                    dy < 0 -> swipe(0.5f, 0.35f, 0.5f, 0.7f, 250)
                    dx > 0 -> swipe(0.8f, 0.5f, 0.2f, 0.5f)
                    else -> swipe(0.2f, 0.5f, 0.8f, 0.5f)
                }
            }
            work.postDelayed({
                val r2 = nav.relocate()
                if (r2 != null) main.post { showFocus(r2) }
            }, 450)
        }
    }

    fun focusClick() {
        work.post {
            val ok = nav.click()
            val r = nav.current
            main.post {
                if (!ok && r != null) tap(r.exactCenterX(), r.exactCenterY())
                showHud("🤏 선택")
            }
            // 화면이 바뀌므로 잠시 후 새 화면에서 다시 잡는다
            work.postDelayed({
                val r2 = nav.enter(screenW, screenH)
                main.post { if (r2 != null) showFocus(r2) }
            }, 600)
        }
    }

    fun focusExit() {
        work.post { nav.reset() }
        main.post { focusView?.clear() }
    }

    private fun showFocus(r: Rect) {
        val v = focusView ?: createFocus()
        v.moveTo(r)
    }

    private fun createFocus(): FocusView {
        val v = FocusView(this)
        val p = baseParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        wm.addView(v, p)
        focusView = v
        return v
    }

    // ───────── 상단 표시 ─────────

    fun progress(label: String?, p: Float) {
        main.post {
            if (!Settings.showHud) return@post
            if (label == null) { hud?.let { if (it.progress >= 0f) it.visibility = View.GONE; it.progress = -1f }; return@post }
            val v = hud ?: createHud()
            main.removeCallbacks(hideHud)
            v.text = label; v.progress = p
            v.visibility = View.VISIBLE
            v.requestLayout(); v.invalidate()
        }
    }

    private fun showHud(text: String) {
        if (!Settings.showHud) return
        val v = hud ?: createHud()
        v.text = text; v.progress = -1f
        v.visibility = View.VISIBLE
        v.requestLayout(); v.invalidate()
        main.removeCallbacks(hideHud)
        main.postDelayed(hideHud, 1100)
    }

    private fun createHud(): HudView {
        val v = HudView(this)
        val p = baseParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (72 * resources.displayMetrics.density).toInt()
        }
        wm.addView(v, p)
        hud = v; hudLp = p
        return v
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
            hud?.let { runCatching { wm.removeView(it) } }
            focusView?.let { runCatching { wm.removeView(it) } }
            hud = null; focusView = null; hudLp = null
        }
    }
}
