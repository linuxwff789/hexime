package com.hexime.ime.engine

/** 单个候选词。comment 是 librime 给出的注释（如拼音、提示）。 */
data class Candidate(val text: String, val comment: String = "")

/** 一次按键后的引擎状态快照。 */
data class EngineSnapshot(
    val composition: String = "",
    val candidates: List<Candidate> = emptyList(),
    val pageNo: Int = 0,
    val isLastPage: Boolean = true,
)

/**
 * 输入引擎抽象。当前实现是 librime（[RimeEngine]）；
 * 保留接口是为了将来可替换实现而不动 UI/Service。
 */
interface InputEngine {

    /** 初始化引擎（设置数据目录）。可能较慢，应在后台线程调用。 */
    fun initialize(sharedDir: String, userDir: String, logDir: String): Boolean

    /** 部署/编译方案。首次较慢，应在后台线程调用。 */
    fun deploy(fullCheck: Boolean): Boolean

    /** 创建输入会话并选择方案。schemaId 为 null 时用默认方案。 */
    fun createSession(schemaId: String?): Boolean

    /** 是否已有可用会话。 */
    fun hasSession(): Boolean

    fun destroySession()

    /** 处理一次按键。keySym 为 X11 keysym，mask 为修饰键位。 */
    fun processKey(keySym: Int, mask: Int): Boolean

    /** 选择候选项（当前页索引）。 */
    fun selectCandidate(index: Int): Boolean

    /** 当前状态（组合串 + 候选）。 */
    fun snapshot(): EngineSnapshot

    /** 取出并消费已上屏文本；无则返回 null。 */
    fun takeCommit(): String?

    fun clearComposition()

    fun setOption(name: String, value: Boolean)

    fun selectSchema(schemaId: String): Boolean

    fun currentSchema(): String

    fun version(): String

    fun shutdown()

    companion object {
        // X11 keysym
        const val KEY_SPACE = 0x0020
        const val KEY_RETURN = 0xFF0D
        const val KEY_BACKSPACE = 0xFF08
        const val KEY_ESCAPE = 0xFF1B
        const val KEY_COMMA = 0x002C
        const val KEY_PERIOD = 0x002E
        const val KEY_SEMICOLON = 0x003B

        const val MASK_SHIFT = 1 shl 0
        const val MASK_LOCK = 1 shl 1
        const val MASK_CONTROL = 1 shl 2

        fun letterKeysym(ch: Char): Int = ch.code
    }
}
