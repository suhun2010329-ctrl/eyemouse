package com.jel.eyemouse

import android.content.Context
import android.content.SharedPreferences

enum class TrackMode { HEAD, EYE }
enum class ClickMode { DWELL, BLINK, BOTH }

/** SharedPreferences 기반 설정. 추적 스레드에서 매 프레임 읽어도 될 만큼 가볍다. */
object Settings {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("eyemouse", Context.MODE_PRIVATE)
    }

    var mode: TrackMode
        get() = enumOr(sp.getString("mode", null), TrackMode.HEAD)
        set(v) = sp.edit().putString("mode", v.name).apply()

    var clickMode: ClickMode
        get() = enumOr(sp.getString("click", null), ClickMode.DWELL)
        set(v) = sp.edit().putString("click", v.name).apply()

    /** 머리 모드 감도 (2~12) */
    var headGain: Float
        get() = sp.getFloat("gain", 6f)
        set(v) = sp.edit().putFloat("gain", v).apply()

    /** 응시 클릭까지 머무는 시간(ms) */
    var dwellMs: Int
        get() = sp.getInt("dwell", 1000)
        set(v) = sp.edit().putInt("dwell", v).apply()

    /** 0 = 빠르고 떨림 많음, 1 = 느리고 안정적 */
    var stability: Float
        get() = sp.getFloat("stability", 0.5f)
        set(v) = sp.edit().putFloat("stability", v).apply()

    var invertX: Boolean
        get() = sp.getBoolean("invX", false)
        set(v) = sp.edit().putBoolean("invX", v).apply()

    var invertY: Boolean
        get() = sp.getBoolean("invY", false)
        set(v) = sp.edit().putBoolean("invY", v).apply()

    /** 머리 모드 기준 자세 (NaN = 아직 없음) */
    var neutralX: Float
        get() = sp.getFloat("nX", Float.NaN)
        set(v) = sp.edit().putFloat("nX", v).apply()

    var neutralY: Float
        get() = sp.getFloat("nY", Float.NaN)
        set(v) = sp.edit().putFloat("nY", v).apply()

    /** 시선 캘리브레이션 결과(JSON) */
    var gazeModel: String?
        get() = sp.getString("gazeModel", null)
        set(v) = sp.edit().putString("gazeModel", v).apply()

    private inline fun <reified T : Enum<T>> enumOr(s: String?, def: T): T =
        s?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: def
}
