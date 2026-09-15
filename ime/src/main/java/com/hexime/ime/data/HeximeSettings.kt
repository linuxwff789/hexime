package com.hexime.ime.data

import android.content.Context

/** 输入法设置（SharedPreferences）。 */
object HeximeSettings {

    private const val PREF = "hexime_settings"
    private const val KEY_VIBRATION_PERCENT = "vibration_percent"
    private const val KEY_SHOW_LATENCY = "show_latency"

    /** 震动强度百分比 0..100，0 = 关闭。 */
    const val VIBRATION_DEFAULT = 30

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun vibrationPercent(context: Context): Int =
        prefs(context).getInt(KEY_VIBRATION_PERCENT, VIBRATION_DEFAULT).coerceIn(0, 100)

    fun setVibrationPercent(context: Context, value: Int) {
        prefs(context).edit()
            .putInt(KEY_VIBRATION_PERCENT, value.coerceIn(0, 100))
            .apply()
    }

    /** 是否在输入法状态栏显示实时「跟手延迟」。开发调优期默认开启。 */
    fun showLatency(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_LATENCY, true)

    fun setShowLatency(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_LATENCY, value).apply()
    }
}
