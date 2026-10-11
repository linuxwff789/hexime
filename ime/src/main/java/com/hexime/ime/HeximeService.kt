package com.hexime.ime

import android.content.Intent
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

    /** 简体/繁体（小鹤选单的「简繁」键）。 */
    private var simplified = false

    /** 当前组合串（键盘侧判断 `.` 键是否要当次选用）。 */
    private var composition = ""

    /** 最近一次按键到下一帧的耗时（ms），用于评估「跟手性」。 */
    private var latencyMs = 0.0

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
                val next = !HeximeSettings.showLatency(this@HeximeService)
                HeximeSettings.setShowLatency(this@HeximeService, next)
                updateStatus()
            }
        }
        candidateBar = CandidateBar(this).apply {
            onSelect = { index -> onSelectCandidate(index) }
        }
        keyboardView = KeyboardView(this).apply {
            onKey = { action -> onKeyAction(action) }
            // 小鹤的 `.` 键：组合中 = 次选第 2 候选
            isComposing = { composition.isNotEmpty() }
        }

        root.addView(statusView)
        root.addView(candidateBar)
        root.addView(keyboardView)
        updateStatus()
        return root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        ensureSession()
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
        ensureSession()
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
                engine.deploy(false)
                ready = true
                Log.i(TAG, "engine ready, version=${engine.version()}")
            } else {
                Log.e(TAG, "engine initialize failed")
            }
            main.post {
                updateStatus()
                ensureSession()
                refresh()
            }
        }
    }

    private fun ensureSession() {
        if (!ready) return
        if (!engine.hasSession()) {
            engine.createSession(schemas[schemaIndex])
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
            is KeyAction.Named -> onNamedKey(action.name)
            is KeyAction.Ctrl -> onCtrlKey(action.ch)
            is KeyAction.Text -> sendKeyMacro(action.text)
            is KeyAction.Kb -> {
                keyboardView?.showKeyboard(action.id)
                syncAsciiFromKeyboard()
            }
            is KeyAction.Toggle -> onToggle(action.option)
            is KeyAction.Cand -> {
                // 小鹤的 delimiterJ/S = 次选/三选（第 2/3 候选 → 引擎 0-based 索引 1/2）
                if (composition.isNotEmpty() && action.index >= 1) {
                    engine.selectCandidate(action.index - 1)
                }
            }
            is KeyAction.Cmd -> onCommand(action.name, action.option)
            KeyAction.None -> Unit
        }
        if (action is KeyAction.Sym && shiftOn) {
            shiftOn = false
            keyboardView?.setShift(false)
        }
        refresh()
        measureLatency(t0)
    }

    /** 切换键盘后，把该键盘自带的 ascii_mode（小鹤键盘文件里的字段）同步给引擎。 */
    private fun syncAsciiFromKeyboard() {
        val on = keyboardView?.asciiOn() ?: return
        if (on == asciiMode) return
        asciiMode = on
        engine.setOption("ascii_mode", on)
    }

    // ---- 具名功能键 / Ctrl / 按键宏 ----

    /** 具名功能键：先给引擎（它能处理翻页/光标），引擎不吃就交给编辑器。 */
    private fun onNamedKey(name: String) {
        when (name) {
            "Shift_L", "Shift_R" -> {
                shiftOn = !shiftOn
                keyboardView?.setShift(shiftOn)
                return
            }
            "copy" -> return contextAction(android.R.id.copy)
            "cut" -> return contextAction(android.R.id.cut)
            "paste" -> return contextAction(android.R.id.paste)
            "select_all" -> return contextAction(android.R.id.selectAll)
            "undo" -> return sendKeyWithMeta(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
            "redo" -> return sendKeyWithMeta(KeyEvent.KEYCODE_Y, KeyEvent.META_CTRL_ON)
        }
        val keysym = NAMED_KEYSYM[name]
        if (keysym != null) {
            val handled = engine.processKey(keysym, 0)
            if (handled) return
        } else if (name != "space") {
            return // hexime 没有的键（Menu / SETTINGS / VOICE_ASSIST ...）静默忽略
        }
        val code = NAMED_KEYCODE[name] ?: return
        sendDownUpKeyEvents(code)
    }

    private fun contextAction(id: Int) {
        currentInputConnection?.performContextMenuAction(id)
    }

    private fun onCtrlKey(ch: Char) {
        when (ch.lowercaseChar()) {
            'a' -> contextAction(android.R.id.selectAll)
            'c' -> contextAction(android.R.id.copy)
            'v' -> contextAction(android.R.id.paste)
            'x' -> contextAction(android.R.id.cut)
            'z' -> sendKeyWithMeta(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
            'y' -> sendKeyWithMeta(KeyEvent.KEYCODE_Y, KeyEvent.META_CTRL_ON)
        }
    }

    /** 按键序列宏，如 `{End}{Shift+Home}{Delete}`（小鹤的「Del↔」「复制」等键）。 */
    private fun sendKeyMacro(macro: String) {
        var i = 0
        while (i < macro.length) {
            val open = macro.indexOf('{', i)
            if (open < 0) break
            val close = macro.indexOf('}', open)
            if (close < 0) break
            val parts = macro.substring(open + 1, close).split('+')
            i = close + 1
            var meta = 0
            for (p in parts.dropLast(1)) {
                meta = when (p) {
                    "Shift" -> meta or KeyEvent.META_SHIFT_ON
                    "Control" -> meta or KeyEvent.META_CTRL_ON
                    "Alt" -> meta or KeyEvent.META_ALT_ON
                    else -> meta
                }
            }
            val code = MACRO_KEYCODE[parts.last()] ?: continue
            sendKeyWithMeta(code, meta)
        }
    }

    private fun sendKeyWithMeta(keyCode: Int, meta: Int) {
        val ic = currentInputConnection ?: return
        val now = System.currentTimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    /** Rime 选项开关。 */
    private fun onToggle(option: String) {
        when (option) {
            "ascii_mode" -> {
                asciiMode = !asciiMode
                engine.setOption("ascii_mode", asciiMode)
                keyboardView?.setAscii(asciiMode)
            }
            "simplification" -> {
                simplified = !simplified
                engine.setOption("simplification", simplified)
            }
            // _hide_candidate / _hide_comment / _hide_key_hint / full_shape：hexime 暂无对应
        }
    }

    /** 小鹤主题的脚本类功能键（大部分是 trime2 专属，hexime 只移植了有意义的几个）。 */
    private fun onCommand(name: String, option: String) {
        when (name) {
            "settings" -> startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "kbheight" -> {
                val next = when (option) {
                    "+" -> HeximeSettings.keyboardHeightDp(this) + 4
                    "-" -> HeximeSettings.keyboardHeightDp(this) - 4
                    else -> HeximeSettings.KEYBOARD_HEIGHT_DEFAULT
                }
                HeximeSettings.setKeyboardHeightDp(this, next)
                appliedKeyHeight = HeximeSettings.keyboardHeightDp(this)
                setInputView(onCreateInputView())
            }
            // addword / run / web_search / deploy / date：trime2 专属，暂不移植
        }
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
        composition = snapshot.composition
        candidateBar?.setCandidates(snapshot.candidates)

        val ic = currentInputConnection ?: return
        if (!commit.isNullOrEmpty()) {
            ic.commitText(commit, 1)
        }
        ic.setComposingText(snapshot.composition, 1)
    }

    private fun updateStatus() {
        val bar = statusView ?: return
        bar.text = when {
            !ready -> "部署中…（首次会编译词库）"
            else -> buildString {
                append(
                    "${engine.currentSchema()}  ·  ${if (asciiMode) "英" else "中"}" +
                        "  ·  librime ${engine.version()}",
                )
                if (HeximeSettings.showLatency(this@HeximeService)) {
                    append("  ·  跟手 %.1f ms".format(latencyMs))
                }
            }
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
        if (!HeximeSettings.showLatency(this)) return
        Choreographer.getInstance().postFrameCallback { frameTimeNanos ->
            latencyMs = (frameTimeNanos - t0) / 1_000_000.0
            updateStatus()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "HeximeService"

        /** 具名功能键 → X11 keysym（引擎优先）。 */
        val NAMED_KEYSYM = mapOf(
            "BackSpace" to InputEngine.KEY_BACKSPACE,
            "Return" to InputEngine.KEY_RETURN,
            "space" to InputEngine.KEY_SPACE,
            "Tab" to 0xFF09,
            "Escape" to InputEngine.KEY_ESCAPE,
            "Delete" to 0xFFFF,
            "Up" to 0xFF52,
            "Down" to 0xFF54,
            "Left" to 0xFF51,
            "Right" to 0xFF53,
            "Page_Up" to InputEngine.KEY_PAGE_UP,
            "Page_Down" to InputEngine.KEY_PAGE_DOWN,
            "Home" to 0xFF50,
            "End" to 0xFF57,
        )

        /** 引擎不处理时，交给编辑器的 Android keycode。 */
        val NAMED_KEYCODE = mapOf(
            "BackSpace" to KeyEvent.KEYCODE_DEL,
            "Return" to KeyEvent.KEYCODE_ENTER,
            "space" to KeyEvent.KEYCODE_SPACE,
            "Tab" to KeyEvent.KEYCODE_TAB,
            "Escape" to KeyEvent.KEYCODE_ESCAPE,
            "Delete" to KeyEvent.KEYCODE_FORWARD_DEL,
            "Up" to KeyEvent.KEYCODE_DPAD_UP,
            "Down" to KeyEvent.KEYCODE_DPAD_DOWN,
            "Left" to KeyEvent.KEYCODE_DPAD_LEFT,
            "Right" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "Page_Up" to KeyEvent.KEYCODE_PAGE_UP,
            "Page_Down" to KeyEvent.KEYCODE_PAGE_DOWN,
            "Home" to KeyEvent.KEYCODE_MOVE_HOME,
            "End" to KeyEvent.KEYCODE_MOVE_END,
        )

        /** 按键序列宏里的键名 → Android keycode。 */
        val MACRO_KEYCODE = mapOf(
            "Home" to KeyEvent.KEYCODE_MOVE_HOME,
            "End" to KeyEvent.KEYCODE_MOVE_END,
            "Delete" to KeyEvent.KEYCODE_FORWARD_DEL,
            "BackSpace" to KeyEvent.KEYCODE_DEL,
            "Left" to KeyEvent.KEYCODE_DPAD_LEFT,
            "Right" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "Up" to KeyEvent.KEYCODE_DPAD_UP,
            "Down" to KeyEvent.KEYCODE_DPAD_DOWN,
            "Tab" to KeyEvent.KEYCODE_TAB,
            "Escape" to KeyEvent.KEYCODE_ESCAPE,
            "Return" to KeyEvent.KEYCODE_ENTER,
        )
    }
}
