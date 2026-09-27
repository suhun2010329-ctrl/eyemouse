package com.jel.eyemouse

import android.graphics.Point
import android.os.Build
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow

/** 서비스·액티비티가 공유하는 실행 상태 (같은 프로세스). */
object EyeMouseState {
    val running = MutableStateFlow(false)
    @Volatile var calibrating = false
    @Volatile var paused = false
    @Volatile var latest: FaceFeatures? = null
    @Volatile var screenW = 1080
    @Volatile var screenH = 2520
    @Volatile var ppmX = 15.6f   // px per mm (Flip7 기본값)
    @Volatile var ppmY = 15.6f
}

fun realScreenSize(wm: WindowManager): Point =
    if (Build.VERSION.SDK_INT >= 30) {
        wm.maximumWindowMetrics.bounds.let { Point(it.width(), it.height()) }
    } else {
        Point().also {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(it)
        }
    }
