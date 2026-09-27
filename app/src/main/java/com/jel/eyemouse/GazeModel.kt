package com.jel.eyemouse

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 시선 특징 → 화면 좌표(0~1 비율) 2차 다항 회귀.
 * 입력: gazeX, gazeY, headX, headY (표준화 후 사용)
 */
class GazeModel(
    private val mean: DoubleArray,
    private val std: DoubleArray,
    private val cx: DoubleArray,
    private val cy: DoubleArray,
) {
    fun predict(f: FaceFeatures): Pair<Float, Float> {
        val p = phi(norm(raw(f)))
        return dot(cx, p).toFloat() to dot(cy, p).toFloat()
    }

    private fun norm(r: DoubleArray) = DoubleArray(r.size) { (r[it] - mean[it]) / std[it] }

    fun toJson(): String = JSONObject()
        .put("mean", JSONArray(mean.toList()))
        .put("std", JSONArray(std.toList()))
        .put("cx", JSONArray(cx.toList()))
        .put("cy", JSONArray(cy.toList()))
        .toString()

    companion object {
        private val STD_FLOOR = doubleArrayOf(0.01, 0.01, 0.02, 0.02)
        private const val LAMBDA = 0.01

        private fun raw(f: FaceFeatures) = doubleArrayOf(
            f.gazeX.toDouble(), f.gazeY.toDouble(), f.headX.toDouble(), f.headY.toDouble()
        )

        private fun phi(z: DoubleArray) = doubleArrayOf(
            1.0, z[0], z[1], z[0] * z[1], z[0] * z[0], z[1] * z[1], z[2], z[3]
        )

        private fun dot(a: DoubleArray, b: DoubleArray): Double {
            var s = 0.0
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        /** samples: (특징, 목표 좌표 비율 x,y) */
        fun fit(samples: List<Pair<FaceFeatures, Pair<Float, Float>>>): GazeModel? {
            if (samples.size < 30) return null
            val raws = samples.map { raw(it.first) }
            val n = raws.size.toDouble()
            val mean = DoubleArray(4) { j -> raws.sumOf { it[j] } / n }
            val std = DoubleArray(4) { j ->
                max(sqrt(raws.sumOf { (it[j] - mean[j]) * (it[j] - mean[j]) } / n), STD_FLOOR[j])
            }
            val tmp = GazeModel(mean, std, DoubleArray(8), DoubleArray(8))
            val rows = raws.map { phi(tmp.norm(it)) }
            val cx = solve(rows, samples.map { it.second.first.toDouble() }) ?: return null
            val cy = solve(rows, samples.map { it.second.second.toDouble() }) ?: return null
            return GazeModel(mean, std, cx, cy)
        }

        /** 릿지 정규화 최소제곱 (정규방정식 + 가우스 소거) */
        private fun solve(rows: List<DoubleArray>, t: List<Double>): DoubleArray? {
            val p = rows[0].size
            val a = Array(p) { DoubleArray(p + 1) }
            for (k in rows.indices) {
                val r = rows[k]; val y = t[k]
                for (i in 0 until p) {
                    for (j in 0 until p) a[i][j] += r[i] * r[j]
                    a[i][p] += r[i] * y
                }
            }
            for (i in 1 until p) a[i][i] += LAMBDA * rows.size
            for (c in 0 until p) {
                var piv = c
                for (r in c + 1 until p) if (abs(a[r][c]) > abs(a[piv][c])) piv = r
                if (abs(a[piv][c]) < 1e-12) return null
                val sw = a[c]; a[c] = a[piv]; a[piv] = sw
                for (r in 0 until p) {
                    if (r == c) continue
                    val f = a[r][c] / a[c][c]
                    for (k in c..p) a[r][k] -= f * a[c][k]
                }
            }
            return DoubleArray(p) { a[it][p] / a[it][it] }
        }

        fun fromJson(s: String?): GazeModel? = try {
            if (s == null) null else {
                val o = JSONObject(s)
                fun arr(k: String) = o.getJSONArray(k).let { ja -> DoubleArray(ja.length()) { ja.getDouble(it) } }
                GazeModel(arr("mean"), arr("std"), arr("cx"), arr("cy"))
            }
        } catch (e: Exception) {
            null
        }
    }
}
