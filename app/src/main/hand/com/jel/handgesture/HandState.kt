package com.jel.handgesture

import kotlinx.coroutines.flow.MutableStateFlow

/** 서비스·화면이 공유하는 실행 상태 */
object HandState {
    val running = MutableStateFlow(false)
    val pointerMode = MutableStateFlow(false)
    @Volatile var paused = false
    @Volatile var frame: HandFrame? = null
    /** 여러 프레임 연속으로 확인된 손 모양 */
    @Volatile var stablePose = Pose.NONE
    @Volatile var wheel = false
    @Volatile var lastEvent = ""
    @Volatile var cameraFps = 0f
    @Volatile var procFps = 0f
    @Volatile var latencyMs = 0f
    @Volatile var gpu = false
    @Volatile var fpsRange = "-"
    @Volatile var idle = true
    /** 입력 영상 가로/세로 비 */
    @Volatile var aspect = 0.75f
    @Volatile var screenW = 1080
    @Volatile var screenH = 2520
    @Volatile var density = 3f
}
