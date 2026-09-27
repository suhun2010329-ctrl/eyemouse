package com.jel.eyemouse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/** CameraX 프레임 → MediaPipe Face Landmarker → FaceFeatures */
class FaceTracker(
    context: Context,
    private val onFace: (FaceFeatures) -> Unit,
    private val onNoFace: (Long) -> Unit,
) {
    private val landmarker: FaceLandmarker
    private var lastTs = 0L
    @Volatile private var closed = false

    init {
        val base = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .setDelegate(Delegate.CPU)
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputFaceBlendshapes(true)
            .setResultListener { result: FaceLandmarkerResult, image: MPImage -> onResult(result, image) }
            .setErrorListener { e: RuntimeException -> Log.e(TAG, "landmarker error", e) }
            .build()
        landmarker = FaceLandmarker.createFromOptions(context, options)
    }

    /** 분석 스레드에서 호출 */
    fun analyze(proxy: ImageProxy) {
        if (closed) { proxy.close(); return }
        val rotation = proxy.imageInfo.rotationDegrees
        val src = try { proxy.toBitmap() } finally { proxy.close() }
        // 회전 보정 + 좌우 반전(셀카 거울 방향 → 머리를 오른쪽으로 돌리면 x 증가)
        val m = Matrix().apply {
            postRotate(rotation.toFloat())
            postScale(-1f, 1f)
        }
        val bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, false)
        var ts = SystemClock.uptimeMillis()
        if (ts <= lastTs) ts = lastTs + 1
        lastTs = ts
        try {
            landmarker.detectAsync(BitmapImageBuilder(bmp).build(), ts)
        } catch (e: Exception) {
            Log.w(TAG, "detectAsync failed", e)
        }
    }

    private fun onResult(r: FaceLandmarkerResult, img: MPImage) {
        val now = SystemClock.uptimeMillis()
        val faces = r.faceLandmarks()
        if (faces.isEmpty()) { onNoFace(now); return }
        val blend = r.faceBlendshapes().orElse(null)?.firstOrNull()
        val f = FeatureExtractor.extract(faces[0], blend, img.width, img.height, now)
        if (f == null) onNoFace(now) else onFace(f)
    }

    fun close() {
        closed = true
        landmarker.close()
    }

    companion object { private const val TAG = "FaceTracker" }
}
