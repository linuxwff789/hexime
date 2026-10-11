package com.hexime.ime

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import com.hexime.ime.ui.HeximePalettes
import com.hexime.ime.ui.KeyAction
import com.hexime.ime.ui.KeyboardView
import com.hexime.ime.ui.Palette
import java.io.File
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

    /** 最近一次非空组合串：openfly 打满 4 码会自动上屏，加词时组合区可能已经空了。 */
    private var lastCode = ""

    /** 最近一次按键到下一帧的耗时（ms），用于评估「跟手性」。 */
    private var latencyMs = 0.0

    /** 状态栏临时提示的恢复任务。 */
    private var flashRunnable: Runnable? = null

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
        val palette = currentPalette()

        statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(palette.candidateText)
            setBackgroundColor(palette.candidateBg)
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
            // 长按候选 = 快捷加词（置顶）
            onLongSelect = { text -> addWordFromCandidate(text) }
        }
        keyboardView = KeyboardView(this).apply {
            onKey = { action -> onKeyAction(action) }
            // 小鹤的 `.` 键：组合中 = 次选第 2 候选
            isComposing = { composition.isNotEmpty() }
        }

        root.addView(statusView)
        root.addView(candidateBar)
        root.addView(keyboardView)
        // 记住这次视图用的高度/配色，免得 onStartInputView 又重建一遍
        appliedKeyHeight = HeximeSettings.keyboardHeightDp(this)
        appliedPaletteId = HeximeSettings.paletteId(this)
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

    /** 当前已应用的配色 id。 */
    private var appliedPaletteId = ""

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 设置里改了键盘高度/配色则重建输入视图
        val height = HeximeSettings.keyboardHeightDp(this)
        val paletteId = HeximeSettings.paletteId(this)
        if (height != appliedKeyHeight || paletteId != appliedPaletteId) {
            appliedKeyHeight = height
            appliedPaletteId = paletteId
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

    /** 按键序列 / 文本宏，如 `{End}{Shift+Home}{Delete}`、`“”{Left}`。 */
    private fun sendKeyMacro(macro: String) {
        val ic = currentInputConnection ?: return
        val literal = StringBuilder()
        fun flush() {
            if (literal.isNotEmpty()) {
                ic.commitText(literal.toString(), 1)
                literal.setLength(0)
            }
        }
        var i = 0
        while (i < macro.length) {
            val open = macro.indexOf('{', i)
            if (open < 0) {
                literal.append(macro, i, macro.length)
                break
            }
            if (open > i) literal.append(macro, i, open)
            val close = macro.indexOf('}', open)
            if (close < 0) {
                literal.append(macro, open, macro.length)
                break
            }
            flush()
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
        flush()
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
                val step = 16
                val next = when (option) {
                    "+" -> HeximeSettings.keyboardHeightDp(this) + step
                    "-" -> HeximeSettings.keyboardHeightDp(this) - step
                    else -> HeximeSettings.KEYBOARD_HEIGHT_DEFAULT
                }
                HeximeSettings.setKeyboardHeightDp(this, next)
                appliedKeyHeight = HeximeSettings.keyboardHeightDp(this)
                // 就地重建：不重建整个输入视图，键盘页/ascii 状态都保留
                keyboardView?.applyTheme(currentPalette(), appliedKeyHeight)
                flashStatus("键盘高度 ${appliedKeyHeight}dp")
            }
            "palette" -> {
                val all = HeximePalettes.ALL
                val index = all.indexOfFirst { it.id == HeximeSettings.paletteId(this) }
                val next = all[(index + 1).mod(all.size)]
                HeximeSettings.setPaletteId(this, next.id)
                appliedPaletteId = next.id
                applyPalette(next)
                flashStatus("配色　${next.name}")
            }
            "addword" -> addWord()
            // run / web_search / deploy / date：trime2 专属，暂不移植
        }
    }

    // ---------------------------------------------------------------- 配色

    private fun currentPalette(): Palette = HeximePalettes.byId(HeximeSettings.paletteId(this))

    /** 换配色：键盘/候选栏/状态栏一起换，不用重建输入视图。 */
    private fun applyPalette(palette: Palette) {
        keyboardView?.applyTheme(palette, HeximeSettings.keyboardHeightDp(this))
        candidateBar?.applyPalette(palette)
        statusView?.setBackgroundColor(palette.candidateBg)
        statusView?.setTextColor(palette.candidateText)
    }

    // ---------------------------------------------------------------- 加词

    /**
     * 加词（菜单键）：词从系统剪贴板取（聊天/网页里长按选中的文本），码自动取当前组合区。
     *
     * openfly 的 translator 是 enable_user_dict: false —— 引擎不读用户词库，
     * 所以走方案的 custom_phrase(stabledb)：txt 直读，写完重载引擎即生效。
     */
    private fun addWord() {
        val word = clipboardChineseWord()
        if (word == null) {
            flashStatus("加词：剪贴板里没有中文词")
            return
        }
        commitAddWord(word)
    }

    /** 加词（快捷）：长按候选栏里的候选 —— 词就是该候选，码取当前组合区，写进去即置顶。 */
    private fun addWordFromCandidate(text: String) {
        val word = text.trim().replace("\t", "").replace("\n", "")
        if (word.isEmpty() || !word.any { it.code in 0x4E00..0x9FFF }) {
            flashStatus("加词：这个候选不是中文词")
            return
        }
        if (word.length > MAX_WORD_LENGTH) {
            flashStatus("加词：词太长（${word.length} 字）")
            return
        }
        commitAddWord(word)
    }

    private fun commitAddWord(word: String) {
        val code = currentCode()
        if (code == null) return
        if (!appendCustomPhrase(word, code)) {
            flashStatus("加词：写 custom_phrase.txt 失败")
            return
        }
        // stabledb 是打开即缓存的，重建会话不够 —— 重启引擎才会重新读 txt
        flashStatus("已加词　$word → $code（重载中…）")
        reloadEngine()
    }

    /** 当前编码：优先组合区，其次最近一次编码（打满 4 码会被 auto_select 上屏）。 */
    private fun currentCode(): String? {
        val code = (composition.ifEmpty { lastCode }).replace(" ", "").trim()
        if (code.isEmpty() || !code.all { it in 'a'..'z' || it == ';' || it == '/' }) {
            flashStatus("加词：先在键盘上打出编码，再加词")
            return null
        }
        if (code.length > MAX_CODE_LENGTH) {
            flashStatus("加词：编码最多 $MAX_CODE_LENGTH 位（$code）")
            return null
        }
        return code
    }

    /** 重启引擎：让 custom_phrase(stabledb) 重新打开并读到刚写的词条。 */
    private fun reloadEngine() {
        ready = false
        updateStatus()
        io.execute {
            engine.shutdown()
            val shared = RimeDataInstaller.ensureInstalled(this)
            val user = RimeDataInstaller.userDir(this).apply { mkdirs() }
            val log = RimeDataInstaller.logDir(this).apply { mkdirs() }
            val ok = engine.initialize(shared.absolutePath, user.absolutePath, log.absolutePath)
            if (ok) engine.deploy(false)
            ready = ok
            Log.i(TAG, "engine reloaded -> $ok")
            main.post {
                updateStatus()
                ensureSession()
                refresh()
            }
        }
    }

    /** 剪贴板里的中文词（没有汉字 / 太长都不算）。 */
    private fun clipboardChineseWord(): String? = runCatching {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).coerceToText(this).toString().trim()
        if (text.isEmpty() || text.length > MAX_WORD_LENGTH) return null
        if (!text.any { it.code in 0x4E00..0x9FFF }) return null
        text.replace("\t", "").replace("\n", "")
    }.getOrNull()

    /** 追加一行「词<tab>码」到 user/custom_phrase.txt。 */
    private fun appendCustomPhrase(word: String, code: String): Boolean {
        val file = File(RimeDataInstaller.userDir(this), CUSTOM_PHRASE_FILE)
        return try {
            file.parentFile?.mkdirs()
            if (!file.exists()) file.writeText("# Rime table\n# coding: utf-8\n")
            val line = "$word\t$code"
            if (file.readLines().none { it.trim() == line }) file.appendText(line + "\n")
            Log.i(TAG, "addword: $line -> ${file.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "addword failed", e)
            false
        }
    }

    /** 顶部状态栏临时显示一条提示，2.5 秒后恢复常规状态。 */
    private fun flashStatus(message: String) {
        val bar = statusView ?: return
        bar.text = message
        flashRunnable?.let { main.removeCallbacks(it) }
        val runnable = Runnable { updateStatus() }
        flashRunnable = runnable
        main.postDelayed(runnable, FLASH_MS)
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
        if (composition.isNotEmpty()) lastCode = composition
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

        /** 加词：小鹤音形码最长 4 位（schema 的 speller.max_code_length）。 */
        const val MAX_CODE_LENGTH = 4

        /** 加词：剪贴板词最长多少个字符。 */
        const val MAX_WORD_LENGTH = 16

        /** 状态栏提示停留时间。 */
        const val FLASH_MS = 2500L

        /** 用户词表（方案 custom_phrase 的 stabledb 源文件）。 */
        const val CUSTOM_PHRASE_FILE = "custom_phrase.txt"

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
            "Page_Up" to 0xFF55,   // InputEngine 暂无这两个常量
            "Page_Down" to 0xFF56,
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
