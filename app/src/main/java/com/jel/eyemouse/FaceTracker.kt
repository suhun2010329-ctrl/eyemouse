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

/**
 * CameraX 프레임(1280×960) → 절반 크기로 얼굴 랜드마크 검출,
 * 원본 해상도는 눈 크롭 정밀 분석에 사용 → FaceFeatures
 */
class FaceTracker(
    context: Context,
    private val onFace: (FaceFeatures) -> Unit,
    private val onNoFace: (Long) -> Unit,
) {
    private val landmarker: FaceLandmarker
    private var lastTs = 0L
    @Volatile private var closed = false
    private val frames = LinkedHashMap<Long, Bitmap>()

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
        // 회전 보정 + 좌우 반전(셀카 방향)
        val m = Matrix().apply {
            postRotate(rotation.toFloat())
            postScale(-1f, 1f)
        }
        val full = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, false)
        val small = Bitmap.createScaledBitmap(full, full.width / 2, full.height / 2, true)
        var ts = SystemClock.uptimeMillis()
        if (ts <= lastTs) ts = lastTs + 1
        lastTs = ts
        synchronized(frames) {
            frames[ts] = full
            while (frames.size > 4) frames.remove(frames.keys.first())
        }
        try {
            landmarker.detectAsync(BitmapImageBuilder(small).build(), ts)
        } catch (e: Exception) {
            Log.w(TAG, "detectAsync failed", e)
        }
    }

    private fun onResult(r: FaceLandmarkerResult, img: MPImage) {
        val now = SystemClock.uptimeMillis()
        val full = synchronized(frames) {
            val b = frames.remove(r.timestampMs())
            val it = frames.keys.iterator()
            while (it.hasNext()) { if (it.next() < r.timestampMs()) it.remove() }
            b
        }
        val faces = r.faceLandmarks()
        if (faces.isEmpty() || full == null) { onNoFace(now); return }
        val blend = r.faceBlendshapes().orElse(null)?.firstOrNull()
        val f = try {
            FeatureExtractor.extract(faces[0], blend, full, now)
        } catch (e: Exception) {
            Log.w(TAG, "extract failed", e); null
        }
        if (f == null) onNoFace(now) else onFace(f)
    }

    fun close() {
        closed = true
        landmarker.close()
        synchronized(frames) { frames.clear() }
    }

    companion object { private const val TAG = "FaceTracker" }
}
