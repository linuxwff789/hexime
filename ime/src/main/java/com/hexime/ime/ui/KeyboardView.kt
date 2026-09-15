package com.hexime.ime.ui

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
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

/** 极简 QWERTY 键盘，含数字/符号页，纯代码构建。 */
class KeyboardView(context: Context) : LinearLayout(context) {

    var onKey: ((KeyAction) -> Unit)? = null

    private var shift = false
    private var symbolMode = false

    /** 跟随 shift 变大小写的字母键 */
    private val letterViews = LinkedHashMap<Char, TextView>()

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
        build()
    }

    private fun label(ch: Char): String =
        if (shift) ch.uppercaseChar().toString() else ch.toString()

    // ---------------------------------------------------------------- 布局

    private fun build() {
        removeAllViews()
        letterViews.clear()
        if (symbolMode) buildSymbols() else buildLetters()
    }

    private fun buildLetters() {
        addView(letterRow(ROW1))
        addView(letterRow(ROW2))

        val row3 = newRow()
        addKey(row3, "⇧", 1.4f) { KeyAction.Shift }
        ROW3.forEach { ch ->
            letterViews[ch] = addKey(row3, label(ch), 1f) { symFor(ch) }
        }
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
        chars.forEach { ch ->
            letterViews[ch] = addKey(row, label(ch), 1f) { symFor(ch) }
        }
        return row
    }

    private fun symbolRow(chars: List<Char>): LinearLayout {
        val row = newRow()
        chars.forEach { ch ->
            addKey(row, ch.toString(), 1f) { KeyAction.Sym(ch.code) }
        }
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
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            isClickable = true
            setOnClickListener { onKey?.invoke(action()) }
        }
        val lp = LayoutParams(0, LayoutParams.WRAP_CONTENT, weight)
        lp.marginStart = dp(3)
        lp.marginEnd = dp(3)
        row.addView(tv, lp)
        return tv
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val ROW1 = listOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p')
        val ROW2 = listOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l')
        val ROW3 = listOf('z', 'x', 'c', 'v', 'b', 'n', 'm')
    }
}