package com.jel.handgesture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SysSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private var camOk by mutableStateOf(false)
    private var a11yOn by mutableStateOf(false)
    private var notifOk by mutableStateOf(true)
    private var tick by mutableIntStateOf(0)

    private val camPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { camOk = it }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { notifOk = it }

    private class AppItem(val label: String, val pkg: String)

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
            MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize()) { Screen() } }
        }
    }

    override fun onResume() {
        super.onResume()
        camOk = granted(Manifest.permission.CAMERA)
        notifOk = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
        a11yOn = ControlService.instance != null
        tick++
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun loadApps(): List<AppItem> {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(i, 0)
            .map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.pkg != packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label }
    }

    @Composable
    private fun Screen() {
        val running by HandState.running.collectAsState()
        val t = tick
        var sens by remember(t) { mutableStateOf(Settings.sensitivity) }
        var hold by remember(t) { mutableStateOf(Settings.holdMs.toFloat()) }
        var numHold by remember(t) { mutableStateOf(Settings.numberHoldMs.toFloat()) }
        var numbers by remember(t) { mutableStateOf(Settings.numbersEnabled) }
        var focus by remember(t) { mutableStateOf(Settings.focusEnabled) }
        var saver by remember(t) { mutableStateOf(Settings.idleSaver) }
        var showHud by remember(t) { mutableStateOf(Settings.showHud) }
        var pickFor by remember { mutableIntStateOf(0) }
        var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
        var refresh by remember { mutableIntStateOf(0) }

        LaunchedEffect(pickFor) {
            if (pickFor > 0 && apps.isEmpty()) apps = withContext(Dispatchers.IO) { loadApps() }
        }

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("핸드 제스처", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

            Section("준비") {
                StatusRow("카메라 권한", camOk, "허용") { camPerm.launch(Manifest.permission.CAMERA) }
                StatusRow("접근성 서비스", a11yOn, "설정 열기") { startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
                if (Build.VERSION.SDK_INT >= 33) {
                    StatusRow("알림(선택)", notifOk, "허용") { notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }
                }
                if (!a11yOn) Hint("접근성 → 설치된 앱 → ‘핸드 제스처’를 켜 주세요. 막혀 있으면 앱 정보 → ⋮ → ‘제한된 설정 허용’.")
            }

            Button(
                onClick = { if (running) GestureService.send(this@MainActivity, GestureService.ACTION_STOP) else GestureService.start(this@MainActivity) },
                enabled = camOk && a11yOn,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (running) "정지" else "시작") }
            OutlinedButton(
                onClick = { startActivity(Intent(this@MainActivity, TestActivity::class.java)) },
                enabled = camOk,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("제스처 테스트 (인식 상태 · fps 확인)") }

            Section("제스처 → 동작") {
                val r = refresh
                Trigger.values().forEach { trig -> ActionRow(trig, r) { refresh++ } }
            }

            Section("숫자 바로가기") {
                SwitchRow("손가락 수로 앱 열기", numbers) { numbers = it; Settings.numbersEnabled = it }
                Hint("손가락 1~5개를 편 채 멈추면 진행 막대가 차고 앱이 열려요. 1은 검지만 펴기.")
                val r = refresh
                for (n in 1..5) {
                    val label = remember(r, t) { Settings.appLabel(n) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$n", Modifier.padding(end = 12.dp), style = MaterialTheme.typography.titleMedium)
                        Text(label ?: "지정 안 됨", Modifier.weight(1f), color = if (label == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        TextButton(onClick = { pickFor = n }) { Text("앱 선택") }
                        if (label != null) TextButton(onClick = { Settings.setApp(n, null, null); refresh++ }) { Text("지우기") }
                    }
                }
                LabeledSlider("유지 시간", numHold, 600f..2000f, "%.1f초".format(numHold / 1000f)) {
                    numHold = it; Settings.numberHoldMs = it.roundToInt()
                }
            }

            Section("파란 선택 띠 (방향키 이동)") {
                SwitchRow("검지 튕기기로 요소 이동", focus) { focus = it; Settings.focusEnabled = it }
                Hint("검지만 펴고 손끝을 위·아래·좌·우로 살짝 튕기면 파란 띠가 다음 요소로 넘어가요. 엄지와 검지를 집으면 선택, 손바닥을 펴면 종료.")
            }

            Section("인식 조정") {
                LabeledSlider("휘두르기 민감도", sens, 0.6f..1.6f, "%.1f".format(sens)) { sens = it; Settings.sensitivity = it }
                LabeledSlider("손 모양 유지 시간", hold, 300f..1500f, "%.1f초".format(hold / 1000f)) { hold = it; Settings.holdMs = it.roundToInt() }
                SwitchRow("대기 절전 (손 없을 때 처리량 1/3)", saver) { saver = it; Settings.idleSaver = it }
                SwitchRow("화면 상단에 동작 표시", showHud) { showHud = it; Settings.showHud = it }
                Hint("카메라는 60fps로 동작하고, 화면이 꺼지면 자동으로 멈춰요.")
            }

            Section("사용법") {
                Hint("• 손바닥 휘두르기: 위·아래 = 스크롤, 좌·우 = 넘기기 (되돌아오는 손짓은 무시)\n• ✌ 아래로 = 알림창, 세 손가락 아래로 = 제어센터\n• 👍 👎 🤙 👌 ✊ 모양을 멈춰서 유지 = 지정한 동작\n• 휴대폰에서 30~60cm, 손 전체가 카메라에 보이게 해 주세요")
            }
        }

        if (pickFor > 0) {
            AlertDialog(
                onDismissRequest = { pickFor = 0 },
                confirmButton = { TextButton(onClick = { pickFor = 0 }) { Text("닫기") } },
                title = { Text("$pickFor 번에 연결할 앱") },
                text = {
                    if (apps.isEmpty()) Text("앱 목록 불러오는 중…")
                    else LazyColumn(Modifier.height(420.dp)) {
                        items(apps) { app ->
                            Text(
                                app.label,
                                Modifier.fillMaxWidth().clickable {
                                    Settings.setApp(pickFor, app.pkg, app.label)
                                    pickFor = 0; refresh++
                                }.padding(vertical = 12.dp),
                            )
                        }
                    }
                },
            )
        }
    }

    @Composable
    private fun ActionRow(trig: Trigger, refresh: Int, onChanged: () -> Unit) {
        var open by remember { mutableStateOf(false) }
        val cur = remember(refresh) { Settings.action(trig) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(trig.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Box {
                TextButton(onClick = { open = true }) { Text(cur.label) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    GestureAction.values().forEach { a ->
                        DropdownMenuItem(text = { Text(a.label) }, onClick = {
                            Settings.setAction(trig, a); open = false; onChanged()
                        })
                    }
                }
            }
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
            if (ok) Text("✓ 완료", color = MaterialTheme.colorScheme.primary) else TextButton(onClick = onClick) { Text(action) }
        }
    }

    @Composable
    private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(checked, onChange)
        }
    }

    @Composable
    private fun Hint(text: String) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    @Composable
    private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
        Column {
            Row {
                Text(label, Modifier.weight(1f))
                Text(valueText, color = MaterialTheme.colorScheme.primary)
            }
            Slider(value = value, onValueChange = onChange, valueRange = range)
        }
    }
}
