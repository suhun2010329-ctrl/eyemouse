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
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Display
import androidx.camera.camera2.interop.Camera2Interop
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
 * - 전면 카메라 640×480 @ 60fps
 * - 화면이 꺼지면 카메라를 끄고, 켜지면 다시 켠다
 * - 알림에서 일시정지/재개/종료
 */
class GestureService : LifecycleService() {

    companion object {
        const val ACTION_STOP = "com.jel.handgesture.STOP"
        const val ACTION_TOGGLE = "com.jel.handgesture.TOGGLE"
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
        .setContentTitle(if (HandState.paused) "손 제스처 일시정지" else "손 제스처 인식 중")
        .setContentText("전면 카메라 ${HandState.fpsRange}fps · 화면이 꺼지면 자동 정지")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, if (HandState.paused) "재개" else "일시정지", action(ACTION_TOGGLE, 1))
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
        val sink = ControlSink()
        val e = GestureEngine(sink)
        engine = e
        tracker = try {
            HandTracker(this, e::onFrame, e::onNoHand)
        } catch (ex: Exception) {
            Log.e(TAG, "모델 로드 실패", ex); stopSelf(); return
        }
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

    /** 전면 카메라가 지원하는 60fps 구간 선택 (없으면 가장 높은 구간) */
    private fun pickFpsRange(): Range<Int>? = try {
        val cm = getSystemService(CameraManager::class.java)
        var best: Range<Int>? = null
        for (id in cm.cameraIdList) {
            val ch = cm.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_FRONT) continue
            val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: continue
            best = ranges.filter { it.upper >= 60 }.maxByOrNull { it.lower }
                ?: ranges.maxByOrNull { it.upper * 1000 + it.lower }
            break
        }
        best
    } catch (e: Exception) {
        null
    }

    private fun bindCamera() {
        val p = provider ?: return
        val t = tracker ?: return
        if (!screenOn || HandState.paused) return
        val builder = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                ).build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetRotation(displayRotation())
        val range = pickFpsRange()
        if (range != null) {
            Camera2Interop.Extender(builder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            HandState.fpsRange = if (range.lower == range.upper) "${range.upper}" else "${range.lower}–${range.upper}"
        }
        val a = builder.build()
        a.setAnalyzer(exec) { proxy -> t.analyze(proxy) }
        analysis = a
        try {
            p.unbindAll()
            p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, a)
            HandState.running.value = true
            notifyState()
        } catch (e: Exception) {
            Log.e(TAG, "카메라 연결 실패", e)
            stopSelf()
        }
    }

    private fun unbindCamera() {
        provider?.unbindAll()
        HandState.frame = null
        engine?.exitFocus()
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
        provider?.unbindAll()
        engine?.exitFocus()
        engine = null
        val t = tracker
        tracker = null
        exec.execute { t?.close() }
        exec.shutdown()
        ControlService.instance?.clearOverlays()
        super.onDestroy()
    }
}

/** 인식 결과 → 접근성 서비스 실행 */
class ControlSink : GestureSink {
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

    override fun onFocusEnter() { HandState.lastEvent = "☝ 선택 모드"; svc?.focusEnter() }
    override fun onFocusMove(dx: Int, dy: Int) { svc?.focusMove(dx, dy) }
    override fun onFocusClick() { HandState.lastEvent = "🤏 선택"; svc?.focusClick() }
    override fun onFocusExit() { svc?.focusExit() }
    override fun onProgress(label: String?, p: Float) { svc?.progress(label, p) }
}
