package com.jel.eyemouse

import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs

/** 분석 화면: 지금 왜 잘 되거나 안 되는지를 숫자와 원인으로 보여 준다 */
class AnalysisActivity : ComponentActivity() {

    private class Snap(
        val t: Long,
        val f: FaceFeatures?,
        val score: Float,
        val state: TrackState,
        val cause: Cause?,
        val fps: Float,
        val pitch: Float,
        val gyro: Float,
        val yaw: Float,
        val lidBase: Float,
        val crop: Bitmap?,
        val rec: String,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!EyeMouseState.running.value) TrackerService.start(this)
        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            val scheme = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    private fun take() = Snap(
        SystemClock.uptimeMillis(), EyeMouseState.latest, QualityMonitor.score, QualityMonitor.state,
        QualityMonitor.cause, QualityMonitor.fps, PoseSensor.pitchDeg, PoseSensor.gyroDps, QualityMonitor.yawDeg,
        QualityMonitor.lidBase, EyeCropRefiner.debugCrop, QualityMonitor.recommendation(),
    )

    @Composable
    private fun Screen() {
        var snap by remember { mutableStateOf(take()) }
        var slow by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) {
            var i = 0
            while (true) {
                delay(120)
                snap = take()
                if (++i % 10 == 0) slow++
            }
        }
        val s = snap
        val f = s.f
        val fresh = f != null && s.t - f.t < 600

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("분석", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

            // 품질 점수
            Card("추적 품질") {
                val (label, color) = when (s.state) {
                    TrackState.GOOD -> "좋음" to Color(0xFF34C759)
                    TrackState.DEGRADED -> "저하" to Color(0xFFFFB300)
                    TrackState.LOST -> "상실" to Color(0xFFFF3B30)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("%.0f".format(s.score), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = color)
                    Text("  / 100 · $label", style = MaterialTheme.typography.titleMedium)
                }
                LinearProgressIndicator(
                    progress = { (s.score / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(), color = color,
                )
                Text("추천: ${s.rec}", style = MaterialTheme.typography.bodyMedium)
                Text("처리 속도 %.0f fps".format(s.fps), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // 실시간 측정값
            Card("실시간 측정") {
                if (!fresh || f == null) {
                    Hint("얼굴이 감지되지 않았어요. 화면 정면을 봐 주세요.")
                } else {
                    val v = f.v
                    val z = v[G.Z]
                    Metric("눈–화면 거리", "%.1f cm".format(z / 10f), ok(z in 250f..400f), "권장 25~40cm")
                    Metric("폰 기울기", "%.0f°".format(s.pitch), ok(s.pitch in 40f..85f), "세움 90° · 눕힘 0°")
                    Metric("폰 흔들림", "%.0f°/s".format(s.gyro), ok(s.gyro < 5f), "5°/s 미만 권장")
                    Metric("머리 좌우", "%.0f°".format(s.yaw), ok(abs(s.yaw) < 15f), "±15° 이내")
                    Metric("홍채 크기", "%.0f px".format(v[G.IRIS]), ok(v[G.IRIS] >= 26f), "26px 이상")
                    val open = if (s.lidBase > 0f) 100f * v[G.LID] / s.lidBase else 100f
                    Metric("눈 개방도", "%.0f%%".format(open), ok(open >= 80f), "평소 대비")
                    Metric("정밀 홍채 신뢰도", "%.0f%%".format(100f * v[G.CONF]), ok(v[G.CONF] >= 0.4f), "높을수록 작은 움직임까지 추적")
                    Metric("눈 영역 밝기", "%.0f".format(v[G.BRIGHT]), ok(v[G.BRIGHT] in 80f..180f), "80~180")
                    Metric("시선 각도", "좌우 %.1f° · 상하 %.1f°".format(
                        Math.toDegrees(v[G.GX].toDouble()), Math.toDegrees(v[G.GY].toDouble())), null, "")
                }
            }

            // 눈 크롭
            Card("눈 확대 화면") {
                val crop = s.crop
                if (crop == null) Hint("추적 중에 표시돼요.")
                else {
                    Image(
                        bitmap = crop.asImageBitmap(), contentDescription = "눈 확대",
                        modifier = Modifier.fillMaxWidth().aspectRatio(crop.width.toFloat() / crop.height)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit, filterQuality = FilterQuality.None,
                    )
                    Hint("초록 원 = 정밀 검출한 홍채, 빨간 점 = 기본 검출 위치")
                }
            }

            // 오차 지도
            val tick = slow
            Card("오차 지도 (화면 위치별)") {
                val wMm = EyeMouseState.screenW / EyeMouseState.ppmX
                val hMm = EyeMouseState.screenH / EyeMouseState.ppmY
                val grid = remember(tick) { GazeTrainer.errorGrid(wMm, hMm) }
                if (grid.all { it.isNaN() }) Hint("전체 학습 또는 정밀 학습을 하면 채워져요.")
                else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (r in 0 until 6) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (c in 0 until 3) {
                                val e = grid[r * 3 + c]
                                val bg = when {
                                    e.isNaN() -> MaterialTheme.colorScheme.surfaceVariant
                                    e < 6f -> Color(0x5534C759)
                                    e < 10f -> Color(0x55FFB300)
                                    else -> Color(0x55FF3B30)
                                }
                                Box(
                                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(6.dp)).background(bg),
                                    contentAlignment = Alignment.Center,
                                ) { Text(if (e.isNaN()) "–" else "%.0fmm".format(e), style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    Hint("초록 6mm 미만 · 노랑 10mm 미만 · 빨강 10mm 이상")
                }
            }

            // 학습 지도
            Card("학습 지도 (거리 × 폰 기울기)") {
                val cov = remember(tick) { GazeTrainer.coverage() }
                val cols = listOf("눕힘 <55°", "보통", "세움 ≥75°")
                val rows = listOf("30cm 미만", "30–40cm", "40cm 이상")
                Row { Text("", Modifier.weight(1.2f)); cols.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) } }
                for (r in 0 until 3) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rows[r], Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall)
                    for (c in 0 until 3) {
                        val n = cov[r * 3 + c]
                        Box(
                            Modifier.weight(1f).padding(2.dp).height(32.dp).clip(RoundedCornerShape(6.dp))
                                .background(if (n >= 100) Color(0x5534C759) else if (n > 0) Color(0x55FFB300) else MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) { Text("$n", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Hint("비어 있는 조건에서 자주 쓴다면 그 자세로 '빠른 보정'을 해 주세요.")
            }

            // 세션 기록
            Card("이번 세션") {
                val pct = remember(tick) { QualityMonitor.statePercents() }
                val causes = remember(tick) { QualityMonitor.topCauses() }
                Text("좋음 %.0f%% · 저하 %.0f%% · 상실 %.0f%%".format(pct[0], pct[1], pct[2]))
                if (causes.isEmpty()) Hint("아직 기록된 문제가 없어요.")
                else causes.forEachIndexed { i, (c, sec) -> Text("${i + 1}. ${c.msg} (%.0f초)".format(sec), style = MaterialTheme.typography.bodySmall) }
                val acc = Settings.accuracyMm
                val cv = Settings.cvErrorMm
                val model = GazeTrainer.model
                Hint(buildString {
                    append("학습 데이터 ${GazeTrainer.count()}개")
                    if (!acc.isNaN()) append(" · 검증 오차 %.0fmm".format(acc))
                    if (!cv.isNaN()) append(" · 예상 오차 %.0fmm".format(cv))
                    if (model != null) append(if (model.hasResidual) " · 물리+잔차 모델" else " · 물리 모델")
                })
            }
        }
    }

    private fun ok(b: Boolean) = b

    @Composable
    private fun Metric(name: String, value: String, good: Boolean?, hint: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.padding(end = 8.dp).height(10.dp).aspectRatio(1f).clip(RoundedCornerShape(5.dp))
                    .background(when (good) { null -> Color.Transparent; true -> Color(0xFF34C759); false -> Color(0xFFFFB300) }),
            )
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium)
                if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                content()
            }
        }
    }

    @Composable
    private fun Hint(text: String) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
