package com.hexime.ime.engine

import android.util.Log

/** 基于 librime 的引擎实现。 */
class RimeEngine : InputEngine {

    private var sessionId: Long = 0L
    private var initialized = false

    override fun initialize(sharedDir: String, userDir: String, logDir: String): Boolean {
        if (initialized) return true
        initialized = RimeNative.init(sharedDir, userDir, logDir, "Hexime", "0.1.0")
        Log.i(TAG, "initialize -> $initialized")
        return initialized
    }

    override fun deploy(fullCheck: Boolean): Boolean {
        if (!initialized) return false
        val started = RimeNative.deploy(fullCheck)
        Log.i(TAG, "deploy(fullCheck=$fullCheck) -> $started")
        return started
    }

    override fun createSession(schemaId: String?): Boolean {
        if (!initialized) return false
        if (sessionId != 0L) destroySession()
        sessionId = RimeNative.createSession()
        if (sessionId == 0L) return false
        if (!schemaId.isNullOrEmpty()) {
            RimeNative.selectSchema(sessionId, schemaId)
        }
        Log.i(TAG, "session=$sessionId schema=${currentSchema()}")
        return true
    }

    override fun hasSession(): Boolean = sessionId != 0L

    override fun destroySession() {
        if (sessionId != 0L) {
            RimeNative.destroySession(sessionId)
            sessionId = 0L
        }
    }

    override fun processKey(keySym: Int, mask: Int): Boolean {
        if (sessionId == 0L) return false
        return RimeNative.processKey(sessionId, keySym, mask)
    }

    override fun selectCandidate(index: Int): Boolean {
        if (sessionId == 0L) return false
        return RimeNative.selectCandidate(sessionId, index)
    }

    override fun snapshot(): EngineSnapshot {
        if (sessionId == 0L) return EngineSnapshot()
        // 一次 JNI：[0] 组合串 [1] 页码 [2] 是否末页 [3..] 候选（原来要 composition/candidates/pageInfo 三次往返）
        val raw = RimeNative.snapshot(sessionId)
        if (raw.size < 3) return EngineSnapshot()
        val candidates = ArrayList<Candidate>(raw.size - 3)
        for (i in 3 until raw.size) candidates.add(Candidate(raw[i]))
        return EngineSnapshot(
            composition = raw[0],
            candidates = candidates,
            pageNo = raw[1].toIntOrNull() ?: 0,
            isLastPage = raw[2] == "1",
        )
    }

    override fun takeCommit(): String? {
        if (sessionId == 0L) return null
        val text = RimeNative.commit(sessionId)
        return text.ifEmpty { null }
    }

    override fun clearComposition() {
        if (sessionId != 0L) RimeNative.clearComposition(sessionId)
    }

    override fun setOption(name: String, value: Boolean) {
        if (sessionId != 0L) RimeNative.setOption(sessionId, name, value)
    }

    override fun selectSchema(schemaId: String): Boolean {
        if (sessionId == 0L) return false
        return RimeNative.selectSchema(sessionId, schemaId)
    }

    override fun currentSchema(): String {
        if (sessionId == 0L) return ""
        return RimeNative.currentSchema(sessionId)
    }

    override fun version(): String = RimeNative.version()

    override fun shutdown() {
        destroySession()
        if (initialized) {
            RimeNative.shutdown()
            initialized = false
        }
    }

    private companion object {
        const val TAG = "HeximeEngine"
    }
}
