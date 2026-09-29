package com.jel.handgesture

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Display
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 백그라운드 손 인식 서비스 (카메라 포그라운드 서비스).
 * - 전면 카메라 640×480, 손 상태·발열에 따라 60 / 30 / 20 / 15fps 자동 조절
 * - 화면이 꺼지면 카메라를 끄고, 켜지면 다시 켠다
 * - 알림에서 일시정지/재개/종료
 */
class GestureService : LifecycleService() {

    companion object {
        const val ACTION_STOP = "com.jel.handgesture.STOP"
        const val ACTION_TOGGLE = "com.jel.handgesture.TOGGLE"
        const val ACTION_POINTER = "com.jel.handgesture.POINTER"
        const val ACTION_RELOAD = "com.jel.handgesture.RELOAD"
        const val ACTION_POWER = "com.jel.handgesture.POWER"
        private const val CHANNEL = "gesture"
        private const val NOTIF_ID = 1
        private const val TAG = "GestureService"

        @Volatile var engine: GestureEngine? = null
            private set

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, GestureService::class.java))
        }

        fun send(ctx: Context, action: String) {
            ctx.startService(Intent(ctx, GestureService::class.java).setAction(action))
        }
    }

    private var started = false
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    @Volatile private var tracker: HandTracker? = null
    private val exec: ExecutorService = Executors.newSingleThreadExecutor()
    private var screenOn = true

    // ── 발열·배터리 관리 ──
    private val mainH = Handler(Looper.getMainLooper())
    private var camera: Camera? = null
    private var ranges: List<Range<Int>> = emptyList()
    private var curRange: Range<Int>? = null
    private var act = HandActivity.MOVING
    private var thermal = 0
    private var headroomHot = 0
    private var warned = 0
    private var thermalListener: Any? = null
    private val headroomPoll = object : Runnable {
        override fun run() {
            pollHeadroom()
            mainH.postDelayed(this, 10_000)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; unbindCamera() }
                Intent.ACTION_SCREEN_ON -> { screenOn = true; if (!HandState.paused) bindCamera() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_TOGGLE -> if (started) {
                HandState.paused = !HandState.paused
                if (HandState.paused) unbindCamera() else bindCamera()
                notifyState()
                return START_NOT_STICKY
            }
            ACTION_POINTER -> if (started) {
                engine?.requestPointer(null)
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> if (started) {
                reload()
                return START_NOT_STICKY
            }
            ACTION_POWER -> if (started) {
                evaluate()
                return START_NOT_STICKY
            }
        }
        if (!started) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                stopSelf(); return START_NOT_STICKY
            }
            started = true
            HandState.paused = false
            startInForeground()
            setup()
        }
        return START_NOT_STICKY
    }

    private fun action(a: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, GestureService::class.java).setAction(a),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_hand)
        .setContentTitle(
            when {
                HandState.paused -> "손 제스처 일시정지"
                HandState.pointerMode.value -> "포인터 모드"
                else -> "손 제스처 인식 중"
            }
        )
        .setContentText("손이 없으면 자동 절전 · 뜨거워지면 fps 자동 조절 · 화면 꺼지면 정지")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, if (HandState.paused) "재개" else "일시정지", action(ACTION_TOGGLE, 1))
        .addAction(0, if (HandState.pointerMode.value) "제스처 모드" else "포인터 모드", action(ACTION_POINTER, 3))
        .addAction(0, "종료", action(ACTION_STOP, 2))
        .build()

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "손 제스처", NotificationManager.IMPORTANCE_LOW))
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    private fun notifyState() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun setup() {
        val sink = ControlSink { ContextCompat.getMainExecutor(this).execute { if (started) notifyState() } }
        val e = GestureEngine(sink)
        engine = e
        tracker = try {
            newTracker(e)
        } catch (ex: Exception) {
            Log.e(TAG, "모델 로드 실패", ex); stopSelf(); return
        }
        startThermal()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = try { future.get() } catch (ex: Exception) { stopSelf(); return@addListener }
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    /** 카메라 관련 설정(해상도·fps·인식 기준)을 바꿨을 때 다시 연결 */
    private fun reload() {
        val e = engine ?: return
        provider?.unbindAll()
        e.release()
        val old = tracker
        tracker = null
        exec.execute { old?.close() }
        tracker = try {
            newTracker(e)
        } catch (ex: Exception) {
            Log.e(TAG, "모델 로드 실패", ex); stopSelf(); return
        }
        bindCamera()
    }

    private fun newTracker(e: GestureEngine) = HandTracker(this, e::onFrame, e::onNoHand) { a ->
        mainH.post { act = a; evaluate() }
    }

    // ───────── 발열·배터리 관리 ─────────

    /**
     * 손 상태 × 성능 모드 × 발열 상태 → 카메라 fps.
     *  손 움직임: 60 (절전 모드 30) · 손 멈춤: 30 (최고 모드 60) · 손 없음: 15
     *  발열 '주의' → 최대 30, '심함' → 20 고정
     */
    private fun evaluate() {
        val mode = Settings.perfMode
        val idleSave = Settings.idleSaver
        var target = when (act) {
            HandActivity.MOVING -> if (mode == 0) 30 else 60
            HandActivity.STILL -> if (mode == 2) 60 else 30
            HandActivity.NO_HAND, HandActivity.DEEP_IDLE -> if (idleSave) 15 else if (mode == 0) 30 else 60
        }
        var heat = 0
        if (Settings.thermalGuard) {
            heat = maxOf(headroomHot, if (thermal >= 3) 2 else if (thermal >= 2) 1 else 0)
            if (heat == 1) target = minOf(target, 30)
            if (heat >= 2) target = minOf(target, 20)
        }
        HandState.heat = heat
        HandState.activity = act
        tracker?.let {
            it.idleCap = if (heat >= 2 || mode == 0) 5 else 10
            it.deepCap = if (heat >= 2 || mode == 0) 2 else 4
        }
        if (heat > warned) {
            ControlService.instance?.notice(
                if (heat >= 2) "🔥 휴대폰이 뜨거워서 인식을 20fps로 낮췄어요" else "휴대폰이 따뜻해져서 인식을 30fps로 낮췄어요",
            )
        }
        warned = heat
        applyFps(target)
    }

    /** 목표 fps 이상인 구간 중 가장 낮은 것 (같으면 고정 fps 구간 우선) */
    private fun pickRange(target: Int): Range<Int>? {
        if (ranges.isEmpty()) return null
        return ranges.filter { it.upper >= target }.minWithOrNull(compareBy<Range<Int>> { it.upper }.thenByDescending { it.lower })
            ?: ranges.maxWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
    }

    private fun applyFps(target: Int) {
        val cam = camera ?: return
        val r = pickRange(target) ?: return
        if (r == curRange) return
        curRange = r
        try {
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, r)
                    .build(),
            )
            HandState.fpsRange = if (r.lower == r.upper) "${r.upper}" else "${r.lower}–${r.upper}"
        } catch (e: Exception) {
            Log.w(TAG, "fps 변경 실패", e)
        }
    }

    private fun startThermal() {
        if (Build.VERSION.SDK_INT < 29) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        thermal = pm.currentThermalStatus
        val l = PowerManager.OnThermalStatusChangedListener { st -> thermal = st; evaluate() }
        try { pm.addThermalStatusListener(ContextCompat.getMainExecutor(this), l); thermalListener = l } catch (_: Exception) {}
        mainH.post(headroomPoll)
    }

    private fun stopThermal() {
        mainH.removeCallbacks(headroomPoll)
        if (Build.VERSION.SDK_INT < 29) return
        val l = thermalListener as? PowerManager.OnThermalStatusChangedListener ?: return
        try { getSystemService(PowerManager::class.java)?.removeThermalStatusListener(l) } catch (_: Exception) {}
        thermalListener = null
    }

    /** 10초 뒤 발열 예측치(1.0 = 성능 제한 시작). 제한 전에 미리 낮춘다 */
    private fun pollHeadroom() {
        if (Build.VERSION.SDK_INT < 30) return
        val hr = try { getSystemService(PowerManager::class.java)?.getThermalHeadroom(10) ?: Float.NaN } catch (_: Exception) { Float.NaN }
        HandState.headroom = hr
        if (hr.isNaN()) return
        val lvl = when {
            hr >= 0.95f -> 2
            hr >= 0.8f -> 1
            hr < 0.7f -> 0
            else -> minOf(headroomHot, 1)   // 0.7~0.8: 조금 식을 때까지 유지
        }
        if (lvl != headroomHot) { headroomHot = lvl; evaluate() }
    }

    /** 전면 카메라가 지원하는 fps 구간 목록 */
    private fun loadRanges(): List<Range<Int>> = try {
        val cm = getSystemService(CameraManager::class.java)
        var list: List<Range<Int>> = emptyList()
        for (id in cm.cameraIdList) {
            val ch = cm.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_FRONT) continue
            list = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: continue
            break
        }
        list
    } catch (e: Exception) {
        emptyList()
    }

    private fun bindCamera() {
        val p = provider ?: return
        val t = tracker ?: return
        if (!screenOn || HandState.paused) return
        val builder = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(
                        if (Settings.highRes) Size(1280, 720) else Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    )
                ).build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetRotation(displayRotation())
        if (ranges.isEmpty()) ranges = loadRanges()
        val a = builder.build()
        a.setAnalyzer(exec) { proxy -> t.analyze(proxy) }
        analysis = a
        try {
            p.unbindAll()
            camera = p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, a)
            curRange = null
            evaluate()
            HandState.running.value = true
            notifyState()
        } catch (e: Exception) {
            Log.e(TAG, "카메라 연결 실패", e)
            stopSelf()
        }
    }

    private fun unbindCamera() {
        provider?.unbindAll()
        camera = null
        HandState.frame = null
        engine?.release()
    }

    private fun displayRotation(): Int =
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).rotation

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        analysis?.targetRotation = displayRotation()
    }

    override fun onDestroy() {
        HandState.running.value = false
        HandState.frame = null
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        stopThermal()
        provider?.unbindAll()
        camera = null
        engine?.release()
        engine = null
        HandState.pointerMode.value = false
        val t = tracker
        tracker = null
        exec.execute { t?.close() }
        exec.shutdown()
        ControlService.instance?.clearOverlays()
        super.onDestroy()
    }
}

/** 인식 결과 → 접근성 서비스 실행 */
class ControlSink(private val onModeChanged: () -> Unit) : GestureSink {
    private val svc get() = ControlService.instance

    override fun onTrigger(t: Trigger) {
        val a = Settings.action(t)
        HandState.lastEvent = "${t.short} → ${a.label}"
        if (a == GestureAction.NONE) return
        svc?.perform(a, "${t.short}  ${a.label}")
    }

    override fun onNumber(n: Int) {
        val pkg = Settings.app(n) ?: return
        val label = Settings.appLabel(n) ?: pkg
        HandState.lastEvent = "$n → $label"
        svc?.launchApp(pkg, "$n  $label")
    }

    override fun onProgress(label: String?, p: Float) { svc?.progress(label, p) }
    override fun onWheel(dx: Float, dy: Float, ax: Float, ay: Float) { svc?.wheel(dx, dy, ax, ay) }
    override fun onWheelEnd(fling: Boolean) { svc?.wheelEnd(fling) }

    override fun onPointerMode(on: Boolean) {
        HandState.lastEvent = if (on) "포인터 모드 켬" else "포인터 모드 끔"
        svc?.pointerMode(on)
        onModeChanged()
    }

    override fun onPointers(p: PointerFrame?) { svc?.pointers(p) }
    override fun onPointerClick(xs: FloatArray, ys: FloatArray) { svc?.pointerClick(xs, ys) }
}
