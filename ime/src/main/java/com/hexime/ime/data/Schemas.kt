package com.hexime.ime.data

/**
 * 可选方案（librime schema）。assets/rime 下已经带了这些 schema.yaml。
 *
 * 顺序 = 选单「方案」键循环的顺序，也是设置页下拉框的顺序。
 */
object Schemas {

    /** 默认方案：小鹤音形。 */
    const val DEFAULT_ID = "openfly"

    /** 音形方案（键面是字根助记键盘）。 */
    const val OPENFLY_ID = "openfly"

    val ALL: List<Pair<String, String>> = listOf(
        "openfly" to "小鹤音形",
        "luna_pinyin" to "拼音（朙月）",
        "luna_pinyin_simp" to "简体拼音",
        "luna_quanpin" to "全拼",
        "stroke" to "笔画",
    )

    fun label(id: String): String = ALL.firstOrNull { it.first == id }?.second ?: id

    fun next(current: String): String {
        val i = ALL.indexOfFirst { it.first == current }
        return if (i < 0) DEFAULT_ID else ALL[(i + 1) % ALL.size].first
    }
}
