package com.hexime.ime

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.hexime.ime.data.HeximeSettings
import com.hexime.ime.data.GithubSync
import com.hexime.ime.data.RimeDataInstaller
import com.hexime.ime.data.Schemas
import com.hexime.ime.data.SyncPrefs
import com.hexime.ime.engine.RimeEngine
import com.hexime.ime.ui.Haptics
import com.hexime.ime.ui.HeximePalettes
import java.util.concurrent.Executors

/**
 * 设置页 —— Material 3 风格（动态取色）。
 *
 * 刻意**不引 com.google.android.material**：那个库给 APK 加 ~1.5MB（几乎全是资源），
 * 打开设置页时还会多几 MB 运行时开销（MaterialButton/TextInputLayout 这类控件比原生重）。
 * 这里用系统动态色（Android 12+ 的 system_accent1_* / system_neutral1_*，就是 Material You 那套
 * tonal palette）+ 圆角卡片 + Switch/SeekBar 自己搭，观感一致、零额外依赖。
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var vibValue: TextView
    private lateinit var heightValue: TextView
    private lateinit var syncStatus: TextView
    private lateinit var schemaSpinner: Spinner
    private lateinit var paletteSpinner: Spinner
    private lateinit var heightSeek: SeekBar
    private lateinit var vibSeek: SeekBar
    private lateinit var latencySwitch: Switch

    /** 本页改过设置（离开时自动推一次；不推的话下次自动拉会把本机改动冲掉）。 */
    private var settingsDirty = false
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var c: M3

    /** Material 3 的一小组颜色角色（够这个页面用）。 */
    private class M3(
        val accent: Int,
        val onAccent: Int,
        val surface: Int,
        val surfaceContainer: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
        val outline: Int,
    )

    override fun onStop() {
        super.onStop()
        // 离开设置页时把本机改动推上去：不然下次「打开自动拉」会把刚改的配色/高度冲回旧值
        if (settingsDirty && SyncPrefs.token(this).isNotBlank()) {
            settingsDirty = false
            io.execute {
                val r = runCatching { GithubSync.upload(this) }
                    .getOrElse { GithubSync.Result(false, "异常：${it.message}") }
                main.post {
                    SyncPrefs.setLastSync(
                        this,
                        "${if (r.ok) "离开时自动上传" else "自动上传失败"}：${r.message}",
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        c = m3Colors()
        window.statusBarColor = c.surface
        window.navigationBarColor = c.surface

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(32))
            setBackgroundColor(c.surface)
        }

        // ---------------- 头部 ----------------
        root.addView(TextView(this).apply {
            text = "Hexime 输入法"
            textSize = 26f
            setTextColor(c.onSurface)
            setTypeface(typeface, Typeface.BOLD)
        })
        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(c.onSurfaceVariant)
            setPadding(0, dp(6), 0, dp(4))
        }
        root.addView(statusView)

        // ---------------- 卡片：开始使用 ----------------
        root.addView(card("开始使用") { box ->
            box.addView(filledButton("启用 Hexime（系统输入法设置）") {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            })
            box.addView(filledButton("选择 Hexime 为当前输入法") {
                getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            })
            box.addView(textButton("重新部署词库（首次较慢）") { redeploy() })
        })

        // ---------------- 卡片：外观 ----------------
        root.addView(card("外观") { box ->
            box.addView(sectionLabel("方案（也可以点选单里的「方案」键循环切）"))
            val schemaIds = Schemas.ALL
            schemaSpinner = Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    schemaIds.map { it.second },
                )
                setSelection(
                    schemaIds.indexOfFirst { it.first == HeximeSettings.schemaId(this@MainActivity) }
                        .coerceAtLeast(0),
                )
                background = rounded(c.surfaceContainer, 12f, c.outline, 1)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        HeximeSettings.setSchemaId(this@MainActivity, schemaIds[position].first)
                        settingsDirty = true
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
            box.addView(schemaSpinner)

            box.addView(sectionLabel("配色（换完下次弹键盘生效）"))
            val palettes = HeximePalettes.ALL
            paletteSpinner = Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    palettes.map { it.name },
                )
                setSelection(
                    palettes.indexOfFirst { it.id == HeximeSettings.paletteId(this@MainActivity) }
                        .coerceAtLeast(0),
                )
                background = rounded(c.surfaceContainer, 12f, c.outline, 1)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        HeximeSettings.setPaletteId(this@MainActivity, palettes[position].id)
                        settingsDirty = true
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
            box.addView(paletteSpinner)

            box.addView(sectionLabel("键盘高度（总高，各键盘页等高）"))
            heightValue = TextView(this).apply {
                textSize = 14f
                setTextColor(c.onSurfaceVariant)
            }
            box.addView(heightValue)
            heightSeek = SeekBar(this).apply {
                max = HeximeSettings.KEYBOARD_HEIGHT_MAX - HeximeSettings.KEYBOARD_HEIGHT_MIN
                progress = HeximeSettings.keyboardHeightDp(this@MainActivity) -
                    HeximeSettings.KEYBOARD_HEIGHT_MIN
                tint(c.accent)
            }
            heightSeek.setOnSeekBarChangeListener(object : SimpleSeek() {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    HeximeSettings.setKeyboardHeightDp(
                        this@MainActivity,
                        value + HeximeSettings.KEYBOARD_HEIGHT_MIN,
                    )
                    if (fromUser) settingsDirty = true
                    updateHeightLabel(value)
                }
            })
            box.addView(heightSeek)
            updateHeightLabel(heightSeek.progress)
        })

        // ---------------- 卡片：手感 ----------------
        root.addView(card("手感") { box ->
            box.addView(sectionLabel("震动强度"))
            vibValue = TextView(this).apply {
                textSize = 14f
                setTextColor(c.onSurfaceVariant)
            }
            box.addView(vibValue)
            vibSeek = SeekBar(this).apply {
                max = 100
                progress = HeximeSettings.vibrationPercent(this@MainActivity)
                tint(c.accent)
            }
            vibSeek.setOnSeekBarChangeListener(object : SimpleSeek() {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    HeximeSettings.setVibrationPercent(this@MainActivity, value)
                    if (fromUser) settingsDirty = true
                    updateVibLabel(value)
                    if (fromUser) previewVibration(value)
                }
            })
            box.addView(vibSeek)
            updateVibLabel(vibSeek.progress)
            box.addView(textButton("试一下震动") {
                previewVibration(HeximeSettings.vibrationPercent(this@MainActivity))
            })

            latencySwitch = Switch(this).apply {
                isChecked = HeximeSettings.showLatency(this@MainActivity)
            }
            box.addView(
                switchRow(
                    "显示「跟手延迟」",
                    "按键到下一帧的耗时，调优指标；默认关（开着每键多一次 Choreographer 回调）",
                    latencySwitch,
                ) { checked ->
                    HeximeSettings.setShowLatency(this@MainActivity, checked)
                    settingsDirty = true
                },
            )
        })

        // ---------------- 卡片：同步 ----------------
        root.addView(card("同步（GitHub）") { box ->
            box.addView(
                sectionLabel(
                    "自定义词表 + 设置 + 额外方案词库 ↔ 你自己的私有仓库" +
                        "（走 GitHub 接口直连，不经第三方）。加词后会自动上传；也可以随时手动点下面两个按钮。",
                ),
            )
            syncStatus = TextView(this).apply {
                textSize = 13f
                setTextColor(c.onSurfaceVariant)
                setPadding(0, 0, 0, dp(4))
            }
            box.addView(syncStatus)
            updateSyncStatus()

            val tokenView = EditText(this).apply {
                hint = "GitHub token（只存在本机，不会跟着设置一起上传）"
                setText(SyncPrefs.token(this@MainActivity))
                textSize = 14f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setTextColor(c.onSurface)
                setHintTextColor(c.onSurfaceVariant)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(c.surfaceContainer, 12f, c.outline, 1)
            }
            box.addView(tokenView)

            val repoView = EditText(this).apply {
                hint = "仓库：owner/name（只填仓库名也行，会用 token 的账号补上）"
                setText(SyncPrefs.repo(this@MainActivity))
                textSize = 14f
                inputType = InputType.TYPE_CLASS_TEXT
                setTextColor(c.onSurface)
                setHintTextColor(c.onSurfaceVariant)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(c.surfaceContainer, 12f, c.outline, 1)
            }
            box.addView(repoView)

            fun saveCreds() {
                SyncPrefs.setToken(this@MainActivity, tokenView.text.toString())
                SyncPrefs.setRepo(this@MainActivity, repoView.text.toString())
            }
            box.addView(
                sectionLabel(
                    "规则：本地优先。词表取并集（两边加的词都不丢），设置整体覆盖。" +
                        "你在这页改过东西、离开时会自动上传；云端内容只在「本机还没拉过」时自动应用一次，" +
                        "之后想用云端覆盖本机，请点「下载」。",
                ),
            )
            box.addView(filledButton("上传（本机 → GitHub）") {
                saveCreds()
                runSync(upload = true)
            })
            box.addView(textButton("下载（用云端覆盖本机）") {
                saveCreds()
                runSync(upload = false)
            })
        })

        // ---------------- 卡片：测试输入 ----------------
        root.addView(card("测试输入") { box ->
            box.addView(sectionLabel("点下面输入框调出键盘"))
            box.addView(EditText(this).apply {
                hint = "在这里输入…"
                minLines = 3
                textSize = 16f
                setTextColor(c.onSurface)
                setHintTextColor(c.onSurfaceVariant)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(c.surfaceContainer, 16f, c.outline, 1)
            })
        })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(c.surface)
            // 自己可聚焦：否则「测试输入」的 EditText 会自动抢焦点，一进设置页键盘就弹出来盖住半页
            isFocusableInTouchMode = true
            addView(root)
        }
        setContentView(scroll)
        scroll.requestFocus()
        refreshStatus()
        autoPullOnOpen()
    }

    /**
     * 打开设置页时自动拉一次（只拉不推）。拉完把控件刷成新值。
     * 没配 token 就静默跳过 —— 没打算同步的人不该看到任何网络行为。
     */
    private fun autoPullOnOpen() {
        if (SyncPrefs.token(this).isBlank()) return
        // 只在「本机还没从云端拉过」时才应用（首次安装 / 换机恢复）。
        // 以前每次打开设置页都无脑覆盖本地，用户刚改的震动强度/配色会被云端旧值冲回去
        // （用户反馈「设置页设置了没有改动，怀疑被 GitHub 备份还原」）。
        // 想主动用云端覆盖本地 → 点下面的「下载」按钮。
        if (SyncPrefs.firstPullDone(this)) {
            syncStatus.text = SyncPrefs.lastSync(this)
            return
        }
        io.execute {
            val result = runCatching { GithubSync.download(this) }
                .getOrElse { GithubSync.Result(false, "异常：${it.message}") }
            main.post {
                SyncPrefs.setLastSync(this, "${if (result.ok) "自动拉取" else "自动拉取失败"}：${result.message}")
                syncStatus.text = result.message
                if (result.ok) reloadControlsFromSettings()
            }
        }
    }

    /** 把各控件刷成当前设置值（拉取同步后用；监听器写回的是同一个值，不会打架）。 */
    private fun reloadControlsFromSettings() {
        schemaSpinner.setSelection(
            Schemas.ALL.indexOfFirst { it.first == HeximeSettings.schemaId(this) }.coerceAtLeast(0),
        )
        paletteSpinner.setSelection(
            HeximePalettes.ALL.indexOfFirst { it.id == HeximeSettings.paletteId(this) }.coerceAtLeast(0),
        )
        heightSeek.progress = HeximeSettings.keyboardHeightDp(this) - HeximeSettings.KEYBOARD_HEIGHT_MIN
        vibSeek.progress = HeximeSettings.vibrationPercent(this)
        latencySwitch.isChecked = HeximeSettings.showLatency(this)
    }

    // ------------------------------------------------------------ 组件

    /** 圆角卡片（M3 的 surface container + 20dp 圆角）。 */
    private fun card(title: String, build: (LinearLayout) -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(c.surfaceContainer, 20f, 0)
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 13f
            letterSpacing = 0.06f
            setTextColor(c.accent)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })
        build(box)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(box)
        }
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(c.onSurfaceVariant)
        setPadding(0, dp(10), 0, dp(4))
    }

    /** M3 填充按钮（胶囊形 + 涟漪）。 */
    private fun filledButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 15f
        isAllCaps = false
        setTextColor(c.onAccent)
        background = ripple(c.accent, c.accent, 999f)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
    }

    /** M3 文字按钮。 */
    private fun textButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 14f
        isAllCaps = false
        setTextColor(c.accent)
        background = ripple(c.accent, Color.TRANSPARENT, 999f)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) }
    }

    /** 标题 + 副标题 + 右侧开关的一行。 */
    /** 开关行的开关由调用方建好传进来（这样设置页拉取后能直接改它的状态）。 */
    private fun switchRow(
        title: String,
        subtitle: String,
        switchView: Switch,
        onChange: (Boolean) -> Unit,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, 0)
        val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 16f
            setTextColor(c.onSurface)
        })
        texts.addView(TextView(this@MainActivity).apply {
            text = subtitle
            textSize = 12f
            setTextColor(c.onSurfaceVariant)
        })
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        switchView.thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(c.accent, c.outline),
        )
        switchView.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(withAlpha(c.accent, 0.5f), withAlpha(c.outline, 0.4f)),
        )
        switchView.setOnCheckedChangeListener { _, value -> onChange(value) }
        addView(switchView)
    }

    private abstract class SimpleSeek : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    // ------------------------------------------------------------ 颜色 / 形状

    /** 优先用系统动态色（Material You）；Android 12 以下退回 M3 基线配色。 */
    private fun m3Colors(): M3 {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            fun sys(id: Int) = getColor(id)
            return if (night) M3(
                accent = sys(android.R.color.system_accent1_200),
                onAccent = sys(android.R.color.system_accent1_900),
                surface = sys(android.R.color.system_neutral1_900),
                surfaceContainer = sys(android.R.color.system_neutral1_800),
                onSurface = sys(android.R.color.system_neutral1_50),
                onSurfaceVariant = sys(android.R.color.system_neutral2_200),
                outline = sys(android.R.color.system_neutral2_600),
            ) else M3(
                accent = sys(android.R.color.system_accent1_600),
                onAccent = Color.WHITE,
                surface = sys(android.R.color.system_neutral1_50),
                surfaceContainer = sys(android.R.color.system_neutral1_100),
                onSurface = sys(android.R.color.system_neutral1_900),
                onSurfaceVariant = sys(android.R.color.system_neutral2_700),
                outline = sys(android.R.color.system_neutral2_400),
            )
        }
        // Android 12 以下没有动态色 → M3 基线（light/dark）
        return if (night) M3(
            0xFFA8C7FA.toInt(), 0xFF062E6F.toInt(), 0xFF1B1B1F.toInt(), 0xFF2B2930.toInt(),
            0xFFE6E1E9.toInt(), 0xFFCAC4D0.toInt(), 0xFF938F99.toInt(),
        ) else M3(
            0xFF0B57D0.toInt(), Color.WHITE, 0xFFFEF7FF.toInt(), 0xFFF3EDF7.toInt(),
            0xFF1D1B20.toInt(), 0xFF49454F.toInt(), 0xFF79747E.toInt(),
        )
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int, strokeDp: Int = 0) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            if (stroke != 0 && strokeDp > 0) setStroke(dp(strokeDp), stroke)
        }

    private fun ripple(rippleColor: Int, fill: Int, radiusDp: Float) = RippleDrawable(
        ColorStateList.valueOf(withAlpha(rippleColor, 0.16f)),
        if (fill == Color.TRANSPARENT) null else rounded(fill, radiusDp, 0),
        if (fill == Color.TRANSPARENT) rounded(Color.WHITE, radiusDp, 0) else null,
    )

    private fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (255 * alpha).toInt().coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private fun SeekBar.tint(color: Int) {
        progressTintList = ColorStateList.valueOf(color)
        thumbTintList = ColorStateList.valueOf(color)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------ 逻辑

    private fun updateSyncStatus() {
        syncStatus.text = SyncPrefs.lastSync(this).ifEmpty { "还没同步过" }
    }

    /** 同步（后台线程，结果回到设置页显示）。 */
    private fun runSync(upload: Boolean) {
        syncStatus.text = if (upload) "上传中…" else "下载中…"
        io.execute {
            val result = runCatching {
                if (upload) GithubSync.upload(this) else GithubSync.download(this)
            }.getOrElse { GithubSync.Result(false, "异常：${it.message}") }
            main.post {
                val line = "${if (result.ok) "上次同步" else "上次失败"}：${result.message}"
                SyncPrefs.setLastSync(this, line)
                syncStatus.text = result.message
            }
        }
    }

    private fun updateVibLabel(percent: Int) {
        vibValue.text = if (percent <= 0) "震动：关闭" else "震动：$percent%"
    }

    private fun updateHeightLabel(progress: Int) {
        heightValue.text = "键盘高度：${progress + HeximeSettings.KEYBOARD_HEIGHT_MIN} dp"
    }

    private fun previewVibration(percent: Int) {
        val effect = Haptics.effect(percent) ?: return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        } ?: return
        // 和键盘里按键用同一个波形，所见即所得
        runCatching { vibrator.vibrate(effect) }
    }

    private fun refreshStatus() {
        io.execute {
            // 后台线程里抛异常会直接杀掉整个进程（一打开就闪退），所以这里必须兜住
            val text = runCatching {
                val shared = RimeDataInstaller.ensureInstalled(this)
                val count = shared.listFiles()?.size ?: 0
                val version = runCatching { RimeEngine().version() }.getOrDefault("?")
                "librime $version · 数据文件 $count 个\n${shared.absolutePath}"
            }.getOrElse { "初始化失败：${it.message}" }
            main.post { statusView.text = text }
        }
    }

    private fun redeploy() {
        statusView.text = "正在重新部署…"
        io.execute {
            val text = runCatching {
                val shared = RimeDataInstaller.ensureInstalled(this)
                val user = RimeDataInstaller.userDir(this).apply { mkdirs() }
                val log = RimeDataInstaller.logDir(this).apply { mkdirs() }
                val engine = RimeEngine()
                if (!engine.initialize(shared.absolutePath, user.absolutePath, log.absolutePath)) {
                    return@runCatching "部署失败：初始化引擎出错"
                }
                val t0 = System.currentTimeMillis()
                engine.deploy(true)
                val ms = System.currentTimeMillis() - t0
                engine.shutdown()
                "部署完成：${ms} ms"
            }.getOrElse { "部署失败：${it.message}" }
            main.post { statusView.text = text }
        }
    }
}
