package com.jel.handgesture

import kotlinx.coroutines.flow.MutableStateFlow

/** 서비스·화면이 공유하는 실행 상태 */
object HandState {
    val running = MutableStateFlow(false)
    @Volatile var paused = false
    @Volatile var frame: HandFrame? = null
    @Volatile var lastEvent = ""
    @Volatile var cameraFps = 0f
    @Volatile var procFps = 0f
    @Volatile var latencyMs = 0f
    @Volatile var gpu = false
    @Volatile var fpsRange = "-"
    @Volatile var idle = true
    /** 입력 영상 가로/세로 비 */
    @Volatile var aspect = 0.75f
}
