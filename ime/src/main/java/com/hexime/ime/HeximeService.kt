package com.hexime.ime

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.hexime.ime.data.HeximeSettings
import com.hexime.ime.data.RimeDataInstaller
import com.hexime.ime.engine.InputEngine
import com.hexime.ime.engine.RimeEngine
import com.hexime.ime.ui.CandidateBar
import com.hexime.ime.ui.HeximeTheme
import com.hexime.ime.ui.KeyAction
import com.hexime.ime.ui.KeyboardView
import java.util.concurrent.Executors

class HeximeService : InputMethodService() {

    private val engine: InputEngine = RimeEngine()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var ready = false

    /** 顶部一行左边：当前编码（组合串）。 */
    private var codeView: TextView? = null

    /** 顶部一行右边：跟手延迟（设置里开了才显示）。 */
    private var latencyView: TextView? = null

    private var candidateBar: CandidateBar? = null
    private var keyboardView: KeyboardView? = null

    /** 可用方案：小鹤音形 / 拼音。 */
    private val schemas = listOf(SCHEMA_OPENFLY, "luna_pinyin")
    private var schemaIndex = 0

    /** 反查状态：组合串以 ` 开头（由候选栏「反查」按钮插入）。 */
    private var reverseLookup = false

    private var shiftOn = false
    private var asciiMode = false

    /** 最近一次按键到下一帧的耗时（ms），用于评估「跟手性」。 */
    private var latencyMs = 0.0

    // ------- 缓存：避免打字热路径上反复做 JNI 调用 / 字符串拼接 / prefs 读取 -------

    /** librime 版本号，进程内不会变，只在引擎启动后取一次。 */
    private var rimeVersion = ""

    /** 当前方案名，只在建会话/切方案时更新。 */
    private var schemaName = ""

    /** 顶部编码行上一次的文本：内容没变就不 setText（省一次 measure/layout/draw）。 */
    private var lastCode = ""

    /** 顶部右侧跟手延迟上一次的文本。 */
    private var lastLatency = ""

    /** 当前编码（组合串），显示在顶部那一行。 */
    private var composition = ""

    /** 跟手延迟的显示文本，数值变化时才重算。 */
    private var latencyText = ""

    /** 是否显示跟手延迟（缓存 prefs，切换时更新）。 */
    private var showLatency = false

    /** 已有一帧回调在排队（合并同帧内的多次按键测量请求）。 */
    private var latencyPending = false

    /** 建会话是否已排队，避免主线程阻塞在 JNI 上。 */
    private var sessionPending = false

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
    }

    /** 用于判断系统深色模式是否变化（变化时重建输入视图换配色）。 */
    private var lastNight = false

    override fun onCreate() {
        super.onCreate()
        showLatency = HeximeSettings.showLatency(this)
        lastNight = HeximeTheme.isNight(this)
        bootstrapEngine()
    }

    /** 系统切深浅色时重建输入视图（配色跟着走）。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val night = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (night != lastNight) {
            lastNight = night
            appliedKeyHeight = -1 // 让下次 onStartInputView 也按新配色重建
            lastCode = ""
            lastLatency = ""
            codeView = null
            latencyView = null
            setInputView(onCreateInputView())
            refresh()
        }
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onCreateInputView(): View {
        val palette = HeximeTheme.of(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.keyboardBg)
        }

        // 顶部一行：左边显示当前编码，右边显示跟手延迟（可选）。
        // 高度写死，编码从无到有也不会让键盘上下跳。
        codeView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(palette.code)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(8), 0)
        }
        latencyView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(palette.statusText)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(10), 0)
        }
        val codeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(palette.statusBg)
            // 点这一行切换跟手延迟显示
            isClickable = true
            setOnClickListener {
                showLatency = !showLatency
                HeximeSettings.setShowLatency(this@HeximeService, showLatency)
                lastLatency = "" // 强制刷新
                updateCodeRow()
            }
            addView(codeView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(latencyView)
        }
        candidateBar = CandidateBar(this).apply {
            onSelect = { index -> onSelectCandidate(index) }
            onPageUp = { onKeyAction(KeyAction.Sym(InputEngine.KEY_PAGE_UP)) }
            onPageDown = { onKeyAction(KeyAction.Sym(InputEngine.KEY_PAGE_DOWN)) }
            onReverseLookup = { toggleReverseLookup() }
            setReverseLookupActive(reverseLookup)
        }
        keyboardView = KeyboardView(this).apply {
            onKey = { action -> onKeyAction(action) }
            setMode(asciiMode, schemaName) // 空格键显示方案名/abc
        }

        root.addView(codeRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)))
        root.addView(candidateBar)
        root.addView(keyboardView)
        lastCode = "" // 新 View，必须重设文本
        lastLatency = ""
        updateCodeRow()
        return root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        ensureSessionAsync()
        refresh()
    }

    /** 当前已应用的按键高度，用于设置变化时重建键盘。 */
    private var appliedKeyHeight = -1

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 设置里改了键盘高度则重建输入视图
        val height = HeximeSettings.keyboardHeightDp(this)
        if (height != appliedKeyHeight) {
            appliedKeyHeight = height
            setInputView(onCreateInputView())
        }
        ensureSessionAsync()
        refresh()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        engine.clearComposition()
    }

    override fun onDestroy() {
        engine.shutdown()
        io.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 引擎启动

    private fun bootstrapEngine() {
        io.execute {
            val shared = RimeDataInstaller.ensureInstalled(this)
            val user = RimeDataInstaller.userDir(this).apply { mkdirs() }
            val log = RimeDataInstaller.logDir(this).apply { mkdirs() }
            val ok = engine.initialize(shared.absolutePath, user.absolutePath, log.absolutePath)
            if (ok) {
                // 数据/包没变就不重复部署（旧实现每次进程启动都 deploy 一遍）
                if (RimeDataInstaller.needsDeploy(this)) {
                    val t0 = System.currentTimeMillis()
                    engine.deploy(false)
                    RimeDataInstaller.markDeployed(this)
                    Log.i(TAG, "deploy took ${System.currentTimeMillis() - t0} ms")
                } else {
                    Log.i(TAG, "deploy skipped (data unchanged)")
                }
                rimeVersion = engine.version()
                ready = true
                // 会话也在 io 线程建好（createSession 要加载编译好的词库，别放主线程）
                ensureSession()
                Log.i(TAG, "engine ready, version=$rimeVersion")
            } else {
                Log.e(TAG, "engine initialize failed")
            }
            main.post {
                keyboardView?.setMode(asciiMode, schemaName)
                updateCodeRow()
                refresh()
            }
        }
    }

    /** 在 io 线程创建会话（若还没有），完成后回主线程刷新一次 UI。 */
    private fun ensureSessionAsync() {
        if (!ready || engine.hasSession() || sessionPending) return
        sessionPending = true
        io.execute {
            ensureSession()
            main.post {
                sessionPending = false
                keyboardView?.setMode(asciiMode, schemaName)
                updateCodeRow()
                refresh()
            }
        }
    }

    private fun ensureSession() {
        if (!ready) return
        if (!engine.hasSession()) {
            engine.createSession(schemas[schemaIndex])
            schemaName = engine.currentSchema()
        }
    }

    // ---------------------------------------------------------------- 按键

    private fun onKeyAction(action: KeyAction) {
        if (!ready) return
        haptic()
        val t0 = System.nanoTime()
        when (action) {
            is KeyAction.Sym -> {
                val handled = engine.processKey(action.keySym, action.mask)
                // 引擎未处理且是可打印 ASCII，则直接上屏（数字/符号/英文）
                if (!handled && action.keySym in 0x20..0x7E) {
                    currentInputConnection?.commitText(action.keySym.toChar().toString(), 1)
                }
            }
            KeyAction.Backspace -> {
                val handled = engine.processKey(InputEngine.KEY_BACKSPACE, 0)
                // 没有组合串时交给编辑器删除字符
                if (!handled) currentInputConnection?.deleteSurroundingText(1, 0)
            }
            KeyAction.Enter -> {
                val handled = engine.processKey(InputEngine.KEY_RETURN, 0)
                if (!handled) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
            KeyAction.ToggleSymbols -> {
                keyboardView?.toggleSymbols()
            }
            KeyAction.Shift -> {
                shiftOn = !shiftOn
                keyboardView?.setShift(shiftOn)
            }
            KeyAction.ToggleAscii -> {
                asciiMode = !asciiMode
                engine.setOption("ascii_mode", asciiMode)
                // 空格键文字：中文显示方案名，英文显示 abc
                keyboardView?.setMode(asciiMode, schemaName)
            }
            KeyAction.SwitchSchema -> {
                schemaIndex = (schemaIndex + 1) % schemas.size
                engine.selectSchema(schemas[schemaIndex])
                schemaName = engine.currentSchema()
                keyboardView?.setMode(asciiMode, schemaName)
            }
        }
        if (action is KeyAction.Sym && shiftOn) {
            shiftOn = false
            keyboardView?.setShift(false)
        }
        refresh()
        measureLatency(t0)
    }

    private fun onSelectCandidate(index: Int) {
        if (!ready) return
        engine.selectCandidate(index)
        refresh()
    }

    /**
     * 反查：在**当前方案**里插入反查引导符 `（方案里配了 reverse_lookup 处理器，
     * 后面打拼音就能出字并显示音形码），不切方案。
     * 已经在组合中则清空，相当于取消。
     */
    private fun toggleReverseLookup() {
        if (!ready) return
        val key = if (composition.isEmpty()) InputEngine.KEY_GRAVE else InputEngine.KEY_ESCAPE
        onKeyAction(KeyAction.Sym(key))
    }

    private fun refresh() {
        if (!ready) {
            updateCodeRow() // 显示「部署中…」
            return
        }
        val commit = engine.takeCommit()
        val snapshot = engine.snapshot()
        composition = snapshot.composition
        // 组合串以 ` 开头 = 正在反查，点亮候选栏的「反查」按钮
        reverseLookup = composition.startsWith("`")
        candidateBar?.setReverseLookupActive(reverseLookup)
        candidateBar?.setCandidates(snapshot.candidates)
        updateCodeRow()

        val ic = currentInputConnection ?: return
        if (!commit.isNullOrEmpty()) {
            ic.commitText(commit, 1)
        }
        ic.setComposingText(snapshot.composition, 1)
    }

    /** 顶部一行：左边当前编码，右边跟手延迟。内容没变就不 setText。 */
    private fun updateCodeRow() {
        val code = codeView ?: return
        val text = if (!ready) "部署中…（首次会编译词库）" else composition
        if (text != lastCode) {
            lastCode = text
            code.text = text
        }
        val latency =
            if (showLatency && ready && latencyText.isNotEmpty()) "跟手 $latencyText" else ""
        if (latency != lastLatency) {
            lastLatency = latency
            latencyView?.text = latency
        }
    }

    private fun haptic() {
        val percent = HeximeSettings.vibrationPercent(this)
        if (percent <= 0) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val amplitude = (percent / 100.0 * 255).toInt().coerceIn(1, 255)
        runCatching { v.vibrate(VibrationEffect.createOneShot(12L, amplitude)) }
    }

    /** 记录「按键 -> 下一帧渲染」的耗时，作为跟手性的量化指标。 */
    private fun measureLatency(t0: Long) {
        if (!showLatency || latencyPending) return // 同帧内只留一个回调
        latencyPending = true
        Choreographer.getInstance().postFrameCallback { frameTimeNanos ->
            latencyPending = false
            val ms = (frameTimeNanos - t0) / 1_000_000.0
            // 显示到 0.1ms，数值没变就不重排版状态栏
            if (latencyText.isEmpty() || Math.abs(ms - latencyMs) >= 0.05) {
                latencyMs = ms
                latencyText = "%.1f ms".format(ms)
                updateCodeRow()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "HeximeService"
        const val SCHEMA_OPENFLY = "openfly"
    }
}
