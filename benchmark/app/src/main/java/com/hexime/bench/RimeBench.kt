package com.hexime.bench

/**
 * librime C API 的 JNI 绑定。
 *
 * 注意：所有 native 方法名必须与 cpp/bench/rime_bench_jni.cpp 中的
 * Java_com_hexime_bench_RimeBench_nativeXxx 对应。
 */
object RimeBench {

    init {
        System.loadLibrary("hexime_bench")
    }

    /** 初始化 librime。返回是否成功。 */
    external fun nativeInit(
        sharedDataDir: String,
        userDataDir: String,
        logDir: String,
        distributionName: String,
        distributionVersion: String,
    ): Boolean

    /** 执行部署（把 yaml 方案编译为 .bin）。fullCheck=true 强制全量。 */
    external fun nativeDeploy(fullCheck: Boolean): Boolean

    /** 创建会话并选择方案。schemaId 可为空（用默认方案）。返回 sessionId，0 表示失败。 */
    external fun nativeCreateSession(schemaId: String?): Long

    external fun nativeDestroySession(sessionId: Long)

    external fun nativeClearComposition(sessionId: Long)

    /** 模拟按键序列（ASCII），例如 "nihao"。 */
    external fun nativeSimulateKeys(sessionId: Long, keys: String): Boolean

    /** 取当前候选，元素格式 "text\tcomment"。 */
    external fun nativeGetCandidates(sessionId: Long): Array<String>

    /** 取当前组合串（preedit）。 */
    external fun nativeGetComposition(sessionId: Long): String

    /** 取并消费已上屏文本（commit）。 */
    external fun nativeGetCommit(sessionId: Long): String

    /**
     * 基准：反复输入 keys，统计每个按键的延迟（毫秒）。
     * 返回 [avg, p50, p95, p99, max]。
     */
    external fun nativeBenchLatency(sessionId: Long, keys: String, iterations: Int): DoubleArray

    external fun nativeGetVersion(): String

    external fun nativeFinalize()
}
