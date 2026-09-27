package com.jel.eyemouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/** 커서 오버레이 표시 + 탭 제스처 실행 담당 */
class EyeMouseAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: EyeMouseAccessibilityService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var view: CursorView? = null
    private var lp: WindowManager.LayoutParams? = null
    private val sizePx by lazy { (56 * resources.displayMetrics.density).toInt() }

    // 최신 커서 상태만 반영 (메인 스레드 post 폭주 방지)
    @Volatile private var pX = -1f
    @Volatile private var pY = -1f
    @Volatile private var pState = CursorView.State.NORMAL
    @Volatile private var pProgress = 0f
    @Volatile private var pending = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        updateScreenSize()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        main.post { removeCursor() }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        removeCursor()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::wm.isInitialized) updateScreenSize()
    }

    private fun updateScreenSize() {
        val p = realScreenSize(wm)
        EyeMouseState.screenW = p.x
        EyeMouseState.screenH = p.y
    }

    fun showCursor(x: Float, y: Float, state: CursorView.State, progress: Float) {
        pX = x; pY = y; pState = state; pProgress = progress
        if (pending) return
        pending = true
        main.post { pending = false; applyCursor() }
    }

    fun hideCursor() {
        main.post { view?.visibility = View.GONE }
    }

    fun tap(x: Float, y: Float) {
        main.post {
            val cx = x.coerceIn(0f, EyeMouseState.screenW - 1f)
            val cy = y.coerceIn(0f, EyeMouseState.screenH - 1f)
            val path = Path().apply { moveTo(cx, cy) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            dispatchGesture(gesture, null, null)
            view?.flash()
        }
    }

    private fun applyCursor() {
        if (pX < 0 || !::wm.isInitialized) return
        val v = view ?: createCursor()
        val p = lp ?: return
        v.visibility = View.VISIBLE
        v.state = pState
        v.progress = pProgress
        v.invalidate()
        p.x = (pX - sizePx / 2f).toInt()
        p.y = (pY - sizePx / 2f).toInt()
        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
    }

    private fun createCursor(): CursorView {
        val v = CursorView(this)
        val p = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(v, p)
        view = v; lp = p
        return v
    }

    private fun removeCursor() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null; lp = null
    }
}
