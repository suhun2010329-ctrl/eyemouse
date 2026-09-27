package com.jel.eyemouse

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * 시선 학습 데이터 저장소 + 학습기.
 * - 캘리브레이션 샘플과 사용 중 수집한 샘플(버튼을 눌렀을 때 실제로 보던 위치)을 모아
 *   모델을 다시 학습한다.
 */
object GazeTrainer {
    const val KIND_GRID = 0
    const val KIND_PURSUIT = 1
    const val KIND_VALID = 2
    const val KIND_TRAIN = 3
    const val KIND_IMPLICIT = 4

    private const val TAG = "GazeTrainer"
    private const val FILE_VERSION = 2
    private const val MAX_TOTAL = 6000
    private const val MAX_IMPLICIT = 800
    private const val REFIT_EVERY_IMPLICIT = 6

    private val lock = Any()
    private val samples = ArrayList<GazeSample>()
    private val exec = Executors.newSingleThreadExecutor()
    private var file: File? = null
    private var nextGroup = 1000
    private var pendingImplicit = 0

    @Volatile var model: GazeModel? = null
        private set

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "gaze_samples.bin")
        model = GazeModel.fromJson(Settings.gazeModel)
        exec.execute { load() }
    }

    fun count(): Int = synchronized(lock) { samples.size }
    fun countImplicit(): Int = synchronized(lock) { samples.count { it.kind == KIND_IMPLICIT } }
    fun newGroup(): Int = synchronized(lock) { nextGroup++ }

    fun clear() {
        synchronized(lock) { samples.clear(); pendingImplicit = 0 }
        model = null
        Settings.gazeModel = null
        Settings.accuracyMm = Float.NaN
        exec.execute { save() }
        TrackerService.controller?.reloadModel()
    }

    /** 전체 캘리브레이션: 기존 데이터를 새 데이터로 교체 */
    fun replaceAll(list: List<GazeSample>) {
        synchronized(lock) {
            samples.clear(); samples.addAll(list)
            nextGroup = maxOf(nextGroup, (list.maxOfOrNull { it.group } ?: 0) + 1)
        }
    }

    fun addAll(list: List<GazeSample>) {
        synchronized(lock) { samples.addAll(list); trim() }
    }

    /** 사용 중 자동 학습: 스냅된 버튼 중심을 '실제로 보던 위치'로 간주 */
    fun addImplicit(e: FloatArray, xMm: Float, yMm: Float) {
        val refit: Boolean
        synchronized(lock) {
            val g = nextGroup++
            samples.add(GazeSample(e, xMm, yMm, 0.8f, g, KIND_IMPLICIT))
            trim()
            pendingImplicit++
            refit = pendingImplicit >= REFIT_EVERY_IMPLICIT
            if (refit) pendingImplicit = 0
        }
        if (refit && model != null) refitAsync()
    }

    fun refitAsync(onDone: ((FitResult?) -> Unit)? = null) {
        exec.execute { onDone?.invoke(refitNow()) }
    }

    /** 현재 스레드에서 학습 (캘리브레이션 화면은 백그라운드 코루틴에서 호출) */
    fun refitNow(): FitResult? {
        val snap = synchronized(lock) { ArrayList(samples) }
        val r = try { GazeModel.fit(snap) } catch (e: Exception) { Log.e(TAG, "fit failed", e); null }
        if (r != null) {
            model = r.model
            Settings.gazeModel = r.model.toJson()
            if (!r.cvErrMm.isNaN()) Settings.cvErrorMm = r.cvErrMm.toFloat()
            TrackerService.controller?.reloadModel()
        }
        save()
        return r
    }

    private fun trim() {
        var implicit = samples.count { it.kind == KIND_IMPLICIT }
        val it = samples.iterator()
        while (implicit > MAX_IMPLICIT && it.hasNext()) {
            if (it.next().kind == KIND_IMPLICIT) { it.remove(); implicit-- }
        }
        while (samples.size > MAX_TOTAL) samples.removeAt(0)
    }

    private fun save() {
        val f = file ?: return
        val snap = synchronized(lock) { ArrayList(samples) }
        try {
            val tmp = File(f.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(FILE_VERSION)
                out.writeInt(snap.size)
                for (s in snap) {
                    out.writeInt(s.group); out.writeInt(s.kind); out.writeFloat(s.w)
                    out.writeFloat(s.x); out.writeFloat(s.y)
                    for (v in s.e) out.writeFloat(v)
                }
            }
            tmp.renameTo(f)
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
        }
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            DataInputStream(f.inputStream().buffered()).use { inp ->
                if (inp.readInt() != FILE_VERSION) return
                val n = inp.readInt()
                val list = ArrayList<GazeSample>(n)
                repeat(n) {
                    val g = inp.readInt(); val k = inp.readInt(); val w = inp.readFloat()
                    val x = inp.readFloat(); val y = inp.readFloat()
                    val e = FloatArray(EYE_DIM) { inp.readFloat() }
                    list.add(GazeSample(e, x, y, w, g, k))
                }
                synchronized(lock) {
                    if (samples.isEmpty()) samples.addAll(list)
                    nextGroup = maxOf(nextGroup, (list.maxOfOrNull { it.group } ?: 0) + 1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "load failed", e)
        }
    }
}
