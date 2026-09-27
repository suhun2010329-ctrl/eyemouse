package com.jel.eyemouse

import android.graphics.PointF
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** 학습 샘플: 특징 벡터 + 그때 보고 있던 화면 위치(mm, 좌상단 기준, 세로 화면) */
class GazeSample(
    val e: FloatArray,
    val x: Float,
    val y: Float,
    val w: Float,
    val group: Int,
    val kind: Int,
)

class FitResult(val model: GazeModel, val cvErrMm: Double)

/**
 * 시선 특징 → 화면 좌표(mm) 회귀 모델.
 * 눈 특징(홍채·눈꺼풀 6개)은 2차 다항식, 머리 자세 6개는 선형 + 시선×머리 교차항.
 * 릿지 정규화 강도는 '보던 점' 단위 교차검증으로 자동 선택.
 */
class GazeModel(
    private val mean: DoubleArray,
    private val std: DoubleArray,
    private val cx: DoubleArray,
    private val cy: DoubleArray,
) {
    fun predictMm(e: FloatArray): PointF {
        val p = phi(norm(e))
        return PointF(dot(cx, p).toFloat(), dot(cy, p).toFloat())
    }

    private fun norm(e: FloatArray) = DoubleArray(EYE_DIM) { (e[it] - mean[it]) / std[it] }

    fun toJson(): String = JSONObject()
        .put("v", 2)
        .put("mean", JSONArray(mean.toList()))
        .put("std", JSONArray(std.toList()))
        .put("cx", JSONArray(cx.toList()))
        .put("cy", JSONArray(cy.toList()))
        .toString()

    companion object {
        const val P = 1 + 6 + 21 + 6 + 4
        private val STD_FLOOR = doubleArrayOf(
            0.004, 0.004, 0.004, 0.004, 0.006, 0.006,
            0.01, 0.01, 0.01, 0.005, 0.005, 0.002,
        )
        private val LAMBDAS = doubleArrayOf(0.3, 1.0, 3.0, 10.0, 30.0, 100.0)
        private const val FOLDS = 5

        fun phi(z: DoubleArray): DoubleArray {
            val out = DoubleArray(P)
            var k = 0
            out[k++] = 1.0
            for (i in 0 until 6) out[k++] = z[i]
            for (i in 0 until 6) for (j in i until 6) out[k++] = z[i] * z[j]
            for (i in 6 until 12) out[k++] = z[i]
            val gx = (z[0] + z[2]) / 2
            val gy = (z[1] + z[3]) / 2
            out[k++] = gx * z[6]
            out[k++] = gy * z[7]
            out[k++] = gx * z[11]
            out[k] = gy * z[11]
            return out
        }

        private fun dot(a: DoubleArray, b: DoubleArray): Double {
            var s = 0.0
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        /** 가중 정규방정식 누적: [A | bx | by] (P × (P+2)) */
        private fun accumulate(rows: List<DoubleArray>, s: List<GazeSample>, idx: List<Int>): Array<DoubleArray> {
            val a = Array(P) { DoubleArray(P + 2) }
            for (n in idx) {
                val r = rows[n]; val w = s[n].w.toDouble()
                for (i in 0 until P) {
                    val wi = w * r[i]
                    if (wi == 0.0) continue
                    val ai = a[i]
                    for (j in i until P) ai[j] += wi * r[j]
                    ai[P] += wi * s[n].x
                    ai[P + 1] += wi * s[n].y
                }
            }
            for (i in 0 until P) for (j in 0 until i) a[i][j] = a[j][i]
            return a
        }

        private fun solve(base: Array<DoubleArray>, lambda: Double): Pair<DoubleArray, DoubleArray>? {
            val a = Array(P) { base[it].copyOf() }
            for (i in 1 until P) a[i][i] += lambda
            for (c in 0 until P) {
                var piv = c
                for (r in c + 1 until P) if (abs(a[r][c]) > abs(a[piv][c])) piv = r
                if (abs(a[piv][c]) < 1e-12) return null
                val sw = a[c]; a[c] = a[piv]; a[piv] = sw
                for (r in 0 until P) {
                    if (r == c) continue
                    val f = a[r][c] / a[c][c]
                    if (f == 0.0) continue
                    for (k in c..P + 1) a[r][k] -= f * a[c][k]
                }
            }
            return DoubleArray(P) { a[it][P] / a[it][it] } to DoubleArray(P) { a[it][P + 1] / a[it][it] }
        }

        private fun sub(x: Array<DoubleArray>, y: Array<DoubleArray>) =
            Array(P) { i -> DoubleArray(P + 2) { j -> x[i][j] - y[i][j] } }

        fun fit(samples: List<GazeSample>): FitResult? {
            if (samples.size < 60) return null
            val n = samples.size
            val mean = DoubleArray(EYE_DIM) { j -> samples.sumOf { it.e[j].toDouble() } / n }
            val std = DoubleArray(EYE_DIM) { j ->
                max(sqrt(samples.sumOf { (it.e[j] - mean[j]).let { d -> d * d } } / n), STD_FLOOR[j])
            }
            val tmp = GazeModel(mean, std, DoubleArray(P), DoubleArray(P))
            val rows = samples.map { phi(tmp.norm(it.e)) }
            val sumW = samples.sumOf { it.w.toDouble() }
            val scale = sumW / 1000.0

            // 보던 점(group) 단위로 fold 나누기 → 새 위치에 대한 일반화 오차
            val groups = samples.map { it.group }.distinct()
            val foldOf = HashMap<Int, Int>()
            groups.shuffled(java.util.Random(7)).forEachIndexed { i, g -> foldOf[g] = i % FOLDS }
            val all = accumulate(rows, samples, samples.indices.toList())

            var bestLambda = 3.0
            var bestErr = Double.MAX_VALUE
            if (groups.size >= FOLDS * 2) {
                val foldIdx = (0 until FOLDS).map { f -> samples.indices.filter { foldOf[samples[it].group] == f } }
                val foldA = foldIdx.map { accumulate(rows, samples, it) }
                for (lam in LAMBDAS) {
                    var errSum = 0.0; var wSum = 0.0
                    for (f in 0 until FOLDS) {
                        val (bx, by) = solve(sub(all, foldA[f]), lam * scale) ?: continue
                        for (i in foldIdx[f]) {
                            val px = dot(bx, rows[i]); val py = dot(by, rows[i])
                            val w = samples[i].w
                            errSum += w * hypot(px - samples[i].x, py - samples[i].y); wSum += w
                        }
                    }
                    if (wSum > 0) {
                        val err = errSum / wSum
                        if (err < bestErr) { bestErr = err; bestLambda = lam }
                    }
                }
            }
            val (bx, by) = solve(all, bestLambda * scale) ?: return null
            return FitResult(GazeModel(mean, std, bx, by), if (bestErr == Double.MAX_VALUE) Double.NaN else bestErr)
        }

        fun fromJson(s: String?): GazeModel? = try {
            if (s == null) null else {
                val o = JSONObject(s)
                if (o.optInt("v") != 2) null else {
                    fun arr(k: String) = o.getJSONArray(k).let { ja -> DoubleArray(ja.length()) { ja.getDouble(it) } }
                    val m = GazeModel(arr("mean"), arr("std"), arr("cx"), arr("cy"))
                    if (m.cx.size == P && m.mean.size == EYE_DIM) m else null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
