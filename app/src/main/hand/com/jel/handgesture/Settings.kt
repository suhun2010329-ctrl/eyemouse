package com.jel.handgesture

import android.content.Context
import android.content.SharedPreferences

/** SharedPreferences 기반 설정 (인식 스레드에서 매 프레임 읽어도 가볍다) */
object Settings {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("hand", Context.MODE_PRIVATE)
    }

    fun action(t: Trigger): GestureAction =
        sp.getString("act_${t.name}", null)?.let { runCatching { GestureAction.valueOf(it) }.getOrNull() } ?: t.default

    fun setAction(t: Trigger, a: GestureAction) = sp.edit().putString("act_${t.name}", a.name).apply()

    /** 숫자(1~5)에 연결된 앱 패키지 */
    fun app(n: Int): String? = sp.getString("app_$n", null)
    fun appLabel(n: Int): String? = sp.getString("appLabel_$n", null)
    fun setApp(n: Int, pkg: String?, label: String?) =
        sp.edit().putString("app_$n", pkg).putString("appLabel_$n", label).apply()

    /** 휘두르기 민감도 (0.6 둔감 ~ 1.6 민감) */
    var sensitivity: Float
        get() = sp.getFloat("sens", 1f)
        set(v) = sp.edit().putFloat("sens", v).apply()

    /** 손 모양 유지 시간(ms) */
    var holdMs: Int
        get() = sp.getInt("hold", 600)
        set(v) = sp.edit().putInt("hold", v).apply()

    /** 숫자 바로가기 유지 시간(ms) */
    var numberHoldMs: Int
        get() = sp.getInt("numHold", 1000)
        set(v) = sp.edit().putInt("numHold", v).apply()

    var numbersEnabled: Boolean
        get() = sp.getBoolean("numbers", true)
        set(v) = sp.edit().putBoolean("numbers", v).apply()

    /** 검지 튕기기로 파란 선택 띠 이동 */
    var focusEnabled: Boolean
        get() = sp.getBoolean("focus", true)
        set(v) = sp.edit().putBoolean("focus", v).apply()

    /** 손이 안 보이면 처리량을 1/3로 줄여 배터리 절약 */
    var idleSaver: Boolean
        get() = sp.getBoolean("idleSaver", true)
        set(v) = sp.edit().putBoolean("idleSaver", v).apply()

    var showHud: Boolean
        get() = sp.getBoolean("hud", true)
        set(v) = sp.edit().putBoolean("hud", v).apply()
}
