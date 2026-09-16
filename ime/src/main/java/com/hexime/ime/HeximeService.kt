package com.hexime.ime

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import com.hexime.ime.ui.KeyAction
import com.hexime.ime.ui.KeyboardView
import java.util.concurrent.Executors

class HeximeService : InputMethodService() {

    private val engine: InputEngine = RimeEngine()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var ready = false

    private var statusView: TextView? = null
    private var candidateBar: CandidateBar? = null
    private var keyboardView: KeyboardView? = null

    private val schemas = listOf("openfly", "luna_pinyin")
    private var schemaIndex = 0

    private var shiftOn = false
    private var asciiMode = false

    /** 最近一次按键到下一帧的耗时（ms），用于评估「跟手性」。 */
    private var latencyMs = 0.0

    // ------- 缓存：避免打字热路径上反复做 JNI 调用 / 字符串拼接 / prefs 读取 -------

    /** librime 版本号，进程内不会变，只在引擎启动后取一次。 */
    private var rimeVersion = ""

    /** 当前方案名，只在建会话/切方案时更新。 */
    private var schemaName = ""

    /** 状态栏上一次显示的完整文本：内容没变就不 setText（省一次 measure/layout/draw）。 */
    private var lastStatus = ""

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

    override fun onCreate() {
        super.onCreate()
        showLatency = HeximeSettings.showLatency(this)
        bootstrapEngine()
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#607D8B"))
            setBackgroundColor(Color.parseColor("#ECEFF1"))
            setPadding(dp(10), dp(3), dp(10), dp(3))
            // 点状态栏切换跟手延迟显示
            isClickable = true
            setOnClickListener {
                showLatency = !showLatency
                HeximeSettings.setShowLatency(this@HeximeService, showLatency)
                lastStatus = "" // 强制刷新一次
                updateStatus()
            }
        }
        candidateBar = CandidateBar(this).apply {
            onSelect = { index -> onSelectCandidate(index) }
        }
        keyboardView = KeyboardView(this).apply {
            onKey = { action -> onKeyAction(action) }
        }

        root.addView(statusView)
        root.addView(candidateBar)
        root.addView(keyboardView)
        lastStatus = "" // 新 View，必须重设文本
        updateStatus()
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
                updateStatus()
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
            }
            KeyAction.SwitchSchema -> {
                schemaIndex = (schemaIndex + 1) % schemas.size
                engine.selectSchema(schemas[schemaIndex])
                schemaName = engine.currentSchema()
                lastStatus = "" // 方案名变了，强制刷新状态栏
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

    private fun refresh() {
        updateStatus()
        if (!ready) return
        val commit = engine.takeCommit()
        val snapshot = engine.snapshot()
        candidateBar?.setCandidates(snapshot.candidates)

        val ic = currentInputConnection ?: return
        if (!commit.isNullOrEmpty()) {
            ic.commitText(commit, 1)
        }
        ic.setComposingText(snapshot.composition, 1)
    }

    private fun updateStatus() {
        val bar = statusView ?: return
        val text = when {
            !ready -> "部署中…（首次会编译词库）"
            else -> buildString {
                append(schemaName.ifEmpty { "—" })
                append("  ·  ")
                append(if (asciiMode) "英" else "中")
                append("  ·  librime ")
                append(rimeVersion)
                if (showLatency) {
                    append("  ·  跟手 ")
                    append(latencyText.ifEmpty { "—" })
                }
            }
        }
        if (text == lastStatus) return // 内容没变：不 setText，省 measure/layout/draw
        lastStatus = text
        bar.text = text
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
                updateStatus()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "HeximeService"
    }
}
