package com.jel.eyemouse

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max

/** 1€ 필터: 천천히 움직일 땐 떨림을 강하게 잡고, 빠르게 움직일 땐 지연을 줄인다. */
class OneEuroFilter(
    var minCutoff: Double = 1.0,
    var beta: Double = 0.004,
    private val dCutoff: Double = 1.0,
) {
    private var inited = false
    private var xPrev = 0.0
    private var dxPrev = 0.0
    private var tPrev = 0L

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }

    fun filter(x: Double, tMs: Long): Double {
        if (!inited) {
            inited = true; xPrev = x; dxPrev = 0.0; tPrev = tMs
            return x
        }
        val dt = max((tMs - tPrev) / 1000.0, 1e-3)
        tPrev = tMs
        val dx = (x - xPrev) / dt
        val aD = alpha(dCutoff, dt)
        val edx = aD * dx + (1 - aD) * dxPrev
        dxPrev = edx
        val a = alpha(minCutoff + beta * abs(edx), dt)
        val r = a * x + (1 - a) * xPrev
        xPrev = r
        return r
    }

    fun reset() { inited = false }
}
