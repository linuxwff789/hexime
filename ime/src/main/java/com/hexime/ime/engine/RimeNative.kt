package com.hexime.ime.engine

/** librime C API 的 JNI 绑定。方法名与 cpp JNI 中的 Java_..._RimeNative_* 对应。 */
object RimeNative {

    init {
        System.loadLibrary("hexime_rime")
    }

    external fun init(
        sharedDir: String,
        userDir: String,
        logDir: String,
        distributionName: String,
        distributionVersion: String,
    ): Boolean

    external fun deploy(fullCheck: Boolean): Boolean

    external fun createSession(): Long

    external fun destroySession(sessionId: Long)

    external fun selectSchema(sessionId: Long, schemaId: String): Boolean

    external fun currentSchema(sessionId: Long): String

    external fun processKey(sessionId: Long, keySym: Int, mask: Int): Boolean

    external fun selectCandidate(sessionId: Long, index: Int): Boolean

    external fun composition(sessionId: Long): String

    /** 元素格式 "text\tcomment"。 */
    external fun candidates(sessionId: Long): Array<String>

    /** [pageNo, isLastPage]。 */
    external fun pageInfo(sessionId: Long): IntArray

    /**
     * 一次拿齐按键后要刷新的全部状态（省两次 JNI 往返）：
     * `[0]` 组合串，`[1]` 页码，`[2]` 是否末页("1"/"0")，`[3..]` 候选文字。
     */
    external fun snapshot(sessionId: Long): Array<String>

    external fun commit(sessionId: Long): String

    external fun clearComposition(sessionId: Long)

    external fun setOption(sessionId: Long, name: String, value: Boolean)

    external fun version(): String

    external fun shutdown()
}
