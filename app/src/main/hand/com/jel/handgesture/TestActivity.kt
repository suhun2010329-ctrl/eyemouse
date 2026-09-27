package com.jel.handgesture

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 제스처 테스트: 손 뼈대, 인식된 모양, 손가락 상태, 카메라/처리 fps, 지연 */
class TestActivity : ComponentActivity() {

    private val bones = intArrayOf(
        0, 1, 1, 2, 2, 3, 3, 4, 0, 5, 5, 6, 6, 7, 7, 8, 5, 9, 9, 10, 10, 11, 11, 12,
        9, 13, 13, 14, 14, 15, 15, 16, 13, 17, 17, 18, 18, 19, 19, 20, 0, 17,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!HandState.running.value) GestureService.start(this)
        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            val scheme = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize()) { Screen() } }
        }
    }

    @Composable
    private fun Screen() {
        var tick by remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) { while (true) { delay(33); tick = SystemClock.uptimeMillis() } }
        val now = tick
        val f = HandState.frame?.takeIf { now - it.t < 300 }
        val aspect = HandState.aspect

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("제스처 테스트", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                if (f == null) "손이 보이지 않아요" else "${f.pose.label}  ·  손가락 ${f.ext.count { it }}개${if (f.pinch) " · 집기" else ""}",
                fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
            )
            val names = listOf("엄지", "검지", "중지", "약지", "새끼")
            Text(
                if (f == null) "-" else names.indices.joinToString("  ") { "${names[it]} ${if (f.ext[it]) "●" else "○"}" },
                style = MaterialTheme.typography.bodyMedium,
            )
            Canvas(
                Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(12.dp)).background(Color(0xFF15171C)),
            ) {
                val w = size.width
                // 좌표: x = 가로 비율, y = 가로 폭 단위
                if (f != null) {
                    val p = f.pts
                    for (k in bones.indices step 2) {
                        val a = bones[k]; val b = bones[k + 1]
                        drawLine(Color(0xFF4C8DFF), Offset(p[a * 2] * w, p[a * 2 + 1] * w), Offset(p[b * 2] * w, p[b * 2 + 1] * w), strokeWidth = 5f)
                    }
                    for (k in 0 until 21) {
                        val on = k == 4 || k == 8 || k == 12 || k == 16 || k == 20
                        drawCircle(if (on) Color(0xFFFFCC00) else Color.White, radius = if (on) 9f else 6f, center = Offset(p[k * 2] * w, p[k * 2 + 1] * w))
                    }
                    drawCircle(Color(0x6634C759), radius = f.palmSize * w * 0.5f, center = Offset(f.palmX * w, f.palmY * w))
                }
            }
            Text(
                "카메라 %.0f fps (설정 %s) · 처리 %.0f fps · 지연 %.0f ms · %s%s".format(
                    HandState.cameraFps, HandState.fpsRange, HandState.procFps, HandState.latencyMs,
                    if (HandState.gpu) "GPU" else "CPU", if (HandState.idle) " · 대기 절전" else "",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text("마지막 실행: ${HandState.lastEvent.ifEmpty { "-" }}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "이 화면에서도 제스처가 실제로 실행돼요. 손 전체가 보이게 30~60cm 거리에서 해 보세요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
