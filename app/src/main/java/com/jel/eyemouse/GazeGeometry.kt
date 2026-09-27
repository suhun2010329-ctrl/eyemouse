package com.jel.eyemouse

import android.graphics.PointF
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 물리 기반 시선 특징.
 * 시선 광선이 화면(≈카메라 평면)과 만나는 점은 대략
 *   화면 좌표 ≈ 눈 위치 + 거리 × tan(시선 각도)
 * 이므로, 거리(Z)를 곱한 각도 항을 선형으로 두면 거리·자세 변화가 식 안에서 자동 반영된다.
 */
object Physics {
    const val P = 8

    fun rowX(v: FloatArray): DoubleArray {
        val z = v[G.Z].toDouble(); val gx = v[G.GX].toDouble(); val gy = v[G.GY].toDouble()
        return doubleArrayOf(
            1.0, v[G.EX].toDouble(), z * gx, z * v[G.HX], z,
            z * gx * gx * gx, z * gx * gy, z * v[G.ROLL],
        )
    }

    fun rowY(v: FloatArray): DoubleArray {
        val z = v[G.Z].toDouble(); val gx = v[G.GX].toDouble(); val gy = v[G.GY].toDouble()
        return doubleArrayOf(
            1.0, v[G.EY].toDouble(), z * gy, z * v[G.HY], z,
            z * gy * gy * gy, z * v[G.LID], z * gx * gy,
        )
    }
}

/** 표준화 + 계수 (한 축) */
class LinearAxis(val mean: DoubleArray, val std: DoubleArray, val coef: DoubleArray) {
    fun predict(raw: DoubleArray): Double {
        var s = coef[0]
        for (i in 1 until raw.size) s += coef[i] * (raw[i] - mean[i]) / std[i]
        return s
    }

    fun toJson(): JSONObject = JSONObject()
        .put("mean", JSONArray(mean.toList())).put("std", JSONArray(std.toList())).put("coef", JSONArray(coef.toList()))

    companion object {
        fun fromJson(o: JSONObject): LinearAxis {
            fun arr(k: String) = o.getJSONArray(k).let { ja -> DoubleArray(ja.length()) { ja.getDouble(it) } }
            return LinearAxis(arr("mean"), arr("std"), arr("coef"))
        }

        /** 가중 릿지 (절편 제외 정규화) */
        fun fit(rows: List<DoubleArray>, t: DoubleArray, w: DoubleArray, idx: IntArray, lambda: Double,
                mean: DoubleArray, std: DoubleArray): LinearAxis? {
            val p = rows[0].size
            val a = Array(p) { DoubleArray(p + 1) }
            val z = DoubleArray(p)
            for (n in idx) {
                val r = rows[n]
                z[0] = 1.0
                for (i in 1 until p) z[i] = (r[i] - mean[i]) / std[i]
                val wn = w[n]
                for (i in 0 until p) {
                    val wi = wn * z[i]
                    for (j in 0 until p) a[i][j] += wi * z[j]
                    a[i][p] += wi * t[n]
                }
            }
            for (i in 1 until p) a[i][i] += lambda
            for (c in 0 until p) {
                var piv = c
                for (r in c + 1 until p) if (abs(a[r][c]) > abs(a[piv][c])) piv = r
                if (abs(a[piv][c]) < 1e-12) return null
                val sw = a[c]; a[c] = a[piv]; a[piv] = sw
                for (r in 0 until p) {
                    if (r == c) continue
                    val f = a[r][c] / a[c][c]
                    if (f == 0.0) continue
                    for (k in c..p) a[r][k] -= f * a[c][k]
                }
            }
            return LinearAxis(mean, std, DoubleArray(p) { a[it][p] / a[it][it] })
        }

        fun stats(rows: List<DoubleArray>): Pair<DoubleArray, DoubleArray> {
            val p = rows[0].size
            val n = rows.size.toDouble()
            val mean = DoubleArray(p) { j -> if (j == 0) 0.0 else rows.sumOf { it[j] } / n }
            val std = DoubleArray(p) { j ->
                if (j == 0) 1.0 else max(sqrt(rows.sumOf { (it[j] - mean[j]) * (it[j] - mean[j]) } / n), 1e-6)
            }
            return mean to std
        }
    }
}

class FitResult3(val model: GazeModelV3, val cvErrMm: Double, val cvPhysMm: Double)

/**
 * v3 시선 모델 = 물리 모델(거리·자세 반영) + 잔차 보정(눈 모양 다항 회귀, 도움이 될 때만)
 * env: 학습 데이터의 거리·머리 자세 범위 (5~95%) — 벗어나면 품질 점수에 반영
 */
class GazeModelV3(
    private val ax: LinearAxis,
    private val ay: LinearAxis,
    private val res: GazeModel?,
    val env: FloatArray,
) {
    val hasResidual get() = res != null

    fun predictMm(v: FloatArray): PointF {
        var x = ax.predict(Physics.rowX(v))
        var y = ay.predict(Physics.rowY(v))
        res?.let { val r = it.predictMm(v); x += r.x; y += r.y }
        return PointF(x.toFloat(), y.toFloat())
    }

    /** 학습 범위 안 = 1, 범위 폭의 30%만큼 벗어나면 0 */
    fun envelopeScore(v: FloatArray): Float {
        var s = 1f
        val keys = intArrayOf(G.Z, G.HX, G.HY)
        for (k in keys.indices) {
            val lo = env[k * 2]; val hi = env[k * 2 + 1]
            val span = max(hi - lo, if (keys[k] == G.Z) 40f else 0.05f)
            val x = v[keys[k]]
            val out = when { x < lo -> lo - x; x > hi -> x - hi; else -> 0f }
            s *= (1f - out / (0.3f * span)).coerceIn(0f, 1f)
        }
        return s
    }

    fun toJson(): String = JSONObject()
        .put("v", 3)
        .put("ax", ax.toJson()).put("ay", ay.toJson())
        .put("res", res?.toJson())
        .put("env", JSONArray(env.map { it.toDouble() }))
        .toString()

    companion object {
        private val LAMBDAS = doubleArrayOf(0.01, 0.1, 1.0, 10.0, 100.0)
        private const val FOLDS = 5

        fun fromJson(s: String?): GazeModelV3? = try {
            if (s == null) null else {
                val o = JSONObject(s)
                if (o.optInt("v") != 3) null else {
                    val res = if (o.isNull("res")) null else GazeModel.fromJson(o.getString("res"))
                    val ea = o.getJSONArray("env")
                    GazeModelV3(
                        LinearAxis.fromJson(o.getJSONObject("ax")), LinearAxis.fromJson(o.getJSONObject("ay")),
                        res, FloatArray(ea.length()) { ea.getDouble(it).toFloat() },
                    )
                }
            }
        } catch (e: Exception) {
            null
        }

        private fun pct(vals: List<Float>, q: Float): Float {
            val s = vals.sorted()
            return s[((s.size - 1) * q).toInt().coerceIn(0, s.size - 1)]
        }

        fun fit(samples: List<GazeSample>): FitResult3? {
            if (samples.size < 60) return null
            val n = samples.size
            val rx = samples.map { Physics.rowX(it.e) }
            val ry = samples.map { Physics.rowY(it.e) }
            val tx = DoubleArray(n) { samples[it].x.toDouble() }
            val ty = DoubleArray(n) { samples[it].y.toDouble() }
            val w = DoubleArray(n) { samples[it].w.toDouble() }
            val (mx, sx) = LinearAxis.stats(rx)
            val (my, sy) = LinearAxis.stats(ry)
            val scale = w.sum() / 1000.0
            val all = IntArray(n) { it }

            val groups = samples.map { it.group }.distinct()
            val foldOf = HashMap<Int, Int>()
            groups.shuffled(java.util.Random(11)).forEachIndexed { i, g -> foldOf[g] = i % FOLDS }
            val canCv = groups.size >= FOLDS * 2
            val test = (0 until FOLDS).map { f -> (0 until n).filter { foldOf[samples[it].group] == f }.toIntArray() }
            val train = (0 until FOLDS).map { f -> (0 until n).filter { foldOf[samples[it].group] != f }.toIntArray() }

            fun wErr(px: DoubleArray, py: DoubleArray, idx: IntArray): Pair<Double, Double> {
                var e = 0.0; var ws = 0.0
                for (i in idx) { e += w[i] * hypot(px[i] - tx[i], py[i] - ty[i]); ws += w[i] }
                return e to ws
            }

            // 1) 물리 모델: 릿지 강도 선택
            var bestLam = 1.0
            var bestErr = Double.MAX_VALUE
            if (canCv) {
                for (lam in LAMBDAS) {
                    val px = DoubleArray(n); val py = DoubleArray(n)
                    var ok = true
                    for (f in 0 until FOLDS) {
                        val fx = LinearAxis.fit(rx, tx, w, train[f], lam * scale, mx, sx)
                        val fy = LinearAxis.fit(ry, ty, w, train[f], lam * scale, my, sy)
                        if (fx == null || fy == null) { ok = false; break }
                        for (i in test[f]) { px[i] = fx.predict(rx[i]); py[i] = fy.predict(ry[i]) }
                    }
                    if (!ok) continue
                    val (e, ws) = wErr(px, py, all)
                    if (ws > 0 && e / ws < bestErr) { bestErr = e / ws; bestLam = lam }
                }
            }
            val physX = LinearAxis.fit(rx, tx, w, all, bestLam * scale, mx, sx) ?: return null
            val physY = LinearAxis.fit(ry, ty, w, all, bestLam * scale, my, sy) ?: return null
            val cvPhys = if (bestErr == Double.MAX_VALUE) Double.NaN else bestErr

            // 2) 잔차 보정이 실제로 도움이 되는지 교차검증으로 확인
            var cvFull = cvPhys
            if (canCv) {
                val px = DoubleArray(n); val py = DoubleArray(n)
                var ok = true
                for (f in 0 until FOLDS) {
                    val fx = LinearAxis.fit(rx, tx, w, train[f], bestLam * scale, mx, sx)
                    val fy = LinearAxis.fit(ry, ty, w, train[f], bestLam * scale, my, sy)
                    if (fx == null || fy == null) { ok = false; break }
                    val resTrain = train[f].map { i ->
                        val s = samples[i]
                        GazeSample(s.e, (tx[i] - fx.predict(rx[i])).toFloat(), (ty[i] - fy.predict(ry[i])).toFloat(), s.w, s.group, s.kind)
                    }
                    val rm = GazeModel.fit(resTrain)?.model
                    for (i in test[f]) {
                        val r = rm?.predictMm(samples[i].e)
                        px[i] = fx.predict(rx[i]) + (r?.x ?: 0f)
                        py[i] = fy.predict(ry[i]) + (r?.y ?: 0f)
                    }
                }
                if (ok) { val (e, ws) = wErr(px, py, all); if (ws > 0) cvFull = e / ws }
            }
            val useRes = !cvPhys.isNaN() && !cvFull.isNaN() && cvFull < cvPhys * 0.97
            val res = if (useRes) {
                GazeModel.fit(samples.mapIndexed { i, s ->
                    GazeSample(s.e, (tx[i] - physX.predict(rx[i])).toFloat(), (ty[i] - physY.predict(ry[i])).toFloat(), s.w, s.group, s.kind)
                })?.model
            } else null

            val env = floatArrayOf(
                pct(samples.map { it.e[G.Z] }, 0.05f), pct(samples.map { it.e[G.Z] }, 0.95f),
                pct(samples.map { it.e[G.HX] }, 0.05f), pct(samples.map { it.e[G.HX] }, 0.95f),
                pct(samples.map { it.e[G.HY] }, 0.05f), pct(samples.map { it.e[G.HY] }, 0.95f),
            )
            return FitResult3(GazeModelV3(physX, physY, res, env), if (useRes) cvFull else cvPhys, cvPhys)
        }
    }
}
