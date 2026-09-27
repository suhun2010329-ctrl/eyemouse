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

/**
 * 카메라 프레임 → MediaPipe 손 랜드마크 → HandFrame.
 * 최적화:
 *  - 프레임마다 새 Bitmap을 만들지 않고 버퍼를 재사용 (GC 없음)
 *  - GPU 추론 (안 되면 CPU)
 *  - 추론 중이면 새 프레임은 바로 버림 (지연 누적 없음)
 *  - 손이 1.5초 이상 안 보이면 3프레임 중 1프레임만 처리 (대기 절전)
 */
class HandTracker(
    context: Context,
    private val onHand: (HandFrame) -> Unit,
    private val onNoHand: (Long) -> Unit,
) {
    private val landmarker: HandLandmarker
    @Volatile private var closed = false
    @Volatile private var inFlight = false
    private var lastTs = 0L
    private var frameNo = 0L
    @Volatile private var lastHandT = 0L

    private var src: Bitmap? = null
    private var dst: Bitmap? = null
    private var dstCanvas: Canvas? = null
    private val matrix = Matrix()
    private val paint = Paint()

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
        camCount++
        if (fpsT0 == 0L) fpsT0 = now
        if (now - fpsT0 >= 1000) {
            HandState.cameraFps = camCount * 1000f / (now - fpsT0)
            HandState.procFps = procCount * 1000f / (now - fpsT0)
            camCount = 0; procCount = 0; fpsT0 = now
        }
        val idle = Settings.idleSaver && now - lastHandT > 1500
        HandState.idle = idle
        frameNo++
        if (closed || inFlight || (idle && frameNo % 3L != 0L)) { proxy.close(); return }

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

        val tw = if (rot % 180 == 0) w else h
        val th = if (rot % 180 == 0) h else w
        var d = dst
        if (d == null || d.width != tw || d.height != th) {
            d = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888); dst = d
            dstCanvas = Canvas(d)
            HandState.aspect = tw.toFloat() / th
        }
        // 회전 + 좌우 반전(셀카 방향): 사용자 오른쪽 = 화면 오른쪽
        matrix.reset()
        matrix.postTranslate(-w / 2f, -h / 2f)
        matrix.postRotate(rot.toFloat())
        matrix.postScale(-1f, 1f)
        matrix.postTranslate(tw / 2f, th / 2f)
        dstCanvas!!.drawBitmap(src!!, matrix, paint)

        var ts = now
        if (ts <= lastTs) ts = lastTs + 1
        lastTs = ts
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
        HandState.frame = f
        onHand(f)
    }

    fun close() {
        closed = true
        try { landmarker.close() } catch (_: Exception) {}
    }

    companion object { private const val TAG = "HandTracker" }
}
