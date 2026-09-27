package com.jel.eyemouse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max

/** 9점 시선 캘리브레이션 */
class CalibrationActivity : ComponentActivity() {
    private lateinit var view: CalibView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        view = CalibView(this)
        setContentView(view)

        EyeMouseState.calibrating = true
        if (!EyeMouseState.running.value) TrackerService.start(this)
        lifecycleScope.launch { runCalibration() }
    }

    override fun onDestroy() {
        EyeMouseState.calibrating = false
        super.onDestroy()
    }

    private fun now() = SystemClock.uptimeMillis()

    private fun fresh(): FaceFeatures? =
        EyeMouseState.latest?.takeIf { now() - it.t < 500 }

    private suspend fun runCalibration() {
        view.message = "카메라 준비 중…"
        val t0 = now()
        while (fresh() == null) {
            if (now() - t0 > 10_000) {
                view.message = "얼굴이 보이지 않아요.\n밝은 곳에서 화면 정면을 봐 주세요."
                delay(3000); finish(); return
            }
            delay(100)
        }
        view.message = "머리는 고정하고\n눈으로만 점을 따라가세요"
        delay(2500)
        view.message = null

        val a = 0.08f; val b = 0.5f; val c = 0.92f
        val points = listOf(
            a to a, b to a, c to a,
            c to b, b to b, a to b,
            a to c, b to c, c to c,
        )
        val samples = mutableListOf<Pair<FaceFeatures, Pair<Float, Float>>>()
        for (p in points) {
            view.setTarget(p)
            delay(SETTLE_MS)
            val end = now() + COLLECT_MS
            var lastT = -1L
            while (now() < end) {
                val f = EyeMouseState.latest
                if (f != null && f.t != lastT) {
                    lastT = f.t
                    if (max(f.blinkL, f.blinkR) < 0.4f) samples += f to p
                }
                delay(15)
            }
        }
        view.setTarget(null)

        val model = GazeModel.fit(samples)
        if (model == null) {
            view.message = "데이터가 부족해요. 다시 시도해 주세요."
            delay(2500); finish(); return
        }
        val size = realScreenSize(windowManager)
        val errPx = samples.map { (f, p) ->
            val (px, py) = model.predict(f)
            hypot((px - p.first) * size.x, (py - p.second) * size.y).toDouble()
        }.average()
        val mm = errPx / resources.displayMetrics.xdpi * 25.4

        Settings.gazeModel = model.toJson()
        Settings.mode = TrackMode.EYE
        TrackerService.controller?.reloadModel()

        view.message = "완료!\n평균 오차 약 %.0fmm".format(mm)
        delay(2500)
        finish()
    }

    companion object {
        private const val SETTLE_MS = 800L
        private const val COLLECT_MS = 1000L
    }
}

private class CalibView(ctx: Context) : View(ctx) {
    var message: String? = null
        set(v) { field = v; postInvalidate() }

    private var target: Pair<Float, Float>? = null
    private var targetStart = 0L
    private val d = resources.displayMetrics.density
    private val loc = IntArray(2)

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * d; color = Color.WHITE
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 69, 58) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 22 * d; textAlign = Paint.Align.CENTER
    }

    fun setTarget(p: Pair<Float, Float>?) {
        target = p
        targetStart = SystemClock.uptimeMillis()
        postInvalidate()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        message?.let { msg ->
            val lines = msg.split("\n")
            var y = height / 2f - (lines.size - 1) * text.textSize * 0.7f
            for (l in lines) { c.drawText(l, width / 2f, y, text); y += text.textSize * 1.4f }
        }
        val t = target ?: return
        // 화면 절대 좌표 → 뷰 좌표
        val size = realScreenSize(context.getSystemService(WindowManager::class.java))
        getLocationOnScreen(loc)
        val x = t.first * size.x - loc[0]
        val y = t.second * size.y - loc[1]
        val k = ((SystemClock.uptimeMillis() - targetStart) / 800f).coerceIn(0f, 1f)
        c.drawCircle(x, y, (40 - 30 * k) * d, ring)
        c.drawCircle(x, y, 6 * d, dot)
        if (k < 1f) postInvalidateOnAnimation()
    }
}
