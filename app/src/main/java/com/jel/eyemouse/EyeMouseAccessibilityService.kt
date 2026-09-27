package com.jel.eyemouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.HandlerThread
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.hypot
import kotlin.math.max

/** 탭 결과: 실제로 누른 위치 + 학습에 쓸 위치(작은 버튼 중심, 없으면 NaN) */
class TapResult(val x: Float, val y: Float, val learnX: Float, val learnY: Float)

/** 커서 오버레이 표시 + 탭 제스처 실행 + 버튼 스냅 담당 */
class EyeMouseAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: EyeMouseAccessibilityService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("eyemouse-a11y").apply { start() }
    private val work = Handler(worker.looper)
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
        worker.quitSafely()
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
        val ppm = DisplayProfile.pxPerMm(p.x, p.y, resources.displayMetrics)
        EyeMouseState.ppmX = ppm.x
        EyeMouseState.ppmY = ppm.y
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

    fun tap(x: Float, y: Float) = tapSmart(x, y, 0f, null)

    /** snapRadius > 0 이면 반경 안의 가장 알맞은 버튼으로 맞춰서 탭 */
    fun tapSmart(x: Float, y: Float, snapRadius: Float, onDone: ((TapResult) -> Unit)?) {
        work.post {
            val r = (if (snapRadius > 0f) runCatching { snap(x, y, snapRadius) }.getOrNull() else null)
                ?: TapResult(x, y, Float.NaN, Float.NaN)
            main.post { dispatchTap(r.x, r.y) }
            onDone?.invoke(r)
        }
    }

    private fun dispatchTap(x: Float, y: Float) {
        val cx = x.coerceIn(0f, EyeMouseState.screenW - 1f)
        val cy = y.coerceIn(0f, EyeMouseState.screenH - 1f)
        val path = Path().apply { moveTo(cx, cy) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
        view?.flash()
    }

    /** 커서 근처의 누를 수 있는 요소 찾기 (화면 맨 위 창 기준) */
    private fun snap(x: Float, y: Float, radius: Float): TapResult? {
        val rect = Rect()
        var root: AccessibilityNodeInfo? = null
        var topLayer = Int.MIN_VALUE
        try {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                w.getBoundsInScreen(rect)
                if (!rect.contains(x.toInt(), y.toInt()) || w.layer < topLayer) continue
                val r = w.root ?: continue
                root = r; topLayer = w.layer
            }
        } catch (_: Exception) {}
        if (root == null) root = rootInActiveWindow ?: return null

        var best: Rect? = null
        var bestDist = Float.MAX_VALUE
        var bestArea = Long.MAX_VALUE
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.addLast(root!!)
        var visited = 0
        while (q.isNotEmpty() && visited < 3000) {
            val n = q.removeFirst()
            visited++
            if (!n.isVisibleToUser) continue
            if (n.isClickable || n.isLongClickable || n.isCheckable || n.isEditable) {
                n.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    val dx = max(max(rect.left - x, 0f), x - rect.right)
                    val dy = max(max(rect.top - y, 0f), y - rect.bottom)
                    val dist = hypot(dx, dy)
                    if (dist <= radius) {
                        val area = rect.width().toLong() * rect.height()
                        val better = dist < bestDist || (dist == bestDist && area < bestArea)
                        if (better) { best = Rect(rect); bestDist = dist; bestArea = area }
                    }
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.addLast(it) }
        }
        val b = best ?: return null
        val small = b.width() <= radius * 2.2f && b.height() <= radius * 2.2f
        val cxB = b.exactCenterX()
        val cyB = b.exactCenterY()
        return if (small) {
            TapResult(cxB, cyB, cxB, cyB)
        } else if (bestDist == 0f) {
            TapResult(x, y, Float.NaN, Float.NaN)   // 큰 요소 안: 그 자리 그대로
        } else {
            TapResult(
                x.coerceIn(b.left + 4f, b.right - 4f),
                y.coerceIn(b.top + 4f, b.bottom - 4f),
                Float.NaN, Float.NaN,
            )
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
