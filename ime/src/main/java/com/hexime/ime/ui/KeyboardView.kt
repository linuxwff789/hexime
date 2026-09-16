package com.hexime.ime.ui

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.SparseArray
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.hexime.ime.data.HeximeSettings
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
 *   3. 支持退格长按连续删除、空格长按切中英
 *
 * 空格键承担两件事：短按上屏空格，长按切中英（原来那个「中/英」键已去掉）；
 * 键面文字在中文模式显示当前方案名（如 openfly），英文模式显示 abc。
 *
 * 资源优化：
 *   * 命中测试按行分桶（每次 MOVE 只扫手指所在那一行的 7~10 个键，而不是全部 34 个）
 *   * 按键背景用的 ColorDrawable 按配色缓存、全键盘共享（见 [HeximeTheme]）
 */
class KeyboardView(context: Context) : LinearLayout(context) {

    var onKey: ((KeyAction) -> Unit)? = null

    private var shift = false
    private var symbolMode = false
    private var asciiMode = false
    private var schemaName = ""

    /** 按键高度（像素），来自设置，可在设置页调节。 */
    private val keyHeightPx: Int =
        (HeximeSettings.keyboardHeightDp(context) * resources.displayMetrics.density).toInt()

    /** 配色（跟随系统深色模式），两套见 [HeximeTheme]。 */
    private val palette = HeximeTheme.of(context)

    private val letterViews = LinkedHashMap<Char, TextView>()

    /** 空格键的 View（键面文字随中英/方案变化）。 */
    private var spaceView: TextView? = null

    private class KeyHolder(
        val view: TextView,
        val action: () -> KeyAction,
        val longAction: (() -> KeyAction)? = null,
    ) {
        val rect = Rect()
    }

    /** 一行按键的桶：命中测试先按 y 命中行，再在行内找键。 */
    private class RowBucket {
        var top = Int.MAX_VALUE
        var bottom = Int.MIN_VALUE
        val keys = ArrayList<KeyHolder>(10)
    }

    private val keys = ArrayList<KeyHolder>()
    private val buckets = ArrayList<RowBucket>(4)
    private var currentBucket: RowBucket? = null
    private val activePointers = SparseArray<KeyHolder>()
    private val longPressRunnables = SparseArray<Runnable>()
    private val longPressFired = SparseArray<Boolean>()

    private val handler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.keyboardBg)
        setPadding(dp(4), dp(6), dp(4), dp(6))
        build()
    }

    fun setShift(on: Boolean) {
        shift = on
        letterViews.forEach { (ch, view) -> view.text = label(ch) }
    }

    /** 中文模式空格键显示方案名，英文模式显示 abc。 */
    fun setMode(ascii: Boolean, schema: String) {
        asciiMode = ascii
        schemaName = schema
        spaceView?.text = spaceLabel()
    }

    fun toggleSymbols() {
        symbolMode = !symbolMode
        shift = false
        stopRepeat()
        build()
    }

    private fun label(ch: Char): String =
        if (shift) ch.uppercaseChar().toString() else ch.toString()

    private fun spaceLabel(): String = if (asciiMode) "abc" else schemaName.ifEmpty { "中文" }

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
                cancelAllLongPress()
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
        if (key.longAction != null) {
            // 有长按行为的键（空格）：先等一等，松手太早才当短按
            scheduleLongPress(pointerId, key)
        } else {
            val action = key.action()
            onKey?.invoke(action)
            if (action is KeyAction.Backspace) startRepeat(key.action)
        }
    }

    private fun handleMove(pointerId: Int, x: Float, y: Float) {
        val current = activePointers.get(pointerId) ?: return
        val target = keyAt(x, y)
        if (target !== current) {
            current.view.isPressed = false
            stopRepeat()
            cancelLongPress(pointerId, fireShort = false) // 手指移出该键，取消长按
            if (target != null) {
                activePointers.put(pointerId, target)
                target.view.isPressed = true
                if (target.longAction != null) {
                    scheduleLongPress(pointerId, target)
                } else {
                    onKey?.invoke(target.action())
                }
            } else {
                activePointers.remove(pointerId)
            }
        }
    }

    private fun handleUp(pointerId: Int) {
        // 长按没触发就当短按：空格短按上屏空格
        cancelLongPress(pointerId, fireShort = true)
        longPressFired.remove(pointerId)
        activePointers.get(pointerId)?.view?.isPressed = false
        activePointers.remove(pointerId)
        if (activePointers.size() == 0) stopRepeat()
    }

    private fun scheduleLongPress(pointerId: Int, key: KeyHolder) {
        val longAction = key.longAction ?: return
        val runnable = Runnable {
            longPressRunnables.remove(pointerId)
            longPressFired.put(pointerId, true)
            key.view.isPressed = false
            onKey?.invoke(longAction())
        }
        longPressRunnables.put(pointerId, runnable)
        handler.postDelayed(runnable, LONG_PRESS_MS)
    }

    private fun cancelLongPress(pointerId: Int, fireShort: Boolean) {
        val runnable = longPressRunnables.get(pointerId) ?: return
        handler.removeCallbacks(runnable)
        longPressRunnables.remove(pointerId)
        if (fireShort && longPressFired.get(pointerId, false) != true) {
            activePointers.get(pointerId)?.let { onKey?.invoke(it.action()) }
        }
    }

    private fun cancelAllLongPress() {
        for (i in 0 until longPressRunnables.size()) {
            handler.removeCallbacks(longPressRunnables.valueAt(i))
        }
        longPressRunnables.clear()
        longPressFired.clear()
    }

    private fun keyAt(x: Float, y: Float): KeyHolder? {
        val px = x.toInt()
        val py = y.toInt()
        for (bucket in buckets) {
            if (py < bucket.top || py > bucket.bottom) continue // 先按行过滤
            val rowKeys = bucket.keys
            for (i in rowKeys.indices) {
                val key = rowKeys[i]
                if (!key.rect.isEmpty && key.rect.contains(px, py)) return key
            }
        }
        return null
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        for (key in keys) {
            // 必须先把 key.rect 填成子视图自身的大小，再转换到本视图坐标系；
            // 否则 rect 为空，命中测试永远失败。
            key.view.getDrawingRect(key.rect)
            offsetDescendantRectToMyCoords(key.view, key.rect)
        }
        // 更新每行的 y 范围，供命中测试快速过滤
        for (bucket in buckets) {
            var lo = Int.MAX_VALUE
            var hi = Int.MIN_VALUE
            for (key in bucket.keys) {
                if (key.rect.isEmpty) continue
                if (key.rect.top < lo) lo = key.rect.top
                if (key.rect.bottom > hi) hi = key.rect.bottom
            }
            bucket.top = lo
            bucket.bottom = hi
        }
    }

    private fun startRepeat(action: () -> KeyAction) {
        stopRepeat()
        val runnable = object : Runnable {
            override fun run() {
                onKey?.invoke(action())
                handler.postDelayed(this, 55)
            }
        }
        repeatRunnable = runnable
        handler.postDelayed(runnable, 400)
    }

    private fun stopRepeat() {
        repeatRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable = null
    }

    // ---------------------------------------------------------------- 布局

    private fun build() {
        removeAllViews()
        keys.clear()
        buckets.clear()
        currentBucket = null
        letterViews.clear()
        spaceView = null
        activePointers.clear()
        cancelAllLongPress()
        if (symbolMode) buildSymbols() else buildLetters()
    }

    private fun buildLetters() {
        addView(letterRow(ROW1))
        addView(letterRow(ROW2))

        val row3 = newRow()
        addKey(row3, "⇧", 1.4f, action = { KeyAction.Shift })
        ROW3.forEach { ch -> letterViews[ch] = addKey(row3, label(ch), 1f) { symFor(ch) } }
        addKey(row3, "⌫", 1.4f, action = { KeyAction.Backspace })
        addView(row3)

        val row4 = newRow()
        addKey(row4, "123", 1.5f, action = { KeyAction.ToggleSymbols })
        addKey(row4, "，", 1f, action = { KeyAction.Sym(InputEngine.KEY_COMMA) })
        spaceView = addKey(
            row4, spaceLabel(), 5f,
            action = { KeyAction.Sym(InputEngine.KEY_SPACE) },
            longAction = { KeyAction.ToggleAscii },
        )
        addKey(row4, "。", 1f, action = { KeyAction.Sym(InputEngine.KEY_PERIOD) })
        addKey(row4, "↵", 1.5f, action = { KeyAction.Enter })
        addView(row4)
    }

    private fun buildSymbols() {
        addView(symbolRow(listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0')))
        addView(symbolRow(listOf('-', '/', ':', ';', '(', ')', '&', '@', '"', '#')))

        val row3 = newRow()
        listOf('.', ',', '?', '!', '\'', '+', '=', '_').forEach { ch ->
            addKey(row3, ch.toString(), 1f, action = { KeyAction.Sym(ch.code) })
        }
        addKey(row3, "⌫", 1.4f, action = { KeyAction.Backspace })
        addView(row3)

        val row4 = newRow()
        addKey(row4, "ABC", 1.5f, action = { KeyAction.ToggleSymbols })
        addKey(row4, "，", 1f, action = { KeyAction.Sym(InputEngine.KEY_COMMA) })
        spaceView = addKey(
            row4, spaceLabel(), 5f,
            action = { KeyAction.Sym(InputEngine.KEY_SPACE) },
            longAction = { KeyAction.ToggleAscii },
        )
        addKey(row4, "。", 1f, action = { KeyAction.Sym(InputEngine.KEY_PERIOD) })
        addKey(row4, "↵", 1.5f, action = { KeyAction.Enter })
        addView(row4)
    }

    private fun letterRow(chars: List<Char>): LinearLayout {
        val row = newRow()
        chars.forEach { ch -> letterViews[ch] = addKey(row, label(ch), 1f) { symFor(ch) } }
        return row
    }

    private fun symbolRow(chars: List<Char>): LinearLayout {
        val row = newRow()
        chars.forEach { ch -> addKey(row, ch.toString(), 1f, action = { KeyAction.Sym(ch.code) }) }
        return row
    }

    private fun symFor(ch: Char): KeyAction.Sym =
        KeyAction.Sym(ch.code, if (shift) InputEngine.MASK_SHIFT else 0)

    private fun newRow(): LinearLayout {
        val bucket = RowBucket()
        buckets.add(bucket)
        currentBucket = bucket
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(3), 0, dp(3))
        }
    }

    private fun addKey(
        row: LinearLayout,
        text: String,
        weight: Float,
        longAction: (() -> KeyAction)? = null,
        action: () -> KeyAction,
    ): TextView {
        val tv = TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTextColor(palette.keyText)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            background = keyBackground()
            // 关键：不设置 OnClickListener，触摸统一由键盘层处理
            isClickable = false
        }
        val lp = LayoutParams(0, keyHeightPx, weight)
        lp.marginStart = dp(3)
        lp.marginEnd = dp(3)
        row.addView(tv, lp)
        val holder = KeyHolder(tv, action, longAction)
        keys.add(holder)
        currentBucket?.keys?.add(holder)
        return tv
    }

    private fun keyBackground(): StateListDrawable = StateListDrawable().apply {
        // ColorDrawable 按配色缓存、全键盘共享（见 HeximeTheme.Palette）
        addState(intArrayOf(android.R.attr.state_pressed), palette.keyPressedDrawable)
        addState(intArrayOf(), palette.keyFaceDrawable)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val LONG_PRESS_MS = 400L

        val ROW1 = listOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p')
        val ROW2 = listOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l')
        val ROW3 = listOf('z', 'x', 'c', 'v', 'b', 'n', 'm')
    }
}
