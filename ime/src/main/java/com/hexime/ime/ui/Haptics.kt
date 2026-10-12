package com.hexime.ime.ui

import android.os.VibrationEffect

/**
 * 按键震动。
 *
 * 原来用一个 12ms 的单发 one-shot：太短，震动强度从 30% 调到 70% 摸起来几乎没差别
 * （用户反馈「设置了震动强度没有改动」）。改成两级短脉冲共 ~42ms，
 * 第二级比第一级强一倍 —— 也就是
 * `Composed{[Step(0,d=0), Step(0.3137255,d=12), Step(0,d=10), Step(0.627451,d=20)]}` 这个形状。
 *
 * 这里保留同样的形状，两级幅度都按「震动强度」百分比缩放，
 * 所以拖动强度条时手感变化是能摸出来的。
 */
object Haptics {
    /** 0（关）返回 null。 */
    fun effect(percent: Int): VibrationEffect? {
        if (percent <= 0) return null
        val k = percent.coerceIn(0, 100) / 100.0
        val weak = (255 * 0.5 * k).toInt().coerceIn(1, 255)
        val strong = (255 * k).toInt().coerceIn(1, 255)
        return VibrationEffect.createWaveform(
            longArrayOf(0L, 12L, 10L, 20L),
            intArrayOf(0, weak, 0, strong),
            -1,
        )
    }
}
