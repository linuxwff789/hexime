package com.hexime.ime

import android.content.ClipboardManager
import android.content.Context
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
import com.hexime.ime.data.GithubSync
import com.hexime.ime.data.Schemas
import com.hexime.ime.data.SyncPrefs
import com.hexime.ime.engine.InputEngine
import com.hexime.ime.engine.RimeEngine
import com.hexime.ime.ui.AddWordDialog
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

    /** 顶部一行左边：当前编码（组合串）。 */
    private var codeView: TextView? = null

    /** 编码行上一次的文本：内容没变就不 setText（省一次 measure/layout/draw）。 */
    private var lastCodeText = ""

    /** 顶栏常驻的「加词」按钮（换配色时要跟着改字色）。 */
    private var addWordView: TextView? = null

    /** 顶栏常驻的「配色」按钮。 */
    private var paletteView: TextView? = null

    /** 顶栏容器（换配色时要改底色）。 */
    private var topBarView: LinearLayout? = null

    /** 状态行上一次的文本（没变就不 setText）。 */
    private var lastStatusText = ""

    /** 中/英状态标识（点一下切换）。 */
    private var modeView: TextView? = null

    // ---- 设置缓存：按键热路径上不要反复读 SharedPreferences ----
    private var vibPercent = HeximeSettings.VIBRATION_DEFAULT
    private var latencyOn = false

    /** 震动波形：按键时不再每键 new 一个 VibrationEffect（强度变了才重建）。 */
    private var vibEffect: VibrationEffect? = null
    private var vibEffectPercent = -1

    /** 重新读一遍热路径用到的设置（建视图、从设置页回来时调用）。 */
    private fun refreshSettingsCache() {
        vibPercent = HeximeSettings.vibrationPercent(this)
        latencyOn = HeximeSettings.showLatency(this)
    }

    private var statusView: TextView? = null
    private var candidateBar: CandidateBar? = null
    private var keyboardView: KeyboardView? = null


    private var shiftOn = false
    private var asciiMode = false

    /** 是否正在组合（决定组合结束时用 finishComposingText 收尾）。 */
    private var composingActive = false

    /** 每个方案上次用的键盘，切方案时还原（不硬塞，避免"切回小鹤键盘变笔画"）。 */
    private val keyboardPerSchema = HashMap<String, String>()

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

    /** 加词窗口（键盘上弹出）。 */
    private var addWordDialog: AddWordDialog? = null

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

    override fun onCreateInputView(): View = try {
        buildInputView()
    } catch (t: Throwable) {
        // 输入法视图建失败也**不能崩**：崩了就是进程反复重启、键盘永远弹不出来（用户看到「打不开」）。
        // 退回一个能看懂的提示，顺便把错误带出来，方便定位。
        Log.e(TAG, "onCreateInputView failed", t)
        TextView(this).apply {
            text = "Hexime 键盘创建失败：${t.message}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), dp(20), dp(16), dp(20))
            setBackgroundColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
        }
    }

    private fun buildInputView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val palette = currentPalette()

        // 顶部两段：左边「编码」（打字时的组合串，大字号），右边状态（方案/中英/版本/跟手）
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(palette.candidateBg)
        }
        // 中/英 状态标识：点一下切换（和空格长按同一个开关）
        val modeChip = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { toggleAsciiMode() }
        }
        modeView = modeChip
        val code = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(palette.candidateText)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            maxLines = 1
        }
        codeView = code
        val status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(palette.candidateComment)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            gravity = Gravity.END
            // 点状态栏切换跟手延迟显示
            isClickable = true
            setOnClickListener {
                val next = !latencyOn
                HeximeSettings.setShowLatency(this@HeximeService, next)
                latencyOn = next
                updateStatus()
            }
        }
        statusView = status
        // 常驻「加词」按钮：不用打字、不用长按任何键，一键开加词窗口
        val addWord = TextView(this).apply {
            text = "＋词"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(palette.candidateText)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            isClickable = true
            setOnClickListener { showAddWordDialog(clipboardChineseWord().orEmpty()) }
        }
        addWordView = addWord
        // 常驻「配色」按钮：候选栏变身配色条，点一下即换
        val paletteBtn = TextView(this).apply {
            text = "配色"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(palette.candidateText)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            isClickable = true
            setOnClickListener { showPalettePicker() }
        }
        paletteView = paletteBtn
        topBarView = topBar
        topBar.addView(modeChip)
        topBar.addView(code, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(addWord)
        topBar.addView(paletteBtn)
        topBar.addView(
            status,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        candidateBar = CandidateBar(this).apply {
            onSelect = { index -> onSelectCandidate(index) }
            // 长按候选 = 加词窗口（预填该候选词）
            onLongSelect = { text -> showAddWordDialog(text) }
            // 候选栏两端的 △/▽ 翻页（小鹤主题的 Page_Up/Page_Down）
            onPage = { dir ->
                onKeyAction(KeyAction.Named(if (dir < 0) "Page_Up" else "Page_Down"))
            }
        }
        keyboardView = KeyboardView(this).apply {
            onKey = { action -> onKeyAction(action) }
            // 小鹤的 `.` 键：组合中 = 次选第 2 候选
            isComposing = { composition.isNotEmpty() }
        }

        root.addView(topBar)
        root.addView(candidateBar)
        root.addView(keyboardView)
        // 记住这次视图用的高度/配色，免得 onStartInputView 又重建一遍
        appliedKeyHeight = HeximeSettings.keyboardHeightDp(this)
        appliedPaletteId = HeximeSettings.paletteId(this)
        refreshSettingsCache()
        // 设置页里改了方案 → 回到键盘时跟上（当前会话还在用旧方案的话）
        val wantedSchema = HeximeSettings.schemaId(this)
        if (ready && engine.hasSession() && wantedSchema != engine.currentSchema()) {
            applySchema(wantedSchema)
        }
        updateModeChip()
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
        refreshSettingsCache()
        val height = HeximeSettings.keyboardHeightDp(this)
        val paletteId = HeximeSettings.paletteId(this)
        if (height != appliedKeyHeight || paletteId != appliedPaletteId) {
            appliedKeyHeight = height
            appliedPaletteId = paletteId
            setInputView(onCreateInputView())
        }
        // 设置里改了方案 → 跟上（当前会话还在用旧方案的话）
        val wanted = HeximeSettings.schemaId(this)
        if (ready && engine.hasSession() && wanted != engine.currentSchema()) applySchema(wanted)
        ensureSession()
        // 设置页里「下载」过词表 → 重启引擎把新词读进来（stabledb 打开即缓存，必须重启）
        if (ready && SyncPrefs.consumePhraseReload(this)) reloadEngine()
        refresh()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        // 注意：**不要**在这里关加词窗口 —— 弹窗里的输入框拿到焦点时，系统会先给原编辑器
        // 发 onFinishInput，一关就把刚弹出的窗口关掉了。
        engine.clearComposition()
    }

    override fun onDestroy() {
        addWordDialog?.dismiss()
        addWordDialog = null
        engine.shutdown()
        io.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 引擎启动

    private fun bootstrapEngine() {
        io.execute {
            // 这里抛异常 = 输入法进程直接死；数据释放失败也要让键盘能弹出来（提示一下就行）
            val ok = runCatching {
                val shared = RimeDataInstaller.ensureInstalled(this)
                val user = RimeDataInstaller.userDir(this).apply { mkdirs() }
                val log = RimeDataInstaller.logDir(this).apply { mkdirs() }
                val initialized = engine.initialize(shared.absolutePath, user.absolutePath, log.absolutePath)
                if (initialized) {
                    engine.deploy(false)
                    Log.i(TAG, "engine ready, version=${engine.version()}")
                } else {
                    Log.e(TAG, "engine initialize failed")
                }
                initialized
            }.getOrElse {
                Log.e(TAG, "bootstrapEngine failed", it)
                false
            }
            if (ok) ready = true
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
            engine.createSession(HeximeSettings.schemaId(this))
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
        updateModeChip()
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
            "copy" -> contextAction(android.R.id.copy)
            "cut" -> contextAction(android.R.id.cut)
            "paste" -> contextAction(android.R.id.paste)
            "select_all" -> contextAction(android.R.id.selectAll)
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

    /** 编辑器认不认这个上下文动作（selectAll/copy/paste/cut）。 */
    private fun contextAction(id: Int): Boolean =
        currentInputConnection?.performContextMenuAction(id) ?: false

    private fun onCtrlKey(ch: Char) {
        // 先试编辑器的「上下文动作」（selectAll/copy/paste/cut）—— 大多数 app 认；
        // 不认的（自绘编辑器/终端等）就退回真发 Ctrl+字母 按键事件，否则 a 上滑全选会「没反应」。
        val handled = when (ch.lowercaseChar()) {
            'a' -> contextAction(android.R.id.selectAll)
            'c' -> contextAction(android.R.id.copy)
            'v' -> contextAction(android.R.id.paste)
            'x' -> contextAction(android.R.id.cut)
            else -> false
        }
        if (handled) return
        when (ch.lowercaseChar()) {
            'a' -> sendKeyWithMeta(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
            'c' -> sendKeyWithMeta(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON)
            'v' -> sendKeyWithMeta(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
            'x' -> sendKeyWithMeta(KeyEvent.KEYCODE_X, KeyEvent.META_CTRL_ON)
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

    /**
     * 切方案：写偏好 + 引擎切 + 键盘跟着换。
     * 拼音用普通 QWERTY（音形助记键盘上的字根提示没意义），音形回助记键盘。
     * 引擎还没起来时只记偏好，建会话时按它开。
     */
    private fun applySchema(id: String) {
        HeximeSettings.setSchemaId(this, id)
        if (!ready || !engine.hasSession()) return
        val from = engine.currentSchema()
        if (from == id) return
        if (!engine.selectSchema(id)) {
            flashStatus("切不了方案：${Schemas.label(id)}")
            return
        }
        // 键盘按方案各记一份，切回来就还原。
        // **不要**硬塞某个键盘（原来切回音形强制 defaultVJ，键面是 丶丨 这类字根，
        // 用户看到的就是「切回小鹤键盘变成笔画了」）。
        keyboardView?.currentKeyboardId()?.let { keyboardPerSchema[from] = it }
        keyboardPerSchema[id]?.let { keyboardView?.showKeyboard(it) }
        flashStatus("方案：${Schemas.label(id)}")
        updateStatus()
        refresh()
    }

    /** 中/英切换：空格长按、选单的中英键、顶栏状态标识都走这里。 */
    private fun toggleAsciiMode() {
        asciiMode = !asciiMode
        engine.setOption("ascii_mode", asciiMode)
        keyboardView?.setAscii(asciiMode)
        updateModeChip()
        updateStatus()
    }

    /** 顶栏的中/英标识：中文用键面底色，英文用按下色，一眼能看出当前模式。 */
    private fun updateModeChip() {
        val v = modeView ?: return
        val palette = currentPalette()
        v.text = if (asciiMode) "英" else "中"
        v.setBackgroundColor(if (asciiMode) palette.keyPressed else palette.keyFace)
        v.setTextColor(if (asciiMode) palette.keyPressedText else palette.keyText)
    }

    /** Rime 选项开关。 */
    private fun onToggle(option: String) {
        when (option) {
            "ascii_mode" -> toggleAsciiMode()
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
            "palette" -> showPalettePicker()
            "schema" -> applySchema(Schemas.next(engine.currentSchema()))
            "addword" -> showAddWordDialog(clipboardChineseWord().orEmpty())
            // run / web_search / deploy / date：trime2 专属，暂不移植
        }
    }

    // ---------------------------------------------------------------- 配色

    private fun currentPalette(): Palette = HeximePalettes.byId(HeximeSettings.paletteId(this))

    /** 换配色：键盘/候选栏/顶栏/编码行/状态栏一起换，不用重建输入视图。 */
    private fun applyPalette(palette: Palette) {
        keyboardView?.applyTheme(palette, HeximeSettings.keyboardHeightDp(this))
        candidateBar?.applyPalette(palette)
        topBarView?.setBackgroundColor(palette.candidateBg)
        updateModeChip()
        codeView?.setTextColor(palette.statusText)
        addWordView?.setTextColor(palette.candidateText)
        paletteView?.setTextColor(palette.candidateText)
        statusView?.setTextColor(palette.candidateComment)
    }

    /** 配色选择：候选栏变成一排配色（每项用自己的颜色画），点一下即换。 */
    private fun showPalettePicker() {
        val bar = candidateBar ?: return
        bar.showPalettePicker { index ->
            val p = HeximePalettes.ALL.getOrNull(index) ?: return@showPalettePicker
            HeximeSettings.setPaletteId(this, p.id)
            appliedPaletteId = p.id
            applyPalette(p)
            bar.clearPicker()
            refresh()
            flashStatus("配色　${p.name}")
        }
        flashStatus("选配色")
    }

    // ---------------------------------------------------------------- 加词

    /**
     * 加词窗口：键盘上直接弹出（选单的「加词」键 / 空格上滑 / 长按候选），
     * 手输或粘贴词 → 自动查小鹤编码 → 确定后写进用户词表并重载引擎。
     *
     * openfly 的 translator 是 enable_user_dict: false —— 引擎不读用户词库，
     * 所以走方案的 custom_phrase(stabledb)：txt 直读，写完重载引擎即生效。
     */
    private fun showAddWordDialog(prefill: String = "") {
        if (!ready) {
            flashStatus("引擎还在启动，稍后再试")
            return
        }
        val dialog = AddWordDialog(this, currentPalette()) { word, code ->
            if (appendCustomPhrase(word, code)) {
                flashStatus("已加词　$word → $code（重载中…）")
                reloadEngine()
                syncUpAfterAddWord()
            } else {
                flashStatus("加词：写 custom_phrase.txt 失败")
            }
        }
        addWordDialog = dialog
        runCatching { dialog.show(prefill) }.onFailure {
            flashStatus("加词窗口打不开：${it.message}（要「显示在其他应用上层」权限）")
            Log.e(TAG, "showAddWordDialog failed", it)
        }
    }

    /** 加完词自动推一次（没配 token 就静默跳过；失败才提示）。 */
    private fun syncUpAfterAddWord() {
        if (SyncPrefs.token(this).isBlank()) return
        // 还没成功拉过一次就别自动推（新装机器上的本地设置几乎是默认值，推上去会冲掉云端）
        if (!SyncPrefs.firstPullDone(this)) return
        io.execute {
            val result = runCatching { GithubSync.upload(this) }
                .getOrElse { GithubSync.Result(false, "异常：${it.message}") }
            if (result.ok) {
                Log.i(TAG, "auto sync after addword: ${result.message}")
            } else {
                main.post { flashStatus("同步失败：${result.message}") }
            }
        }
    }

    /** 重启引擎：让 custom_phrase(stabledb) 重新打开并读到刚写的词条。 */
    private fun reloadEngine() {
        ready = false
        updateStatus()
        io.execute {
            engine.shutdown()
            val ok = runCatching {
                val shared = RimeDataInstaller.ensureInstalled(this)
                val user = RimeDataInstaller.userDir(this).apply { mkdirs() }
                val log = RimeDataInstaller.logDir(this).apply { mkdirs() }
                val initialized = engine.initialize(shared.absolutePath, user.absolutePath, log.absolutePath)
                if (initialized) engine.deploy(false)
                initialized
            }.getOrElse {
                Log.e(TAG, "reloadEngine failed", it)
                false
            }
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

    /** 顶部状态栏临时显示一条提示，2.5 秒后恢复成当前编码。 */
    private fun flashStatus(message: String) {
        val view = codeView ?: return
        view.text = message
        lastCodeText = message
        flashRunnable?.let { main.removeCallbacks(it) }
        val runnable = Runnable {
            lastCodeText = composition
            codeView?.text = composition
        }
        flashRunnable = runnable
        main.postDelayed(runnable, FLASH_MS)
    }

    /** 编码行：内容没变就不 setText。 */
    private fun setCodeText(text: String) {
        if (lastCodeText == text) return
        lastCodeText = text
        codeView?.text = text
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
        setCodeText(composition)
        // 配色选择条打开时不要用候选把它盖掉
        if (candidateBar?.inPicker != true) {
            candidateBar?.setCandidates(snapshot.candidates)
            candidateBar?.setPage(snapshot.pageNo, snapshot.isLastPage)
        }

        val ic = currentInputConnection ?: return
        if (!commit.isNullOrEmpty()) {
            ic.commitText(commit, 1)
            composingActive = false
        }
        // 组合区空了必须用 finishComposingText()，**绝对不能** setComposingText("")：
        // 编辑器里只要有选区（刚做完「全选」、或用户自己划了一段），
        // 空的 composing 会把选区整段替换掉 —— 表现就是「上滑 a 全选，结果内容全没了」。
        if (snapshot.composition.isEmpty()) {
            if (composingActive) {
                ic.finishComposingText()
                composingActive = false
            }
        } else {
            ic.setComposingText(snapshot.composition, 1)
            composingActive = true
        }
    }

    private fun updateStatus() {
        val bar = statusView ?: return
        val text = when {
            !ready -> "部署中…（首次会编译词库）"
            else -> buildString {
                // 顶栏要留出「＋词」「配色」按钮的位置，状态文字保持短
                append(Schemas.label(engine.currentSchema()))
                if (latencyOn) {
                    append(" · %.1fms".format(latencyMs))
                }
            }
        }
        // 按键热路径上会频繁调用：文字没变就别 setText（省一次 measure/layout/draw）
        if (lastStatusText == text) return
        lastStatusText = text
        bar.text = text
    }

    private fun haptic() {
        val percent = vibPercent
        if (percent <= 0) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        var effect = vibEffect
        if (effect == null || vibEffectPercent != percent) {
            val amplitude = (percent / 100.0 * 255).toInt().coerceIn(1, 255)
            effect = VibrationEffect.createOneShot(12L, amplitude)
            vibEffect = effect
            vibEffectPercent = percent
        }
        runCatching { v.vibrate(effect) }
    }

    /** 记录「按键 -> 下一帧渲染」的耗时，作为跟手性的量化指标。 */
    private fun measureLatency(t0: Long) {
        if (!latencyOn) return
        Choreographer.getInstance().postFrameCallback { frameTimeNanos ->
            latencyMs = (frameTimeNanos - t0) / 1_000_000.0
            updateStatus()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "HeximeService"

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
