package com.jel.eyemouse

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PointF
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 시선 학습 화면 (세로 고정).
 *  FULL  : 18점 격자 + 움직이는 점 따라가기 + 검증 5점 (약 1분 10초) — 처음부터 새로 학습
 *  QUICK : 5점 빠른 보정 — 자세·거리가 바뀌었을 때
 *  TRAIN : 무작위 20점 정밀 학습 — 실시간 예측 링을 보며 추가 학습
 */
class CalibrationActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val FULL = "full"
        const val QUICK = "quick"
        const val TRAIN = "train"

        fun start(ctx: Context, mode: String) {
            ctx.startActivity(Intent(ctx, CalibrationActivity::class.java).putExtra(EXTRA_MODE, mode))
        }

        private const val MOVE_MS = 450L
        private const val SETTLE_MS = 450L
        private const val COLLECT_MS = 900L
        private const val PURSUIT_MS = 18_000L
        private const val PURSUIT_LAG_MS = 120L
        private const val BLINK_SKIP = 0.5f

        private val VALID_POINTS = listOf(
            PointF(0.5f, 0.5f), PointF(0.2f, 0.12f), PointF(0.8f, 0.12f),
            PointF(0.2f, 0.88f), PointF(0.8f, 0.88f),
        )
    }

    private lateinit var view: CalibView
    private lateinit var size: Point
    private lateinit var ppm: PointF

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

        val mode = intent.getStringExtra(EXTRA_MODE) ?: FULL
        lifecycleScope.launch {
            // 세로 고정이 적용된 뒤의 화면 크기를 사용
            delay(300)
            size = realScreenSize(windowManager)
            ppm = DisplayProfile.pxPerMm(size.x, size.y, resources.displayMetrics)
            view.screen = size
            if (size.x > size.y) {
                view.message = "세로 화면에서만 학습할 수 있어요"
                delay(2500); finish(); return@launch
            }
            when {
                mode == FULL || GazeTrainer.model == null -> runFull()
                mode == QUICK -> runQuick()
                else -> runTrain()
            }
        }
    }

    override fun onDestroy() {
        EyeMouseState.calibrating = false
        super.onDestroy()
    }

    // ───────────────────────── 모드 ─────────────────────────

    private suspend fun runFull() {
        if (!waitFace()) return
        say("폰을 평소처럼 25~35cm 거리에서 들고\n머리는 편하게 고정한 채\n눈으로만 점을 따라가세요", 4000)

        val all = ArrayList<GazeSample>()

        // 1) 18점 격자 (3열 × 6행, 21:9 세로 화면에 맞춘 배치)
        val cols = floatArrayOf(0.12f, 0.5f, 0.88f)
        val rows = floatArrayOf(0.05f, 0.23f, 0.41f, 0.59f, 0.77f, 0.95f)
        val grid = ArrayList<PointF>()
        rows.forEachIndexed { r, fy ->
            val order = if (r % 2 == 0) cols.toList() else cols.reversed()
            order.forEach { fx -> grid += PointF(fx, fy) }
        }
        view.header = "1단계 · 점 바라보기"
        grid.forEachIndexed { i, p ->
            view.progress = i / grid.size.toFloat() * 0.6f
            all += fixate(p, i, GazeTrainer.KIND_GRID, 1f)
        }

        // 2) 움직이는 점 따라가기 (세세한 연속 움직임 학습)
        view.header = "2단계 · 움직이는 점 따라가기"
        view.target = null
        say("이번엔 천천히 움직이는 점을\n눈으로 따라가세요", 2200)
        val t0 = now()
        val path = { t: Long ->
            val s = (t - t0) / 1000f
            PointF(
                0.5f + 0.38f * sin(2 * PI.toFloat() * s / 7.3f),
                0.5f + 0.44f * sin(2 * PI.toFloat() * s / 11.1f + PI.toFloat() / 2),
            )
        }
        view.follow = path
        all += collect(
            ms = PURSUIT_MS, kind = GazeTrainer.KIND_PURSUIT, weight = 0.6f,
            target = { t -> path(t - PURSUIT_LAG_MS) },
            group = { t -> 100 + ((t - t0) / 1500).toInt() },
            onTick = { t -> view.progress = 0.6f + 0.3f * ((t - t0).toFloat() / PURSUIT_MS) },
        )
        view.follow = null

        // 3) 학습
        view.header = "학습 중"
        view.message = "학습 중…"
        GazeTrainer.replaceAll(all)
        val r = withContext(Dispatchers.Default) { GazeTrainer.refitNow() }
        view.message = null
        if (r == null) { say("데이터가 부족해요.\n밝은 곳에서 다시 시도해 주세요.", 3000); finish(); return }

        // 4) 검증
        view.header = "3단계 · 확인"
        say("마지막 확인입니다\n점을 바라보세요", 1800)
        val (err, valid) = validate(0.9f)
        GazeTrainer.addAll(valid)
        withContext(Dispatchers.Default) { GazeTrainer.refitNow() }
        Settings.accuracyMm = err.toFloat()
        view.progress = 1f
        finishWith(err)
    }

    private suspend fun runQuick() {
        if (!waitFace()) return
        view.header = "빠른 보정 · 5점"
        say("점을 바라보세요", 1500)
        val (err, samples) = validate(0f)
        GazeTrainer.addAll(samples.map { GazeSample(it.e, it.x, it.y, 1.5f, it.group, it.kind) })
        view.message = "적용 중…"
        withContext(Dispatchers.Default) { GazeTrainer.refitNow() }
        say("보정 완료!\n보정 전 오차 약 %.0fmm".format(err), 2500)
        finish()
    }

    private suspend fun runTrain() {
        if (!waitFace()) return
        say("정밀 학습\n점을 바라보면 파란 링이 예측 위치예요\n링이 점에 가까워지도록 학습합니다", 3500)
        view.showLive = true
        val rnd = java.util.Random()
        val errs = ArrayList<Double>()
        val total = 20
        for (i in 0 until total) {
            val p = PointF(0.08f + rnd.nextFloat() * 0.84f, 0.05f + rnd.nextFloat() * 0.90f)
            view.progress = i / total.toFloat()
            val s = fixate(p, GazeTrainer.newGroup(), GazeTrainer.KIND_TRAIN, 1.2f)
            val e = meanError(s)
            if (!e.isNaN()) errs += e
            view.header = "정밀 학습 ${i + 1}/$total · 이번 오차 %.0fmm".format(e)
            GazeTrainer.addAll(s)
            if ((i + 1) % 4 == 0) GazeTrainer.refitAsync()
        }
        view.showLive = false
        view.message = "적용 중…"
        withContext(Dispatchers.Default) { GazeTrainer.refitNow() }
        val first = errs.take(5).average()
        val last = errs.takeLast(5).average()
        say("완료!\n처음 %.0fmm → 마지막 %.0fmm".format(first, last), 3000)
        finish()
    }

    // ───────────────────────── 공통 ─────────────────────────

    private fun now() = SystemClock.uptimeMillis()

    private suspend fun say(msg: String, ms: Long) {
        view.message = msg
        delay(ms)
        view.message = null
    }

    private suspend fun waitFace(): Boolean {
        view.message = "카메라 준비 중…"
        val t0 = now()
        while (EyeMouseState.latest?.takeIf { now() - it.t < 500 } == null) {
            if (now() - t0 > 10_000) {
                view.message = "얼굴이 보이지 않아요.\n밝은 곳에서 화면 정면을 봐 주세요."
                delay(3000); finish(); return false
            }
            delay(100)
        }
        view.message = null
        return true
    }

    /** 점을 옮기고, 눈이 도착할 시간을 준 뒤 고정 시선 샘플 수집 */
    private suspend fun fixate(p: PointF, group: Int, kind: Int, weight: Float): List<GazeSample> {
        view.moveTo(p, MOVE_MS)
        delay(MOVE_MS + SETTLE_MS)
        return collect(COLLECT_MS, kind, weight, target = { p }, group = { group })
    }

    private suspend fun collect(
        ms: Long,
        kind: Int,
        weight: Float,
        target: (Long) -> PointF,
        group: (Long) -> Int,
        onTick: ((Long) -> Unit)? = null,
    ): List<GazeSample> {
        val out = ArrayList<GazeSample>()
        val end = now() + ms
        var lastT = -1L
        while (now() < end) {
            val f = EyeMouseState.latest
            if (f != null && f.t != lastT) {
                lastT = f.t
                if (min(f.blinkL, f.blinkR) < BLINK_SKIP) {
                    val tp = target(f.t)
                    out += GazeSample(
                        f.eye.copyOf(),
                        tp.x * size.x / ppm.x, tp.y * size.y / ppm.y,
                        weight, group(f.t), kind,
                    )
                }
                if (view.showLive) view.live = predictPx(f.eye)
            }
            onTick?.invoke(now())
            delay(15)
        }
        return out
    }

    /** 5점 검증: 점별 평균 예측 위치와 실제 위치의 거리(mm) 평균 */
    private suspend fun validate(progressBase: Float): Pair<Double, List<GazeSample>> {
        view.showLive = true
        val all = ArrayList<GazeSample>()
        val errs = ArrayList<Double>()
        VALID_POINTS.forEachIndexed { i, p ->
            if (progressBase > 0f) view.progress = progressBase + 0.1f * i / VALID_POINTS.size
            val s = fixate(p, GazeTrainer.newGroup(), GazeTrainer.KIND_VALID, 1f)
            val e = meanError(s)
            if (!e.isNaN()) errs += e
            all += s
        }
        view.showLive = false
        view.live = null
        return (if (errs.isEmpty()) Double.NaN else errs.average()) to all
    }

    private fun meanError(s: List<GazeSample>): Double {
        val m = GazeTrainer.model ?: return Double.NaN
        if (s.isEmpty()) return Double.NaN
        var px = 0.0; var py = 0.0
        for (smp in s) { val p = m.predictMm(smp.e); px += p.x; py += p.y }
        px /= s.size; py /= s.size
        return hypot(px - s[0].x, py - s[0].y)
    }

    private fun predictPx(e: FloatArray): PointF? {
        val m = GazeTrainer.model ?: return null
        val mm = m.predictMm(e)
        return PointF(mm.x * ppm.x, mm.y * ppm.y)
    }

    private suspend fun finishWith(errMm: Double) {
        val grade = when {
            errMm.isNaN() -> ""
            errMm < 6 -> "아주 좋음 — 작은 버튼도 가능"
            errMm < 10 -> "좋음 — 일반 버튼 사용 가능"
            errMm < 15 -> "보통 — '정밀 학습'을 추천해요"
            else -> "낮음 — 조명·거리 확인 후 다시 해 보세요"
        }
        say("완료!\n평균 오차 약 %.0fmm\n%s".format(errMm, grade), 4000)
        finish()
    }
}

private class CalibView(ctx: Context) : View(ctx) {
    var screen = Point(1080, 2520)
    var message: String? = null
        set(v) { field = v; postInvalidate() }
    var header: String? = null
        set(v) { field = v; postInvalidate() }
    var progress = 0f
        set(v) { field = v; postInvalidate() }
    var showLive = false
    var live: PointF? = null
        set(v) { field = v; postInvalidate() }
    var follow: ((Long) -> PointF)? = null
        set(v) { field = v; postInvalidate() }
    var target: PointF? = null

    private var from: PointF? = null
    private var moveStart = 0L
    private var moveMs = 1L
    private val d = resources.displayMetrics.density
    private val loc = IntArray(2)

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * d; color = Color.WHITE
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 69, 58) }
    private val liveP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = Color.rgb(64, 156, 255)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 21 * d; textAlign = Paint.Align.CENTER
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY; textSize = 13 * d; textAlign = Paint.Align.CENTER
    }
    private val bar = Paint().apply { color = Color.rgb(64, 156, 255) }

    fun moveTo(p: PointF, ms: Long) {
        from = currentFrac()
        target = p
        moveStart = SystemClock.uptimeMillis()
        moveMs = ms.coerceAtLeast(1)
        postInvalidate()
    }

    private fun currentFrac(): PointF? {
        follow?.let { return it(SystemClock.uptimeMillis()) }
        val t = target ?: return null
        val f = from ?: return t
        val k = ((SystemClock.uptimeMillis() - moveStart).toFloat() / moveMs).coerceIn(0f, 1f)
        val e = k * k * (3 - 2 * k)
        return PointF(f.x + (t.x - f.x) * e, f.y + (t.y - f.y) * e)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        getLocationOnScreen(loc)
        header?.let { c.drawText(it, width / 2f, 48 * d, small) }
        if (progress > 0f) c.drawRect(0f, height - 4 * d, width * progress, height.toFloat(), bar)
        message?.let { msg ->
            val lines = msg.split("\n")
            var y = height / 2f - (lines.size - 1) * text.textSize * 0.7f
            for (l in lines) { c.drawText(l, width / 2f, y, text); y += text.textSize * 1.4f }
        }
        var animating = follow != null || showLive
        currentFrac()?.let { p ->
            val x = p.x * screen.x - loc[0]
            val y = p.y * screen.y - loc[1]
            val since = SystemClock.uptimeMillis() - moveStart - moveMs
            val k = if (follow != null) 1f else (since / 700f).coerceIn(0f, 1f)
            c.drawCircle(x, y, (34 - 24 * k) * d, ring)
            c.drawCircle(x, y, 5 * d, dot)
            if (k < 1f) animating = true
        }
        if (showLive) live?.let { c.drawCircle(it.x - loc[0], it.y - loc[1], 14 * d, liveP) }
        if (animating) postInvalidateOnAnimation()
    }
}
