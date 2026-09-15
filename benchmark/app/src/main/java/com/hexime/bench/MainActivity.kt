package com.hexime.bench

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var logView: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val sb = StringBuilder()

    private val schemaId = "luna_pinyin"

    /** 测试用按键序列：越靠后越长，观察延迟是否随长度退化 */
    private val testSequences = listOf(
        "ni", "nihao", "zhongguo", "shurufa",
        "woshiyigexiaohuozi", "jintiantianqizenmeyang",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        logView = findViewById(R.id.log)
        logView.typeface = Typeface.MONOSPACE
        findViewById<Button>(R.id.run).setOnClickListener { runBenchmark() }
    }

    private fun log(line: String) {
        Log.i("HeximeBench", line)
        sb.append(line).append('\n')
        main.post { logView.text = sb.toString() }
    }

    private fun pssKb(): Long {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss.toLong()
    }

    private fun runBenchmark() {
        findViewById<Button>(R.id.run).isEnabled = false
        sb.setLength(0)
        worker.execute {
            try {
                benchmark()
            } catch (t: Throwable) {
                log("FATAL: ${t.stackTraceToString()}")
            } finally {
                main.post { findViewById<Button>(R.id.run).isEnabled = true }
            }
        }
    }

    private fun benchmark() {
        log("librime version: ${RimeBench.nativeGetVersion()}")
        log("--- memory baseline: ${pssKb()} KB")

        val sharedDir = File(filesDir, "rime/shared").apply { mkdirs() }
        val userDir = File(filesDir, "rime/user").apply { mkdirs() }
        val logDir = File(filesDir, "rime/log").apply { mkdirs() }
        copyAssets("rime", sharedDir)
        log("rift data -> ${sharedDir.absolutePath} (${sharedDir.listFiles()?.size ?: 0} files)")

        val t0 = System.nanoTime()
        val ok = RimeBench.nativeInit(
            sharedDir.absolutePath,
            userDir.absolutePath,
            logDir.absolutePath,
            "HeximeBench",
            "0.1.0",
        )
        val initMs = (System.nanoTime() - t0) / 1_000_000.0
        log("--- init: ok=$ok, ${"%.1f".format(initMs)} ms, pss=${pssKb()} KB")

        val t1 = System.nanoTime()
        val deployed = RimeBench.nativeDeploy(true)
        val deployMs = (System.nanoTime() - t1) / 1_000_000.0
        log("--- deploy: started=$deployed, ${"%.1f".format(deployMs)} ms, pss=${pssKb()} KB")

        val sid = RimeBench.nativeCreateSession(schemaId)
        log("--- session: $sid (schema=$schemaId), pss=${pssKb()} KB")
        if (sid == 0L) return

        // 候选质量抽查
        for (k in listOf("nihao", "zhongguo")) {
            RimeBench.nativeClearComposition(sid)
            RimeBench.nativeSimulateKeys(sid, k)
            log("candidates[$k] = ${RimeBench.nativeGetCandidates(sid).take(5).joinToString(" | ")}")
        }

        // 按键延迟
        log("--- latency (per key, ms) ---")
        for (seq in testSequences) {
            val r = RimeBench.nativeBenchLatency(sid, seq, 200)
            log("%-26s avg=%.2f p50=%.2f p95=%.2f p99=%.2f max=%.2f"
                .format(seq, r[0], r[1], r[2], r[3], r[4]))
        }
        log("--- after bench: pss=${pssKb()} KB")

        RimeBench.nativeDestroySession(sid)
        RimeBench.nativeFinalize()
    }

    private fun copyAssets(assetDir: String, dest: File) {
        val children = assets.list(assetDir) ?: return
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(assetDir).use { input ->
                FileOutputStream(dest).use { input.copyTo(it) }
            }
            return
        }
        dest.mkdirs()
        for (name in children) copyAssets("$assetDir/$name", File(dest, name))
    }
}
