package com.jel.eyemouse

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * 시선 학습 데이터 저장소 + 학습기 (v3).
 * - 캘리브레이션 샘플과 사용 중 수집한 샘플을 모아 물리+잔차 모델을 학습한다.
 * - 샘플은 거리·폰 기울기 조건이 함께 저장되어 '학습 지도'를 만들 수 있다.
 * - 오래된 샘플은 가중치가 서서히 낮아진다(반감기 14일).
 */
object GazeTrainer {
    const val KIND_GRID = 0
    const val KIND_PURSUIT = 1
    const val KIND_VALID = 2
    const val KIND_TRAIN = 3
    const val KIND_IMPLICIT = 4

    private const val TAG = "GazeTrainer"
    private const val FILE_VERSION = 3
    private const val MAX_TOTAL = 6000
    private const val MAX_IMPLICIT = 800
    private const val REFIT_EVERY_IMPLICIT = 6
    private const val HALF_LIFE_MS = 14L * 24 * 3600 * 1000

    private class Stored(val s: GazeSample, val time: Long)

    private val lock = Any()
    private val samples = ArrayList<Stored>()
    private val exec = Executors.newSingleThreadExecutor()
    private var file: File? = null
    private var nextGroup = 1000
    private var pendingImplicit = 0

    @Volatile var model: GazeModelV3? = null
        private set

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "gaze_samples_v3.bin")
        model = GazeModelV3.fromJson(Settings.gazeModel)
        exec.execute { load() }
    }

    fun count(): Int = synchronized(lock) { samples.size }
    fun countImplicit(): Int = synchronized(lock) { samples.count { it.s.kind == KIND_IMPLICIT } }
    fun newGroup(): Int = synchronized(lock) { nextGroup++ }

    /** 학습 지도: 거리 구간(3) × 폰 기울기 구간(3) 샘플 수, 행 = 거리 */
    fun coverage(): IntArray = synchronized(lock) {
        val c = IntArray(9)
        for (st in samples) {
            val z = st.s.e[G.Z]; val p = st.s.e[G.PITCH]
            val zi = when { z < 300f -> 0; z < 400f -> 1; else -> 2 }
            val pi = when { p < 55f -> 0; p < 75f -> 1; else -> 2 }
            c[zi * 3 + pi]++
        }
        c
    }

    fun clear() {
        synchronized(lock) { samples.clear(); pendingImplicit = 0 }
        model = null
        Settings.gazeModel = null
        Settings.accuracyMm = Float.NaN
        Settings.cvErrorMm = Float.NaN
        Settings.errPoints = null
        exec.execute { save() }
        TrackerService.controller?.reloadModel()
    }

    /** 전체 캘리브레이션: 기존 데이터를 새 데이터로 교체 */
    fun replaceAll(list: List<GazeSample>) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            samples.clear(); list.forEach { samples.add(Stored(it, now)) }
            nextGroup = maxOf(nextGroup, (list.maxOfOrNull { it.group } ?: 0) + 1)
        }
    }

    fun addAll(list: List<GazeSample>) {
        val now = System.currentTimeMillis()
        synchronized(lock) { list.forEach { samples.add(Stored(it, now)) }; trim() }
    }

    /** 사용 중 자동 학습: 스냅된 버튼 중심을 '실제로 보던 위치'로 간주 */
    fun addImplicit(v: FloatArray, xMm: Float, yMm: Float) {
        val refit: Boolean
        synchronized(lock) {
            val g = nextGroup++
            samples.add(Stored(GazeSample(v, xMm, yMm, 0.8f, g, KIND_IMPLICIT), System.currentTimeMillis()))
            trim()
            pendingImplicit++
            refit = pendingImplicit >= REFIT_EVERY_IMPLICIT
            if (refit) pendingImplicit = 0
        }
        if (refit && model != null) refitAsync()
    }

    fun refitAsync(onDone: ((FitResult3?) -> Unit)? = null) {
        exec.execute { onDone?.invoke(refitNow()) }
    }

    /** 현재 스레드에서 학습 */
    fun refitNow(): FitResult3? {
        val now = System.currentTimeMillis()
        val snap = synchronized(lock) {
            samples.map {
                val age = (now - it.time).coerceAtLeast(0L).toDouble()
                val decay = Math.pow(0.5, age / HALF_LIFE_MS).toFloat().coerceAtLeast(0.2f)
                GazeSample(it.s.e, it.s.x, it.s.y, it.s.w * decay, it.s.group, it.s.kind)
            }
        }
        val r = try { GazeModelV3.fit(snap) } catch (e: Exception) { Log.e(TAG, "fit failed", e); null }
        if (r != null) {
            model = r.model
            Settings.gazeModel = r.model.toJson()
            if (!r.cvErrMm.isNaN()) Settings.cvErrorMm = r.cvErrMm.toFloat()
            TrackerService.controller?.reloadModel()
        }
        save()
        return r
    }

    // ── 오차 지도: 검증·정밀 학습 때 잰 '점 위치(mm) + 오차(mm)' 최근 90개 ──

    fun recordError(xMm: Float, yMm: Float, errMm: Float) {
        val arr = try { JSONArray(Settings.errPoints ?: "[]") } catch (e: Exception) { JSONArray() }
        arr.put(JSONArray().put(xMm.toDouble()).put(yMm.toDouble()).put(errMm.toDouble()))
        while (arr.length() > 90) arr.remove(0)
        Settings.errPoints = arr.toString()
    }

    /** 3열 × 6행 칸별 평균 오차(mm), 데이터 없으면 NaN */
    fun errorGrid(widthMm: Float, heightMm: Float): FloatArray {
        val sum = FloatArray(18); val cnt = IntArray(18)
        try {
            val arr = JSONArray(Settings.errPoints ?: "[]")
            for (i in 0 until arr.length()) {
                val e = arr.getJSONArray(i)
                val c = (e.getDouble(0) / widthMm * 3).toInt().coerceIn(0, 2)
                val r = (e.getDouble(1) / heightMm * 6).toInt().coerceIn(0, 5)
                sum[r * 3 + c] += e.getDouble(2).toFloat(); cnt[r * 3 + c]++
            }
        } catch (_: Exception) {}
        return FloatArray(18) { if (cnt[it] == 0) Float.NaN else sum[it] / cnt[it] }
    }

    private fun trim() {
        var implicit = samples.count { it.s.kind == KIND_IMPLICIT }
        val it = samples.iterator()
        while (implicit > MAX_IMPLICIT && it.hasNext()) {
            if (it.next().s.kind == KIND_IMPLICIT) { it.remove(); implicit-- }
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
                out.writeInt(FEAT_DIM)
                out.writeInt(snap.size)
                for (st in snap) {
                    val s = st.s
                    out.writeLong(st.time)
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
                if (inp.readInt() != FEAT_DIM) return
                val n = inp.readInt()
                val list = ArrayList<Stored>(n)
                repeat(n) {
                    val time = inp.readLong()
                    val g = inp.readInt(); val k = inp.readInt(); val w = inp.readFloat()
                    val x = inp.readFloat(); val y = inp.readFloat()
                    val e = FloatArray(FEAT_DIM) { inp.readFloat() }
                    list.add(Stored(GazeSample(e, x, y, w, g, k), time))
                }
                synchronized(lock) {
                    if (samples.isEmpty()) samples.addAll(list)
                    nextGroup = maxOf(nextGroup, (list.maxOfOrNull { it.s.group } ?: 0) + 1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "load failed", e)
        }
    }
}
