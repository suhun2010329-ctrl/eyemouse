package com.jel.eyemouse

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SysSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private var camOk by mutableStateOf(false)
    private var a11yOn by mutableStateOf(false)
    private var notifOk by mutableStateOf(true)
    private var tick by mutableIntStateOf(0)

    private val camPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { camOk = it }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { notifOk = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    override fun onResume() {
        super.onResume()
        camOk = granted(Manifest.permission.CAMERA)
        notifOk = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
        a11yOn = EyeMouseAccessibilityService.instance != null
        tick++
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    @Composable
    private fun Screen() {
        val running by EyeMouseState.running.collectAsState()
        val t = tick
        var mode by remember(t) { mutableStateOf(Settings.mode) }
        var click by remember(t) { mutableStateOf(Settings.clickMode) }
        var gain by remember(t) { mutableStateOf(Settings.headGain) }
        var dwell by remember(t) { mutableStateOf(Settings.dwellMs.toFloat()) }
        var stab by remember(t) { mutableStateOf(Settings.stability) }
        var invX by remember(t) { mutableStateOf(Settings.invertX) }
        var invY by remember(t) { mutableStateOf(Settings.invertY) }
        val hasModel = remember(t) { Settings.gazeModel != null }
        val ready = camOk && a11yOn

        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("아이마우스", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

            Section("준비") {
                StatusRow("카메라 권한", camOk, "허용") { camPerm.launch(Manifest.permission.CAMERA) }
                StatusRow("접근성 서비스", a11yOn, "설정 열기") {
                    startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    StatusRow("알림(선택)", notifOk, "허용") { notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }
                }
                if (!a11yOn) {
                    Hint("접근성 목록에서 ‘아이마우스’를 켜 주세요. 회색으로 막혀 있으면: 앱 정보 → 우측 상단 ⋮ → ‘제한된 설정 허용’ 후 다시 시도.")
                }
            }

            Button(
                onClick = {
                    if (running) TrackerService.send(this@MainActivity, TrackerService.ACTION_STOP)
                    else TrackerService.start(this@MainActivity)
                },
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (running) "정지" else "시작") }

            Section("커서 이동") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(mode == TrackMode.HEAD, { mode = TrackMode.HEAD; Settings.mode = mode }, { Text("머리 움직임") })
                    FilterChip(mode == TrackMode.EYE, { mode = TrackMode.EYE; Settings.mode = mode }, { Text("시선") })
                }
                if (mode == TrackMode.HEAD) {
                    Hint("코끝 방향으로 커서가 움직입니다. 편한 자세에서 ‘센터 맞추기’를 누르세요.")
                    LabeledSlider("감도", gain, 2f..12f, "%.1f".format(gain)) { gain = it; Settings.headGain = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("좌우 반전", Modifier.weight(1f))
                        Switch(invX, { invX = it; Settings.invertX = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("상하 반전", Modifier.weight(1f))
                        Switch(invY, { invY = it; Settings.invertY = it })
                    }
                    OutlinedButton(
                        onClick = { TrackerService.send(this@MainActivity, TrackerService.ACTION_RECENTER) },
                        enabled = running,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("센터 맞추기") }
                } else {
                    Hint(
                        if (hasModel) "캘리브레이션 완료. 자세나 화면 방향이 바뀌면 다시 해 주세요."
                        else "시선 모드는 캘리브레이션이 필요합니다. (그 전엔 머리 모드로 동작)"
                    )
                }
                LabeledSlider("안정성", stab, 0f..1f, "${(stab * 100).roundToInt()}%") {
                    stab = it; Settings.stability = it
                }
                OutlinedButton(
                    onClick = { startActivity(Intent(this@MainActivity, CalibrationActivity::class.java)) },
                    enabled = camOk,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("시선 캘리브레이션 (9점)") }
            }

            Section("클릭 방식") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(click == ClickMode.DWELL, { click = ClickMode.DWELL; Settings.clickMode = click }, { Text("응시") })
                    FilterChip(click == ClickMode.BLINK, { click = ClickMode.BLINK; Settings.clickMode = click }, { Text("깜빡임") })
                    FilterChip(click == ClickMode.BOTH, { click = ClickMode.BOTH; Settings.clickMode = click }, { Text("둘 다") })
                }
                if (click != ClickMode.BLINK) {
                    LabeledSlider("응시 시간", dwell, 500f..2500f, "%.1f초".format(dwell / 1000f)) {
                        dwell = it; Settings.dwellMs = it.roundToInt()
                    }
                }
            }

            Section("사용법") {
                Hint("• 응시: 커서를 한곳에 멈추면 초록 링이 차고 탭\n• 깜빡임: 두 눈을 0.3~1.2초 감았다 뜨면 탭\n• 두 눈 2초 이상 감기: 일시정지/재개\n• 커서 색: 파랑=작동, 주황=눈 감음, 보라=일시정지 준비, 회색=정지/얼굴 없음")
            }
            Spacer(Modifier.width(1.dp))
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                content()
            }
        }
    }

    @Composable
    private fun StatusRow(label: String, ok: Boolean, action: String, onClick: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            if (ok) Text("✓ 완료", color = MaterialTheme.colorScheme.primary)
            else TextButton(onClick = onClick) { Text(action) }
        }
    }

    @Composable
    private fun Hint(text: String) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    @Composable
    private fun LabeledSlider(
        label: String,
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        valueText: String,
        onChange: (Float) -> Unit,
    ) {
        Column {
            Row {
                Text(label, Modifier.weight(1f))
                Text(valueText, color = MaterialTheme.colorScheme.primary)
            }
            Slider(value = value, onValueChange = onChange, valueRange = range)
        }
    }
}
