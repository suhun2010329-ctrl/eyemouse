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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private var camOk by mutableStateOf(false)
    private var a11yOn by mutableStateOf(false)
    private var notifOk by mutableStateOf(true)
    /** 화면 복귀·초기화 때 설정값을 다시 읽게 하는 버전 */
    private var ver by mutableIntStateOf(0)

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
        ver++
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun applyPower() {
        if (HandState.running.value) GestureService.send(this, GestureService.ACTION_POWER)
    }

    private fun reloadCamera() {
        if (HandState.running.value) GestureService.send(this, GestureService.ACTION_RELOAD)
    }

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
        val pointer by HandState.pointerMode.collectAsState()
        val v = ver
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
            Text(
                when {
                    !running -> "꺼져 있음"
                    pointer -> "실행 중 · 포인터 모드"
                    else -> "실행 중 · 제스처 모드"
                },
                color = MaterialTheme.colorScheme.primary,
            )

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
            if (running) {
                OutlinedButton(
                    onClick = { GestureService.send(this@MainActivity, GestureService.ACTION_POINTER) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (pointer) "제스처 모드로 전환" else "포인터 모드로 전환") }
            }
            OutlinedButton(
                onClick = { startActivity(Intent(this@MainActivity, TestActivity::class.java)) },
                enabled = camOk,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("제스처 테스트 (인식 상태 · fps 확인)") }

            // ── 제스처 → 동작 ──
            Section("제스처 → 동작") {
                val r = refresh
                var lastGroup = ""
                Trigger.values().forEach { trig ->
                    if (trig.group != lastGroup) {
                        if (lastGroup.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        lastGroup = trig.group
                        Text(trig.group, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    ActionRow(trig, r) { refresh++ }
                }
                TextButton(onClick = { Settings.resetActions(); refresh++ }) { Text("동작 지정 기본값으로") }
            }

            // ── 쓸기 (휠) ──
            Section("쓸기 (마우스 휠처럼)") {
                Hint("손을 모으거나(다섯 손끝을 한데 모음) 검지를 편 채 위·아래·옆으로 움직이면 화면이 손을 따라 바로 스크롤돼요. 포인터는 나오지 않아요.")
                PrefSwitch("쓸기 사용", v, { Settings.wheelEnabled }) { Settings.wheelEnabled = it }
                PrefChoice("쓸기 손 모양", listOf("손 모으기", "검지", "둘 다"), v, { Settings.wheelPose }) { Settings.wheelPose = it }
                Hint("‘검지’를 고르면 검지 휘두르기와 숫자 1 바로가기는 꺼져요.")
                PrefSlider("쓸기 속도", v, 0.4f..4f, { "%.1f배".format(it) }, { Settings.wheelGain }) { Settings.wheelGain = it }
                PrefSwitch("방향 반대로 (마우스 휠 방식)", v, { Settings.wheelReverse }) { Settings.wheelReverse = it }
                PrefSwitch("옆으로도 쓸기", v, { Settings.wheelHorizontal }) { Settings.wheelHorizontal = it }
                PrefSwitch("한 방향으로 고정 (대각선 흔들림 방지)", v, { Settings.wheelAxisLock }) { Settings.wheelAxisLock = it }
                PrefSwitch("빠르게 놓으면 관성으로 더 넘어가기", v, { Settings.wheelInertia }) { Settings.wheelInertia = it }
            }

            // ── 포인터 모드 ──
            Section("포인터 모드 (손끝 5개 포인터)") {
                Hint("편 손가락 끝마다 색 점이 나와요. 한 곳에 머무르면 고리가 차오르고 클릭돼요. 다섯 손끝을 모아 움직이면 그 자리를 잡고 쓸어요. ✊ 주먹을 유지하면 꺼져요.")
                PrefSwitch("머무르면 클릭", v, { Settings.dwellClick }) { Settings.dwellClick = it }
                PrefSlider("클릭까지 머무는 시간", v, 400f..2500f, { "%.1f초".format(it / 1000f) }, { Settings.dwellMs.toFloat() }) { Settings.dwellMs = it.roundToInt() }
                PrefSlider("머무름 허용 반경", v, 8f..60f, { "${it.roundToInt()}dp" }, { Settings.dwellRadiusDp }) { Settings.dwellRadiusDp = it }
                PrefSwitch("누를 수 있는 곳 위의 포인터 우선", v, { Settings.clickableFirst }) { Settings.clickableFirst = it }
                PrefSlider("손 이동 범위", v, 0.35f..1f, { "${(it * 100).roundToInt()}%" }, { Settings.pointerRange }) { Settings.pointerRange = it }
                Hint("작을수록 손을 조금만 움직여도 화면 끝까지 가요.")
                PrefSlider("떨림 보정", v, 0f..1f, { "${(it * 100).roundToInt()}%" }, { Settings.pointerSmooth }) { Settings.pointerSmooth = it }
                PrefSlider("포인터 크기", v, 0.6f..1.8f, { "%.1f배".format(it) }, { Settings.pointerSize }) { Settings.pointerSize = it }
                Text("포인터로 쓸 손가락")
                FingerToggles(v)
                PrefChoice("주먹 유지 = 포인터 모드 켜기", listOf("켜기", "끄기"), refresh + v * 1000,
                    { if (Settings.action(Trigger.FIST) == GestureAction.POINTER_MODE) 0 else 1 }) {
                    Settings.setAction(Trigger.FIST, if (it == 0) GestureAction.POINTER_MODE else GestureAction.NONE); refresh++
                }
            }

            // ── 숫자 바로가기 ──
            Section("숫자 바로가기") {
                PrefSwitch("손가락 수로 앱 열기", v, { Settings.numbersEnabled }) { Settings.numbersEnabled = it }
                Hint("손가락 1~5개를 편 채 멈추면 진행 막대가 차고 앱이 열려요. 1은 검지만 펴기.")
                val r = refresh
                for (n in 1..5) {
                    val label = remember(r, v) { Settings.appLabel(n) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$n", Modifier.padding(end = 12.dp), style = MaterialTheme.typography.titleMedium)
                        Text(label ?: "지정 안 됨", Modifier.weight(1f), color = if (label == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        TextButton(onClick = { pickFor = n }) { Text("앱 선택") }
                        if (label != null) TextButton(onClick = { Settings.setApp(n, null, null); refresh++ }) { Text("지우기") }
                    }
                }
                PrefSlider("유지 시간", v, 500f..2000f, { "%.1f초".format(it / 1000f) }, { Settings.numberHoldMs.toFloat() }) { Settings.numberHoldMs = it.roundToInt() }
            }

            // ── 휘두르기 인식 ──
            Section("휘두르기·손 모양 인식") {
                PrefSlider("거리 민감도", v, 0.5f..2f, { "%.1f".format(it) }, { Settings.sensitivity }) { Settings.sensitivity = it }
                Hint("높을수록 조금만 휘둘러도 인식해요.")
                PrefSlider("속도 민감도", v, 0.5f..2f, { "%.1f".format(it) }, { Settings.speedSens }) { Settings.speedSens = it }
                Hint("높을수록 천천히 움직여도 인식해요. 낮추면 실수로 실행되는 일이 줄어요.")
                PrefSlider("되돌아오는 손짓 무시", v, 0f..1200f, { "%.1f초".format(it / 1000f) }, { Settings.reboundMs.toFloat() }) { Settings.reboundMs = it.roundToInt() }
                PrefSlider("동작 사이 최소 간격", v, 150f..1000f, { "%.2f초".format(it / 1000f) }, { Settings.cooldownMs.toFloat() }) { Settings.cooldownMs = it.roundToInt() }
                PrefSlider("손 모양 유지 시간", v, 300f..1500f, { "%.1f초".format(it / 1000f) }, { Settings.holdMs.toFloat() }) { Settings.holdMs = it.roundToInt() }
                PrefSlider("‘쓸기’ 한 번 거리", v, 0.2f..0.7f, { "화면의 ${(it * 100).roundToInt()}%" }, { Settings.swipeDistance }) { Settings.swipeDistance = it }
            }

            // ── 발열·배터리 ──
            Section("발열·배터리") {
                PrefChoice("성능 모드", listOf("절전", "균형", "최고"), v, { Settings.perfMode }) { Settings.perfMode = it; applyPower() }
                Hint("절전: 최대 30fps · 균형: 손을 움직일 때만 60fps, 멈추면 30fps · 최고: 손이 보이면 항상 60fps")
                PrefSwitch("손이 없으면 절전 (15fps, 초당 10번만 확인)", v, { Settings.idleSaver }) { Settings.idleSaver = it; applyPower() }
                PrefSwitch("발열 보호 (뜨거워지기 전에 fps 자동 낮춤)", v, { Settings.thermalGuard }) { Settings.thermalGuard = it; applyPower() }
                Hint("휴대폰 온도가 오르기 시작하면 30fps, 뜨거우면 20fps로 낮추고 식으면 되돌려요. 화면이 꺼지면 카메라도 꺼져요.")
            }

            // ── 손 인식·카메라 ──
            Section("손 인식·카메라") {
                PrefSlider("손 감지 기준", v, 0.3f..0.9f, { "%.2f".format(it) }, { Settings.detectConf }, ::reloadCamera) { Settings.detectConf = it }
                Hint("높이면 손이 아닌 것을 손으로 잘못 잡는 일이 줄고, 낮추면 어두운 곳·먼 거리에서도 잡아요.")
                PrefSlider("추적 유지 기준", v, 0.3f..0.9f, { "%.2f".format(it) }, { Settings.trackConf }, ::reloadCamera) { Settings.trackConf = it }
                Hint("낮추면 빠르게 휘둘러도 손을 놓치지 않아요.")
                PrefSlider("손 모양 확정 프레임", v, 1f..6f, { "${it.roundToInt()}프레임" }, { Settings.poseFrames.toFloat() }, steps = 4) { Settings.poseFrames = it.roundToInt() }
                Hint("높일수록 손 모양 인식이 안정적이지만 조금 늦어져요. 60fps에서 3프레임 = 0.05초.")
                PrefChoice("카메라 해상도", listOf("640×480 빠름", "1280×720 먼 거리"), v, { if (Settings.highRes) 1 else 0 }) {
                    Settings.highRes = it == 1; reloadCamera()
                }
                PrefSwitch("화면 상단에 동작 표시", v, { Settings.showHud }) { Settings.showHud = it }
                TextButton(onClick = { Settings.resetTuning(); ver++; reloadCamera() }) { Text("인식·쓸기·포인터 설정 기본값으로") }
            }

            Section("사용법") {
                Hint(
                    "• 손 모아 움직이기: 휠처럼 스크롤 (위·아래·옆)\n" +
                        "• 손바닥 휘두르기: ↑↓ 쓸기, ← 다음, → 뒤로\n" +
                        "• 검지 휘두르기: ↑ 아래 내용, ↓ 위 내용, → 뒤로\n" +
                        "• ✌ ↓ 알림창, ✌ ↑ 닫기 · 세 손가락 ↓ 제어센터, ↑ 홈\n" +
                        "• 손바닥을 카메라 쪽으로 밀기: 음악 재생/정지\n" +
                        "• 👍 👎 🤙 👌 🤘 🤟 모양을 멈춰서 유지: 지정한 동작\n" +
                        "• ✊ 유지: 포인터 모드 켜기/끄기\n" +
                        "• 휴대폰에서 30~60cm, 손 전체가 카메라에 보이게 해 주세요",
                )
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
        val cur = remember(refresh, ver) { Settings.action(trig) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(trig.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Box {
                TextButton(onClick = { open = true }) {
                    Text(cur.label, color = if (cur == GestureAction.NONE) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                }
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
    private fun FingerToggles(v: Int) {
        val names = listOf("엄지", "검지", "중지", "약지", "새끼")
        var mask by remember(v) { mutableIntStateOf(Settings.pointerFingers) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            names.forEachIndexed { i, name ->
                val on = (mask shr i) and 1 == 1
                val click = {
                    mask = mask xor (1 shl i)
                    Settings.pointerFingers = mask
                }
                val mod = Modifier.weight(1f)
                val pad = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
                if (on) Button(onClick = click, modifier = mod, contentPadding = pad) { Text(name, fontSize = 13.sp) }
                else OutlinedButton(onClick = click, modifier = mod, contentPadding = pad) { Text(name, fontSize = 13.sp) }
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
    private fun PrefSwitch(label: String, v: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
        var checked by remember(v) { mutableStateOf(get()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(checked, { checked = it; set(it) })
        }
    }

    @Composable
    private fun PrefChoice(label: String, options: List<String>, v: Int, get: () -> Int, set: (Int) -> Unit) {
        var sel by remember(v) { mutableIntStateOf(get()) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEachIndexed { i, o ->
                    val click = { sel = i; set(i) }
                    val mod = Modifier.weight(1f)
                    val pad = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    if (i == sel) Button(onClick = click, modifier = mod, contentPadding = pad) { Text(o, fontSize = 13.sp) }
                    else OutlinedButton(onClick = click, modifier = mod, contentPadding = pad) { Text(o, fontSize = 13.sp) }
                }
            }
        }
    }

    @Composable
    private fun PrefSlider(
        label: String,
        v: Int,
        range: ClosedFloatingPointRange<Float>,
        fmt: (Float) -> String,
        get: () -> Float,
        onDone: (() -> Unit)? = null,
        steps: Int = 0,
        set: (Float) -> Unit,
    ) {
        var value by remember(v) { mutableFloatStateOf(get().coerceIn(range.start, range.endInclusive)) }
        Column {
            Row {
                Text(label, Modifier.weight(1f))
                Text(fmt(value), color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = value,
                onValueChange = { value = it; set(it) },
                valueRange = range,
                steps = steps,
                onValueChangeFinished = onDone,
            )
        }
    }

    @Composable
    private fun Hint(text: String) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
