package com.hexime.ime.ui

import android.content.Context
import android.content.res.Configuration

/**
 * 键盘配色。跟随系统深色模式（resources.configuration.uiMode），
 * 两套：LIGHT（白色）与 DARK（暗黑）。
 *
 * 颜色集中在这里，KeyboardView / CandidateBar / 状态栏都从这里取，
 * 换配色只改这一个文件。
 */
object HeximeTheme {

    class Palette(
        val keyboardBg: Int,
        val keyFace: Int,
        val keyPressed: Int,
        val keyText: Int,
        val barBg: Int,
        val barText: Int,
        val barDim: Int,
        /** 候选栏左侧「编码」的强调色。 */
        val code: Int,
        val statusBg: Int,
        val statusText: Int,
    )

    /** 白色配色。 */
    val LIGHT = Palette(
        keyboardBg = 0xFFCFD8DC.toInt(),
        keyFace = 0xFFFFFFFF.toInt(),
        keyPressed = 0xFF90A4AE.toInt(),
        keyText = 0xFF212121.toInt(),
        barBg = 0xFFECEFF1.toInt(),
        barText = 0xFF212121.toInt(),
        barDim = 0xFF78909C.toInt(),
        code = 0xFF0277BD.toInt(),
        statusBg = 0xFFECEFF1.toInt(),
        statusText = 0xFF607D8B.toInt(),
    )

    /** 暗黑配色。 */
    val DARK = Palette(
        keyboardBg = 0xFF121212.toInt(),
        keyFace = 0xFF2C2C2C.toInt(),
        keyPressed = 0xFF4A4A4A.toInt(),
        keyText = 0xFFECEFF1.toInt(),
        barBg = 0xFF1E1E1E.toInt(),
        barText = 0xFFECEFF1.toInt(),
        barDim = 0xFF90A4AE.toInt(),
        code = 0xFF4FC3F7.toInt(),
        statusBg = 0xFF1E1E1E.toInt(),
        statusText = 0xFF90A4AE.toInt(),
    )

    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun of(context: Context): Palette = if (isNight(context)) DARK else LIGHT
}
