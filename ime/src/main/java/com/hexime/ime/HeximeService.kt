package com.hexime.ime

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
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
        updateStatus()
        return root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        ensureSession()
        refresh()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
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
            }
        }
        if (action is KeyAction.Sym && shiftOn) {
            shiftOn = false
            keyboardView?.setShift(false)
        }
        refresh()
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
        bar.text = when {
            !ready -> "部署中…（首次会编译词库）"
            else -> "${engine.currentSchema()}  ·  ${if (asciiMode) "英" else "中"}  ·  librime ${engine.version()}"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "HeximeService"
    }
}
