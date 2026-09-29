package com.jel.handgesture

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** SharedPreferences 기반 설정 (메모리 캐시라 인식 스레드에서 매 프레임 읽어도 가볍다) */
object Settings {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("hand", Context.MODE_PRIVATE)
    }

    private class F(val key: String, val def: Float) : ReadWriteProperty<Any?, Float> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Float = Settings.sp.getFloat(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) { Settings.sp.edit().putFloat(key, value).apply() }
    }

    private class I(val key: String, val def: Int) : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int = Settings.sp.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { Settings.sp.edit().putInt(key, value).apply() }
    }

    private class B(val key: String, val def: Boolean) : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = Settings.sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) { Settings.sp.edit().putBoolean(key, value).apply() }
    }

    // ── 제스처 → 동작 ──
    fun action(t: Trigger): GestureAction =
        sp.getString("act_${t.name}", null)?.let { runCatching { GestureAction.valueOf(it) }.getOrNull() } ?: t.default

    fun setAction(t: Trigger, a: GestureAction) = sp.edit().putString("act_${t.name}", a.name).apply()

    fun resetActions() {
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("act_") }.forEach { e.remove(it) }
        e.apply()
    }

    // ── 숫자 바로가기 ──
    fun app(n: Int): String? = sp.getString("app_$n", null)
    fun appLabel(n: Int): String? = sp.getString("appLabel_$n", null)
    fun setApp(n: Int, pkg: String?, label: String?) =
        sp.edit().putString("app_$n", pkg).putString("appLabel_$n", label).apply()

    var numbersEnabled by B("numbers", true)
    var numberHoldMs by I("numHold", 1000)

    // ── 휘두르기 ──
    /** 거리 민감도 (0.5 둔감 ~ 2.0 민감) */
    var sensitivity by F("sens2", 1f)
    /** 속도 민감도 (0.5 빠르게 휘둘러야 함 ~ 2.0 천천히도 인식) */
    var speedSens by F("speedSens", 1f)
    /** 반대 방향으로 되돌아오는 손짓을 무시하는 시간(ms) */
    var reboundMs by I("rebound", 600)
    /** 동작 실행 후 다음 동작까지 최소 간격(ms) */
    var cooldownMs by I("cooldown", 350)
    /** 손 모양 유지 시간(ms) */
    var holdMs by I("hold", 600)
    /** '쓸기' 동작 한 번의 이동 거리 (화면 비율) */
    var swipeDistance by F("swipeDist", 0.4f)

    // ── 쓸기 (휠) ──
    var wheelEnabled by B("wheel", true)
    /** 0 = 손 모으기, 1 = 검지, 2 = 둘 다 */
    var wheelPose by I("wheelPose", 0)
    var wheelGain by F("wheelGain", 1.2f)
    var wheelReverse by B("wheelReverse", false)
    var wheelHorizontal by B("wheelH", true)
    var wheelInertia by B("wheelInertia", true)
    var wheelAxisLock by B("wheelAxis", true)

    // ── 포인터 모드 ──
    var dwellClick by B("dwellClick", true)
    var dwellMs by I("dwellMs", 900)
    var dwellRadiusDp by F("dwellRadius", 24f)
    /** 카메라 화면 중 포인터가 화면 끝까지 닿는 범위 (작을수록 조금만 움직여도 끝까지) */
    var pointerRange by F("pRange", 0.65f)
    /** 떨림 보정 세기 0 ~ 1 */
    var pointerSmooth by F("pSmooth", 0.5f)
    var pointerSize by F("pSize", 1f)
    var clickableFirst by B("clickableFirst", true)
    /** 포인터로 쓸 손가락 (비트 0 = 엄지 … 4 = 새끼) */
    var pointerFingers by I("pFingers", 0b11111)

    // ── 손 인식·카메라 ──
    var detectConf by F("detConf", 0.55f)
    var trackConf by F("trkConf", 0.45f)
    /** 손 모양이 확정되기까지 연속으로 같아야 하는 프레임 수 */
    var poseFrames by I("poseFrames", 3)
    var highRes by B("highRes", false)

    // ── 발열·배터리 ──
    /** 0 = 절전(최대 30fps), 1 = 균형(움직일 때만 60fps), 2 = 최고(손 있으면 항상 60fps) */
    var perfMode by I("perfMode", 1)
    /** 휴대폰이 뜨거워지면 fps를 자동으로 낮춤 */
    var thermalGuard by B("thermalGuard", true)
    /** 손이 안 보이면 처리량을 1/3로 줄여 배터리 절약 */
    var idleSaver by B("idleSaver", true)
    var showHud by B("hud", true)

    /** 인식·쓸기·포인터 설정만 기본값으로 (동작 지정과 앱 바로가기는 유지) */
    fun resetTuning() {
        val e = sp.edit()
        sp.all.keys.filter { !it.startsWith("act_") && !it.startsWith("app") }.forEach { e.remove(it) }
        e.apply()
    }
}
