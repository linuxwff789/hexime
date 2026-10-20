package com.hexime.ime.data

import android.content.Context

/** 输入法设置（SharedPreferences）。 */
object HeximeSettings {

    private const val PREF = "hexime_settings"
    private const val KEY_VIBRATION_PERCENT = "vibration_percent"
    private const val KEY_SHOW_LATENCY = "show_latency"
    private const val KEY_KEYBOARD_HEIGHT = "keyboard_height_dp"
    private const val KEY_FLOAT_ALPHA = "float_alpha_percent"
    private const val KEY_PALETTE = "palette_id"
    private const val KEY_SCHEMA = "schema_id"
    private const val KEY_SHOW_CANDIDATE_COMMENT = "show_candidate_comment"

    /** 震动强度百分比 0..100，0 = 关闭。 */
    const val VIBRATION_DEFAULT = 30

    /**
     * 键盘**总高度**（dp）—— 所有键盘页共用（行高 = 总高 × 该行 h%，
     * 所以 4 行主键盘和 5 行符号页高度相同）。
     *
     * 旧版本这个值表示「单行高度」，读到 <120 的旧值会 ×4 迁移。
     */
    const val KEYBOARD_HEIGHT_DEFAULT = 184
    const val KEYBOARD_HEIGHT_MIN = 120
    const val KEYBOARD_HEIGHT_MAX = 320

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun vibrationPercent(context: Context): Int =
        prefs(context).getInt(KEY_VIBRATION_PERCENT, VIBRATION_DEFAULT).coerceIn(0, 100)

    fun setVibrationPercent(context: Context, value: Int) {
        prefs(context).edit()
            .putInt(KEY_VIBRATION_PERCENT, value.coerceIn(0, 100))
            .apply()
    }

    /**
     * 是否在输入法状态栏显示实时「跟手延迟」。
     * 默认关：开着的话每按一个键都要 post 一次 Choreographer 回调 + 刷状态行，
     * 属于开发调优指标，平时不用付这个成本（点状态栏或设置页都能开）。
     */
    fun showLatency(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_LATENCY, false)

    fun setShowLatency(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_LATENCY, value).apply()
    }

    /** 键盘总高度（dp）。 */
    fun keyboardHeightDp(context: Context): Int {
        val raw = prefs(context).getInt(KEY_KEYBOARD_HEIGHT, KEYBOARD_HEIGHT_DEFAULT)
        val migrated = if (raw < KEYBOARD_HEIGHT_MIN) raw * 4 else raw // 旧语义：单行高度
        return migrated.coerceIn(KEYBOARD_HEIGHT_MIN, KEYBOARD_HEIGHT_MAX)
    }

    fun setKeyboardHeightDp(context: Context, value: Int) {
        prefs(context).edit()
            .putInt(KEY_KEYBOARD_HEIGHT, value.coerceIn(KEYBOARD_HEIGHT_MIN, KEYBOARD_HEIGHT_MAX))
            .apply()
    }

    /**
     * 悬浮键盘整体透明度（百分比，100 = 完全不透明）。
     * 悬浮时整个面板（键面 + 文字 + 背景）都按这个值调 alpha，**与配色无关** ——
     * 以前是写死 0.85，加上各配色自己的底色差别，看起来像"只有某些配色变透明"。
     */
    const val FLOAT_ALPHA_DEFAULT = 70
    const val FLOAT_ALPHA_MIN = 30
    const val FLOAT_ALPHA_MAX = 100

    fun floatAlphaPercent(context: Context): Int =
        prefs(context).getInt(KEY_FLOAT_ALPHA, FLOAT_ALPHA_DEFAULT)
            .coerceIn(FLOAT_ALPHA_MIN, FLOAT_ALPHA_MAX)

    fun setFloatAlphaPercent(context: Context, value: Int) {
        prefs(context).edit()
            .putInt(KEY_FLOAT_ALPHA, value.coerceIn(FLOAT_ALPHA_MIN, FLOAT_ALPHA_MAX))
            .apply()
    }

    /** 配色 id（见 HeximePalettes）。 */
    fun paletteId(context: Context): String =
        prefs(context).getString(KEY_PALETTE, null) ?: PALETTE_DEFAULT

    fun setPaletteId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_PALETTE, id).apply()
    }

    /** 方案 id（见 Schemas）。 */
    fun schemaId(context: Context): String =
        Schemas.normalize(prefs(context).getString(KEY_SCHEMA, null) ?: Schemas.DEFAULT_ID)

    fun setSchemaId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_SCHEMA, id).apply()
    }

    /** 默认配色（与 HeximePalettes.DEFAULT_ID 一致，这里写字面量避免 data 层依赖 ui 层）。 */
    const val PALETTE_DEFAULT = "小鹤"

    /**
     * 候选栏是否在候选字后面跟一小号注释（反查的编码、拼音）。
     *
     * 默认**开**：反查（打全码 + 反引号）就是看编码用的，只出字等于白查。
     * 没有改成开关界面的入口（设置页没这个项），但存成偏好方便以后接上。
     */
    fun showCandidateComment(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_CANDIDATE_COMMENT, true)

    fun setShowCandidateComment(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_CANDIDATE_COMMENT, value).apply()
    }
}
