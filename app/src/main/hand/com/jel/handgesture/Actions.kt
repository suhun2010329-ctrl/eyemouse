package com.jel.handgesture

/** 제스처가 실행할 동작 */
enum class GestureAction(val label: String) {
    NONE("없음"),
    SWIPE_UP("위로 넘기기 (아래 내용 보기)"),
    SWIPE_DOWN("아래로 넘기기 (위 내용 보기)"),
    SWIPE_LEFT("왼쪽으로 넘기기 (다음)"),
    SWIPE_RIGHT("오른쪽으로 넘기기 (이전)"),
    NOTIFICATIONS("알림창 열기"),
    QUICK_SETTINGS("제어센터 (빠른 설정)"),
    HOME("홈"),
    BACK("뒤로"),
    RECENTS("최근 앱"),
    SCREENSHOT("스크린샷"),
    LOCK("화면 잠금"),
}

/** 인식하는 제스처와 기본 동작 */
enum class Trigger(val label: String, val short: String, val default: GestureAction) {
    PALM_UP("손바닥 위로 휘두르기", "손바닥 ↑", GestureAction.SWIPE_UP),
    PALM_DOWN("손바닥 아래로 휘두르기", "손바닥 ↓", GestureAction.SWIPE_DOWN),
    PALM_LEFT("손바닥 왼쪽으로 휘두르기", "손바닥 ←", GestureAction.SWIPE_LEFT),
    PALM_RIGHT("손바닥 오른쪽으로 휘두르기", "손바닥 →", GestureAction.SWIPE_RIGHT),
    V_DOWN("✌ 브이 아래로 내리기", "✌ ↓", GestureAction.NOTIFICATIONS),
    V_UP("✌ 브이 위로 올리기", "✌ ↑", GestureAction.BACK),
    THREE_DOWN("세 손가락 아래로 내리기", "3손가락 ↓", GestureAction.QUICK_SETTINGS),
    THREE_UP("세 손가락 위로 올리기", "3손가락 ↑", GestureAction.HOME),
    THUMB_UP("👍 엄지 올리고 유지", "👍", GestureAction.HOME),
    THUMB_DOWN("👎 엄지 내리고 유지", "👎", GestureAction.BACK),
    SHAKA("🤙 엄지+새끼 펴고 유지", "🤙", GestureAction.RECENTS),
    OK("👌 OK 모양 유지", "👌", GestureAction.SCREENSHOT),
    FIST("✊ 주먹 쥐고 유지", "✊", GestureAction.NONE),
}
