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

    /**
     * 可选方案。openfly / rime_ice 随 APK 自带（assets/rime）；
     * 「小鹤官方」(flypy) 是官方鼠须管那套的 schema + 预编译词库表，
     * 文件不进仓库，由使用者自己放到设备的 rime 目录里，放了就能在选单/设置里选到。
     */
    val ALL: List<Pair<String, String>> = listOf(
        "openfly" to "小鹤音形",
        "flypy" to "小鹤官方",
        "rime_ice" to "雾凇拼音",
    )

    fun label(id: String): String = ALL.firstOrNull { it.first == id }?.second ?: id

    /** 偏好里存了一个已经去掉的方案时，退回默认（免得建会话用了没列出来的方案）。 */
    fun normalize(id: String): String = if (ALL.any { it.first == id }) id else DEFAULT_ID

    fun next(current: String): String {
        val i = ALL.indexOfFirst { it.first == current }
        return if (i < 0) DEFAULT_ID else ALL[(i + 1) % ALL.size].first
    }
}
