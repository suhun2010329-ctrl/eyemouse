package com.jel.eyemouse

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.Display
import androidx.camera.camera2.interop.Camera2CameraInfo
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 전면 카메라 + 얼굴 추적을 돌리는 포그라운드 서비스 */
class TrackerService : LifecycleService() {

    companion object {
        const val ACTION_STOP = "com.jel.eyemouse.STOP"
        const val ACTION_PAUSE = "com.jel.eyemouse.PAUSE"
        const val ACTION_RECENTER = "com.jel.eyemouse.RECENTER"
        private const val CHANNEL = "tracker"
        private const val NOTIF_ID = 1
        private const val TAG = "TrackerService"
        private const val DEFAULT_TEXT = "눈 2초 감기 = 일시정지/재개"

        @Volatile var controller: CursorController? = null
            private set

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TrackerService::class.java))
        }

        fun send(ctx: Context, action: String) {
            ctx.startService(Intent(ctx, TrackerService::class.java).setAction(action))
        }
    }

    private var started = false
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    @Volatile private var tracker: FaceTracker? = null
    private val exec: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_PAUSE -> controller?.togglePause()
            ACTION_RECENTER -> controller?.requestRecenter()
        }
        if (!started) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                stopSelf(); return START_NOT_STICKY
            }
            started = true
            startInForeground()
            startTracking()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_eye)
        .setContentTitle("아이마우스 작동 중")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        )
        .addAction(0, "일시정지/재개", action(ACTION_PAUSE, 1))
        .addAction(0, "센터", action(ACTION_RECENTER, 2))
        .addAction(0, "종료", action(ACTION_STOP, 3))
        .build()

    private fun action(a: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, TrackerService::class.java).setAction(a),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "시선 추적", NotificationManager.IMPORTANCE_LOW)
        )
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(DEFAULT_TEXT), type)

        // 품질 저하 원인이 1초 이상 이어지면 알림 문구로 안내
        lifecycleScope.launch {
            var shown = DEFAULT_TEXT
            var candidate: String? = null
            var since = 0L
            while (true) {
                delay(500)
                val c = if (QualityMonitor.state == TrackState.GOOD) null else QualityMonitor.cause?.msg
                val now = System.currentTimeMillis()
                if (c != candidate) { candidate = c; since = now }
                val want = if (candidate != null && now - since >= 1000) candidate!! else DEFAULT_TEXT
                if (want != shown && (candidate == null || now - since >= 1000)) {
                    shown = want
                    nm.notify(NOTIF_ID, buildNotification(want))
                }
            }
        }
    }

    private fun startTracking() {
        val c = CursorController(this)
        controller = c
        EyeMouseState.paused = false
        QualityMonitor.reset()
        PoseSensor.start(this)
        tracker = try {
            FaceTracker(this, c::onFeatures, c::onNoFace)
        } catch (e: Exception) {
            Log.e(TAG, "모델 로드 실패", e); stopSelf(); return
        }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val p = try { future.get() } catch (e: Exception) { stopSelf(); return@addListener }
            provider = p
            val a = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 960),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            )
                        ).build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetRotation(displayRotation())
                .build()
            a.setAnalyzer(exec) { proxy ->
                val t = tracker
                if (t == null) proxy.close() else t.analyze(proxy)
            }
            analysis = a
            try {
                p.unbindAll()
                val cam = p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, a)
                readIntrinsics(cam)
                EyeMouseState.running.value = true
            } catch (e: Exception) {
                Log.e(TAG, "카메라 연결 실패", e)
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 초점거리(mm) ÷ 센서 긴 변(mm) — 홍채 크기로 거리를 계산할 때 사용 */
    private fun readIntrinsics(cam: Camera) {
        try {
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val size = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (focal != null && size != null) {
                val longSide = maxOf(size.width, size.height)
                if (longSide > 0f) EyeMouseState.focalRatio = (focal / longSide).coerceIn(0.3f, 1.5f)
            }
        } catch (e: Exception) {
            Log.w(TAG, "intrinsics unavailable", e)
        }
    }

    private fun displayRotation(): Int =
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).rotation

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        analysis?.targetRotation = displayRotation()
        controller?.onRotation()
    }

    override fun onDestroy() {
        EyeMouseState.running.value = false
        PoseSensor.stop()
        provider?.unbindAll()
        controller = null
        val t = tracker
        tracker = null
        exec.execute { t?.close() }
        exec.shutdown()
        EyeMouseAccessibilityService.instance?.hideCursor()
        super.onDestroy()
    }
}
