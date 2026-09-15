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
        val composition = RimeNative.composition(sessionId)
        val raw = RimeNative.candidates(sessionId)
        val candidates = raw.map { item ->
            val tab = item.indexOf('\t')
            if (tab >= 0) {
                Candidate(item.substring(0, tab), item.substring(tab + 1))
            } else {
                Candidate(item)
            }
        }
        val page = RimeNative.pageInfo(sessionId)
        return EngineSnapshot(
            composition = composition,
            candidates = candidates,
            pageNo = page.getOrElse(0) { 0 },
            isLastPage = page.getOrElse(1) { 1 } != 0,
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
