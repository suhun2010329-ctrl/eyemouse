package com.jel.eyemouse

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 폰 자세와 움직임.
 * pitchDeg: 화면이 바닥에서 세워진 각도 (0 = 바닥에 눕힘, 90 = 똑바로 세움)
 * gyroDps: 회전 속도(°/s) — 폰이 흔들리거나 이동하는지 판단
 */
object PoseSensor : SensorEventListener {
    @Volatile var pitchDeg = 70f
        private set
    @Volatile var rollDeg = 0f
        private set
    @Volatile var gyroDps = 0f
        private set

    private var sm: SensorManager? = null
    private var gx = 0f
    private var gy = 9.8f
    private var gz = 3f

    fun start(ctx: Context) {
        if (sm != null) return
        val m = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm = m
        val grav = m.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = m.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        grav?.let { m.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyro?.let { m.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sm?.unregisterListener(this)
        sm = null
        gyroDps = 0f
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                val k = if (e.sensor.type == Sensor.TYPE_GRAVITY) 1f else 0.1f
                gx += k * (e.values[0] - gx)
                gy += k * (e.values[1] - gy)
                gz += k * (e.values[2] - gz)
                pitchDeg = Math.toDegrees(atan2(gy.toDouble(), gz.toDouble())).toFloat()
                rollDeg = Math.toDegrees(atan2(gx.toDouble(), sqrt((gy * gy + gz * gz).toDouble()))).toFloat()
            }
            Sensor.TYPE_GYROSCOPE -> {
                val w = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                val dps = Math.toDegrees(w.toDouble()).toFloat()
                gyroDps += 0.3f * (dps - gyroDps)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
