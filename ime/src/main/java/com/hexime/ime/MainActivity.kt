package com.hexime.ime

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.hexime.ime.data.HeximeSettings
import com.hexime.ime.data.RimeDataInstaller
import com.hexime.ime.engine.RimeEngine
import java.util.concurrent.Executors

/** 设置/引导页。 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var vibValue: TextView
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }

        statusView = TextView(this).apply { textSize = 15f }

        root.addView(TextView(this).apply { text = "Hexime 输入法"; textSize = 24f })
        root.addView(statusView)

        root.addView(Button(this).apply {
            text = "1. 启用 Hexime（系统输入法设置）"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "2. 选择 Hexime 为当前输入法"
            setOnClickListener {
                getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            }
        })
        root.addView(Button(this).apply {
            text = "重新部署词库（首次较慢）"
            setOnClickListener { redeploy() }
        })

        // ---- 震动 ----
        root.addView(sectionTitle("\n震动强度"))
        vibValue = TextView(this).apply { textSize = 15f }
        root.addView(vibValue)

        val seek = SeekBar(this).apply {
            max = 100
            progress = HeximeSettings.vibrationPercent(this@MainActivity)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                HeximeSettings.setVibrationPercent(this@MainActivity, value)
                updateVibLabel(value)
                if (fromUser) previewVibration(value)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        root.addView(seek)
        updateVibLabel(seek.progress)

        root.addView(Button(this).apply {
            text = "试一下震动"
            setOnClickListener { previewVibration(HeximeSettings.vibrationPercent(this@MainActivity)) }
        })

        // ---- 跟手延迟显示 ----
        root.addView(CheckBox(this).apply {
            text = "在输入法状态栏显示「跟手延迟」(按键→下一帧)"
            isChecked = HeximeSettings.showLatency(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                HeximeSettings.setShowLatency(this@MainActivity, checked)
            }
        })

        // ---- 测试输入 ----
        root.addView(sectionTitle("\n测试输入（点下面输入框调出键盘）"))
        root.addView(EditText(this).apply {
            hint = "在这里输入…"
            minLines = 2
        })

        setContentView(root)
        refreshStatus()
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
    }

    private fun updateVibLabel(percent: Int) {
        vibValue.text = if (percent <= 0) "震动：关闭" else "震动：$percent%"
    }

    private fun previewVibration(percent: Int) {
        if (percent <= 0) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        } ?: return
        val amplitude = (percent / 100.0 * 255).toInt().coerceIn(1, 255)
        runCatching { vibrator.vibrate(VibrationEffect.createOneShot(35L, amplitude)) }
    }

    private fun refreshStatus() {
        io.execute {
            val shared = RimeDataInstaller.ensureInstalled(this)
            val count = shared.listFiles()?.size ?: 0
            val version = runCatching { RimeEngine().version() }.getOrDefault("?")
            main.post {
                statusView.text =
                    "librime: $version\n数据文件: $count 个\n目录: ${shared.absolutePath}"
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