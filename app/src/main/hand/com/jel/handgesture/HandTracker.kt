package com.jel.handgesture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.hypot

/** 손 활동 상태 → 서비스가 카메라 fps를 정한다 */
enum class HandActivity { MOVING, STILL, NO_HAND, DEEP_IDLE }

/**
 * 카메라 프레임 → MediaPipe 손 랜드마크 → HandFrame.
 * 발열·배터리 최적화:
 *  - 프레임마다 새 Bitmap을 만들지 않고 버퍼 재사용 (GC 없음), GPU 추론 (안 되면 CPU)
 *  - 추론 중이면 새 프레임은 바로 버림 (지연 누적 없음)
 *  - 손이 없으면 초당 처리 횟수를 제한하고(10회 → 20초 뒤 4회) 영상도 절반 크기로 줄여 손바닥 찾기만 가볍게
 *  - 손 활동 상태(움직임/멈춤/없음/오래 없음)가 바뀔 때만 서비스에 알려 카메라 fps 자체를 낮춘다
 */
class HandTracker(
    context: Context,
    private val onHand: (HandFrame) -> Unit,
    private val onNoHand: (Long) -> Unit,
    private val onActivity: (HandActivity) -> Unit,
) {
    private val landmarker: HandLandmarker
    @Volatile private var closed = false
    @Volatile private var inFlight = false
    private var lastTs = 0L
    @Volatile private var lastHandT = 0L
    private var lastProcT = 0L

    private var src: Bitmap? = null
    private val dst = arrayOfNulls<Bitmap>(2)          // 0 = 원래 크기, 1 = 절반
    private val dstCanvas = arrayOfNulls<Canvas>(2)
    private val matrix = Matrix()
    private val paint = Paint()

    // 활동 상태
    @Volatile private var activity = HandActivity.MOVING
    private var lastMoveT = 0L
    private var prevPx = 0f
    private var prevPy = 0f
    private var prevPose = Pose.NONE
    private var startT = 0L

    /** 손이 없을 때 초당 최대 처리 횟수 (서비스가 정함, 0 = 제한 없음) */
    @Volatile var idleCap = 10
    @Volatile var deepCap = 4

    // 속도 측정
    private var camCount = 0
    private var procCount = 0
    private var fpsT0 = 0L

    init {
        landmarker = try {
            create(context, Delegate.GPU).also { HandState.gpu = true }
        } catch (e: Throwable) {
            Log.w(TAG, "GPU unavailable, using CPU", e)
            HandState.gpu = false
            create(context, Delegate.CPU)
        }
    }

    private fun create(ctx: Context, delegate: Delegate): HandLandmarker {
        val base = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .setDelegate(delegate)
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(Settings.detectConf.coerceIn(0.2f, 0.95f))
            .setMinHandPresenceConfidence(Settings.trackConf.coerceIn(0.2f, 0.95f))
            .setMinTrackingConfidence(Settings.trackConf.coerceIn(0.2f, 0.95f))
            .setResultListener { r: HandLandmarkerResult, img: MPImage -> onResult(r, img) }
            .setErrorListener { e: RuntimeException -> inFlight = false; Log.e(TAG, "landmarker error", e) }
            .build()
        return HandLandmarker.createFromOptions(ctx, options)
    }

    /** 카메라 분석 스레드에서 호출 */
    fun analyze(proxy: ImageProxy) {
        val now = SystemClock.uptimeMillis()
        if (startT == 0L) { startT = now; lastHandT = now; lastMoveT = now }
        camCount++
        if (fpsT0 == 0L) fpsT0 = now
        if (now - fpsT0 >= 1000) {
            HandState.cameraFps = camCount * 1000f / (now - fpsT0)
            HandState.procFps = procCount * 1000f / (now - fpsT0)
            camCount = 0; procCount = 0; fpsT0 = now
        }

        // 손 없음 상태 판정 (손이 있을 때는 onResult에서 판정)
        val noHandFor = now - lastHandT
        if (noHandFor > 20_000) setActivity(HandActivity.DEEP_IDLE)
        else if (noHandFor > 1500) setActivity(HandActivity.NO_HAND)
        val a = activity
        val idle = a == HandActivity.NO_HAND || a == HandActivity.DEEP_IDLE
        HandState.idle = idle

        val cap = when {
            !idle || !Settings.idleSaver -> 0
            a == HandActivity.DEEP_IDLE -> deepCap
            else -> idleCap
        }
        if (closed || inFlight || (cap > 0 && now - lastProcT < 1000L / cap)) { proxy.close(); return }

        val w = proxy.width
        val h = proxy.height
        val rot = proxy.imageInfo.rotationDegrees
        try {
            val plane = proxy.planes[0]
            val strideW = plane.rowStride / plane.pixelStride
            var s = src
            if (s == null || s.width != strideW || s.height != h) {
                s = Bitmap.createBitmap(strideW, h, Bitmap.Config.ARGB_8888); src = s
            }
            val buf = plane.buffer
            buf.rewind()
            s.copyPixelsFromBuffer(buf)
        } catch (e: Exception) {
            Log.w(TAG, "copy failed", e)
            proxy.close()
            return
        }
        proxy.close()

        // 손이 없을 때는 절반 크기로 (손바닥 찾기 모델 입력이 192px라 충분)
        val k = if (idle && Settings.idleSaver) 1 else 0
        val sc = if (k == 1) 0.5f else 1f
        val tw = ((if (rot % 180 == 0) w else h) * sc).toInt()
        val th = ((if (rot % 180 == 0) h else w) * sc).toInt()
        var d = dst[k]
        if (d == null || d.width != tw || d.height != th) {
            d = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            dst[k] = d
            dstCanvas[k] = Canvas(d)
            HandState.aspect = tw.toFloat() / th
        }
        // 회전 + 좌우 반전(셀카 방향) + 축소: 사용자 오른쪽 = 화면 오른쪽
        matrix.reset()
        matrix.postTranslate(-w / 2f, -h / 2f)
        matrix.postRotate(rot.toFloat())
        matrix.postScale(-sc, sc)
        matrix.postTranslate(tw / 2f, th / 2f)
        dstCanvas[k]!!.drawBitmap(src!!, matrix, paint)

        var ts = now
        if (ts <= lastTs) ts = lastTs + 1
        lastTs = ts
        lastProcT = now
        inFlight = true
        try {
            landmarker.detectAsync(BitmapImageBuilder(d).build(), ts)
        } catch (e: Exception) {
            inFlight = false
            Log.w(TAG, "detectAsync failed", e)
        }
    }

    private fun onResult(r: HandLandmarkerResult, img: MPImage) {
        inFlight = false
        procCount++
        val now = SystemClock.uptimeMillis()
        HandState.latencyMs += 0.1f * ((now - r.timestampMs()) - HandState.latencyMs)
        val hands = r.landmarks()
        if (hands.isEmpty()) {
            HandGeometry.reset()
            HandState.frame = null
            onNoHand(now)
            return
        }
        val world = r.worldLandmarks().firstOrNull()
        val f = HandGeometry.analyze(now, hands[0], world, HandState.aspect)
        if (f == null) { onNoHand(now); return }
        lastHandT = now

        // 움직임 판정: 손바닥 크기의 4% 이상 이동하거나 손 모양이 바뀌면 '움직임'
        val moved = hypot(f.palmX - prevPx, f.palmY - prevPy) / f.palmSize
        prevPx = f.palmX; prevPy = f.palmY
        val sp = HandState.stablePose   // 확정된 모양 기준 (프레임마다 흔들리는 모양은 무시)
        val poseChanged = sp != prevPose
        prevPose = sp
        if (moved > 0.04f || poseChanged || activity == HandActivity.NO_HAND || activity == HandActivity.DEEP_IDLE) lastMoveT = now
        setActivity(if (now - lastMoveT > 1500) HandActivity.STILL else HandActivity.MOVING)

        HandState.frame = f
        onHand(f)
    }

    private fun setActivity(a: HandActivity) {
        if (activity == a) return
        activity = a
        onActivity(a)
    }

    fun close() {
        closed = true
        try { landmarker.close() } catch (_: Exception) {}
    }

    companion object { private const val TAG = "HandTracker" }
}
