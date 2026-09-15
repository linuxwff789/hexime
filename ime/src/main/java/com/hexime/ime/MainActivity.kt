package com.hexime.ime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.hexime.ime.data.RimeDataInstaller
import com.hexime.ime.engine.RimeEngine
import java.util.concurrent.Executors

/** 设置/引导页：启用输入法、选择输入法、重新部署。 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }

        statusView = TextView(this).apply { textSize = 15f }

        root.addView(TextView(this).apply {
            text = "Hexime 输入法"
            textSize = 24f
        })
        root.addView(statusView)
        root.addView(
            Button(this).apply {
                text = "1. 启用 Hexime（系统输入法设置）"
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }
            },
        )
        root.addView(
            Button(this).apply {
                text = "2. 选择 Hexime 为当前输入法"
                setOnClickListener {
                    getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                }
            },
        )
        root.addView(
            Button(this).apply {
                text = "重新部署词库（首次较慢）"
                setOnClickListener { redeploy() }
            },
        )

        root.addView(TextView(this).apply {
            text = "\n测试输入（点下面输入框调出键盘）："
            setPadding(0, dp(40), 0, dp(8))
        })
        root.addView(
            EditText(this).apply {
                hint = "在这里输入…"
                minLines = 2
            },
        )

        setContentView(root)
        refreshStatus()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    }

    private fun refreshStatus() {
        io.execute {
            val shared = RimeDataInstaller.ensureInstalled(this)
            val count = shared.listFiles()?.size ?: 0
            val version = runCatching { RimeEngine().version() }.getOrDefault("?")
            main.post {
                statusView.text = "librime: $version\n数据文件: $count 个\n目录: ${shared.absolutePath}"
            }
        }
    }

    private fun redeploy() {
        io.execute {
            val shared = RimeDataInstaller.ensureInstalled(this)
            val user = RimeDataInstaller.userDir(this).apply { mkdirs() }
            val log = RimeDataInstaller.logDir(this).apply { mkdirs() }
            val engine = RimeEngine()
            if (engine.initialize(shared.absolutePath, user.absolutePath, log.absolutePath)) {
                val t0 = System.currentTimeMillis()
                engine.deploy(true)
                val ms = System.currentTimeMillis() - t0
                engine.shutdown()
                main.post { statusView.text = "部署完成：${ms} ms" }
            }
        }
    }
}
