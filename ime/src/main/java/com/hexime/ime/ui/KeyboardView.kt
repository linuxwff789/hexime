package com.hexime.ime.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.SparseArray
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.hexime.ime.engine.InputEngine

/** 按键动作。 */
sealed class KeyAction {
    data class Sym(val keySym: Int, val mask: Int = 0) : KeyAction()
    object Shift : KeyAction()
    object ToggleAscii : KeyAction()
    object ToggleSymbols : KeyAction()
    object SwitchSchema : KeyAction()
    object Backspace : KeyAction()
    object Enter : KeyAction()
}

/**
 * 极简 QWERTY 键盘，含数字/符号页。
 *
 * 关键点：**不使用子 View 的 OnClickListener**，而是在键盘（ViewGroup）层统一处理
 * 触摸事件（onInterceptTouchEvent + onTouchEvent），从而：
 *   1. 支持多点触控（多键齐按/手指重叠不丢键）
 *   2. 按下（ACTION_DOWN）立即触发，跟手更好
 *   3. 支持退格长按连续删除
 */
class KeyboardView(context: Context) : LinearLayout(context) {

    var onKey: ((KeyAction) -> Unit)? = null

    private var shift = false
    private var symbolMode = false

    private val letterViews = LinkedHashMap<Char, TextView>()

    private class KeyHolder(val view: TextView, val action: () -> KeyAction) {
        val rect = Rect()
    }

    private val keys = ArrayList<KeyHolder>()
    private val activePointers = SparseArray<KeyHolder>()

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#CFD8DC"))
        setPadding(dp(4), dp(6), dp(4), dp(6))
        build()
    }

    fun setShift(on: Boolean) {
        shift = on
        letterViews.forEach { (ch, view) -> view.text = label(ch) }
    }

    fun toggleSymbols() {
        symbolMode = !symbolMode
        shift = false
        stopRepeat()
        build()
    }

    private fun label(ch: Char): String =
        if (shift) ch.uppercaseChar().toString() else ch.toString()

    // ---------------------------------------------------------------- 多点触控

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                handleDown(ev.getPointerId(i), ev.getX(i), ev.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) {
                    handleMove(ev.getPointerId(i), ev.getX(i), ev.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                handleUp(ev.getPointerId(ev.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> {
                activePointers.clear()
                keys.forEach { it.view.isPressed = false }
                stopRepeat()
            }
        }
        return true
    }

    private fun handleDown(pointerId: Int, x: Float, y: Float) {
        val key = keyAt(x, y) ?: return
        activePointers.put(pointerId, key)
        key.view.isPressed = true
        val action = key.action()
        onKey?.invoke(action)
        if (action is KeyAction.Backspace) startRepeat(key.action)
    }

    private fun handleMove(pointerId: Int, x: Float, y: Float) {
        val current = activePointers.get(pointerId) ?: return
        val target = keyAt(x, y)
        if (target !== current) {
            current.view.isPressed = false
            stopRepeat()
            if (target != null) {
                activePointers.put(pointerId, target)
                target.view.isPressed = true
                onKey?.invoke(target.action())
            } else {
                activePointers.remove(pointerId)
            }
        }
    }

    private fun handleUp(pointerId: Int) {
        activePointers.get(pointerId)?.view?.isPressed = false
        activePointers.remove(pointerId)
        if (activePointers.size() == 0) stopRepeat()
    }

    private fun keyAt(x: Float, y: Float): KeyHolder? {
        val px = x.toInt()
        val py = y.toInt()
        return keys.firstOrNull { it.rect.contains(px, py) }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        for (key in keys) {
            // 必须先把 key.rect 填成子视图自身的大小，再转换到本视图坐标系；
            // 否则 rect 为空，命中测试永远失败。
            key.view.getDrawingRect(key.rect)
            offsetDescendantRectToMyCoords(key.view, key.rect)
        }
    }

    private fun startRepeat(action: () -> KeyAction) {
        stopRepeat()
        val runnable = object : Runnable {
            override fun run() {
                onKey?.invoke(action())
                repeatHandler.postDelayed(this, 55)
            }
        }
        repeatRunnable = runnable
        repeatHandler.postDelayed(runnable, 400)
    }

    private fun stopRepeat() {
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        repeatRunnable = null
    }

    // ---------------------------------------------------------------- 布局

    private fun build() {
        removeAllViews()
        keys.clear()
        letterViews.clear()
        activePointers.clear()
        if (symbolMode) buildSymbols() else buildLetters()
    }

    private fun buildLetters() {
        addView(letterRow(ROW1))
        addView(letterRow(ROW2))

        val row3 = newRow()
        addKey(row3, "⇧", 1.4f) { KeyAction.Shift }
        ROW3.forEach { ch -> letterViews[ch] = addKey(row3, label(ch), 1f) { symFor(ch) } }
        addKey(row3, "⌫", 1.4f) { KeyAction.Backspace }
        addView(row3)

        val row4 = newRow()
        addKey(row4, "123", 1.5f) { KeyAction.ToggleSymbols }
        addKey(row4, "中/英", 1.5f) { KeyAction.ToggleAscii }
        addKey(row4, "，", 1f) { KeyAction.Sym(InputEngine.KEY_COMMA) }
        addKey(row4, "空格", 4f) { KeyAction.Sym(InputEngine.KEY_SPACE) }
        addKey(row4, "。", 1f) { KeyAction.Sym(InputEngine.KEY_PERIOD) }
        addKey(row4, "↵", 1.5f) { KeyAction.Enter }
        addView(row4)
    }

    private fun buildSymbols() {
        addView(symbolRow(listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0')))
        addView(symbolRow(listOf('-', '/', ':', ';', '(', ')', '&', '@', '"', '#')))

        val row3 = newRow()
        listOf('.', ',', '?', '!', '\'', '+', '=', '_').forEach { ch ->
            addKey(row3, ch.toString(), 1f) { KeyAction.Sym(ch.code) }
        }
        addKey(row3, "⌫", 1.4f) { KeyAction.Backspace }
        addView(row3)

        val row4 = newRow()
        addKey(row4, "ABC", 1.5f) { KeyAction.ToggleSymbols }
        addKey(row4, "，", 1f) { KeyAction.Sym(InputEngine.KEY_COMMA) }
        addKey(row4, "空格", 4f) { KeyAction.Sym(InputEngine.KEY_SPACE) }
        addKey(row4, "。", 1f) { KeyAction.Sym(InputEngine.KEY_PERIOD) }
        addKey(row4, "↵", 1.5f) { KeyAction.Enter }
        addView(row4)
    }

    private fun letterRow(chars: List<Char>): LinearLayout {
        val row = newRow()
        chars.forEach { ch -> letterViews[ch] = addKey(row, label(ch), 1f) { symFor(ch) } }
        return row
    }

    private fun symbolRow(chars: List<Char>): LinearLayout {
        val row = newRow()
        chars.forEach { ch -> addKey(row, ch.toString(), 1f) { KeyAction.Sym(ch.code) } }
        return row
    }

    private fun symFor(ch: Char): KeyAction.Sym =
        KeyAction.Sym(ch.code, if (shift) InputEngine.MASK_SHIFT else 0)

    private fun newRow(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun addKey(
        row: LinearLayout,
        text: String,
        weight: Float,
        action: () -> KeyAction,
    ): TextView {
        val tv = TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTextColor(Color.parseColor("#212121"))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            background = keyBackground()
            // 关键：不设置 OnClickListener，触摸统一由键盘层处理
            isClickable = false
        }
        val lp = LayoutParams(0, LayoutParams.WRAP_CONTENT, weight)
        lp.marginStart = dp(3)
        lp.marginEnd = dp(3)
        row.addView(tv, lp)
        keys.add(KeyHolder(tv, action))
        return tv
    }

    private fun keyBackground(): StateListDrawable {
        val normal = ColorDrawable(Color.parseColor("#FFFFFF"))
        val pressed = ColorDrawable(Color.parseColor("#90A4AE"))
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val ROW1 = listOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p')
        val ROW2 = listOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l')
        val ROW3 = listOf('z', 'x', 'c', 'v', 'b', 'n', 'm')
    }
}
