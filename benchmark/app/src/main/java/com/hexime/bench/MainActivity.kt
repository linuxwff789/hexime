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

    /** 每个方案：id + 用于测延迟的按键序列 */
    private data class SchemaCase(val id: String, val label: String, val sequences: List<String>)

    private val cases = listOf(
        SchemaCase(
            id = "luna_pinyin",
            label = "拼音 luna_pinyin",
            sequences = listOf(
                "ni", "nihao", "zhongguo", "shurufa",
                "woshiyigexiaohuozi", "jintiantianqizenmeyang",
            ),
        ),
        SchemaCase(
            id = "openfly",
            label = "小鹤音形 openfly (无 lua)",
            // 来自 openfly 码表：你=n 好=hc 中国=vsg 输入法=urf 小鹤=xnhe 阿=aaek
            sequences = listOf("n", "hc", "vsg", "urf", "xnhe", "aaek"),
        ),
        SchemaCase(
            id = "openfly_lua",
            label = "小鹤音形 openfly_lua (+lua filter)",
            sequences = listOf("n", "hc", "vsg", "urf", "xnhe", "aaek"),
        ),
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
        log("rime data -> ${sharedDir.absolutePath} (${sharedDir.listFiles()?.size ?: 0} files)")

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
        // fullCheck=false：已部署过则秒返（真实 App 启动行为）；首次安装则完整部署
        val deployed = RimeBench.nativeDeploy(false)
        val deployMs = (System.nanoTime() - t1) / 1_000_000.0
        log("--- deploy(auto): started=$deployed, ${"%.1f".format(deployMs)} ms, pss=${pssKb()} KB")

        for (case in cases) {
            runCase(case)
        }

        RimeBench.nativeFinalize()
        log("--- after finalize: pss=${pssKb()} KB")
    }

    private fun runCase(case: SchemaCase) {
        val sid = RimeBench.nativeCreateSession(case.id)
        log("")
        log("=========== ${case.label} (${case.id}) ===========")
        log("--- session: $sid, pss=${pssKb()} KB")
        if (sid == 0L) {
            log("!! 会话创建失败（方案是否已部署？）")
            return
        }

        // 候选质量抽查
        for (k in case.sequences.take(4)) {
            RimeBench.nativeClearComposition(sid)
            RimeBench.nativeSimulateKeys(sid, k)
            log("candidates[$k] = ${RimeBench.nativeGetCandidates(sid).take(5).joinToString(" | ")}")
        }

        log("--- latency (per key, ms) ---")
        for (seq in case.sequences) {
            val r = RimeBench.nativeBenchLatency(sid, seq, 200)
            log("%-26s avg=%.2f p50=%.2f p95=%.2f p99=%.2f max=%.2f"
                .format(seq, r[0], r[1], r[2], r[3], r[4]))
        }
        log("--- after ${case.id}: pss=${pssKb()} KB")
        RimeBench.nativeDestroySession(sid)
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
