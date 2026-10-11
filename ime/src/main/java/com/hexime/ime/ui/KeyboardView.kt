package com.hexime.ime.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.SparseArray
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.hexime.ime.data.HeximeSettings
import com.hexime.ime.engine.InputEngine
import kotlin.math.abs

/** 按键动作。 */
sealed class KeyAction {
    /** 直接按键（X11 keysym + 修饰位）。 */
    data class Sym(val keySym: Int, val mask: Int = 0) : KeyAction()

    /** 具名功能键：BackSpace / space / Return / Up / Tab / copy ... */
    data class Named(val name: String) : KeyAction()

    /** Ctrl+字母。 */
    data class Ctrl(val ch: Char) : KeyAction()

    /** 按键序列宏，如 {End}{Shift+Home}{Control+c}。 */
    data class Text(val text: String) : KeyAction()

    /** 切键盘（trime2 的 select）。 */
    data class Kb(val id: String) : KeyAction()

    /** 开关：ascii_mode / simplification / ... */
    data class Toggle(val option: String) : KeyAction()

    /** 组合中选第 N 候选（次选/三选）。 */
    data class Cand(val index: Int) : KeyAction()

    /** 主题脚本类功能（settings / kbheight / date / run / web / addword / deploy）。 */
    data class Cmd(val name: String, val option: String) : KeyAction()

    object None : KeyAction()
}

/**
 * 数据驱动的键盘：布局来自 assets/keyboards/<id>.json（由小鹤主题的 trime2 键盘转换而来）。
 *
 * 沿用原有设计：**不使用子 View 的 OnClickListener**，在键盘（ViewGroup）层统一处理触摸，
 * 因此支持多点触控（多键齐按/手指重叠不丢键）。
 *
 * 触发时机（为了支持小鹤键盘自带的长按/滑动）：
 *   * 只绑了 click 的键 → 按下(DOWN)立即触发，保留原有的跟手性
 *   * 绑了 long_click / swipe_* 的键 → 松手(UP)时触发 click；长按或滑动会顶掉它
 */
class KeyboardView(context: Context) : LinearLayout(context) {

    var onKey: ((KeyAction) -> Unit)? = null

    /** 引擎是否正在组合：小鹤的 `.` 键在组合中 = 选第 2 候选。 */
    var isComposing: (() -> Boolean)? = null

    private var shift = false
    private var asciiMode = false
    private var currentId = KeyboardRepo.DEFAULT_ID
    private var def: KeyboardDef? = null

    /** 按键高度（像素），来自设置，可在设置页调节。 */
    private val keyHeightPx: Int =
        (HeximeSettings.keyboardHeightDp(context) * resources.displayMetrics.density).toInt()

    private class KeyHolder(
        val view: TextView,
        val k: KeyDef,
        val click: KeyAction,
        val long: KeyAction?,
        val composing: KeyAction?,
        val up: KeyAction?,
        val down: KeyAction?,
        val left: KeyAction?,
        val right: KeyAction?,
    ) {
        val rect = Rect()

        /** 按下就触发的键：松手不再补一次。 */
        var firedOnDown = false
        var longFired = false
        var consumed = false
        var downX = 0f
        var downY = 0f

        val hasGesture: Boolean
            get() = long != null || up != null || down != null || left != null || right != null

        fun swipe(dir: Int): KeyAction? = when (dir) {
            DIR_UP -> up
            DIR_DOWN -> down
            DIR_LEFT -> left
            else -> right
        }
    }

    private val keys = ArrayList<KeyHolder>()
    private val activePointers = SparseArray<KeyHolder>()
    private val longPressRunnables = SparseArray<Runnable>()

    private val handler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#CFD8DC"))
        setPadding(dp(4), dp(6), dp(4), dp(6))
        showKeyboard(KeyboardRepo.DEFAULT_ID)
    }

    // ---------------------------------------------------------------- 对外 API

    fun currentKeyboard(): String = currentId

    /** 当前键盘自带的 ascii_mode（小鹤键盘文件里的字段，切换键盘时同步给引擎）。 */
    fun asciiOn(): Boolean = asciiMode

    /** 切键盘（小鹤的 select）。目标不存在时回退到默认键盘。 */
    fun showKeyboard(id: String) {
        val d = KeyboardRepo.load(context, id)
            ?: KeyboardRepo.load(context, KeyboardRepo.DEFAULT_ID)
            ?: return
        currentId = d.id
        def = d
        asciiMode = d.asciiDefault
        shift = false
        build()
    }

    fun setAscii(on: Boolean) {
        if (asciiMode == on) return
        asciiMode = on
        build()
    }

    fun setShift(on: Boolean) {
        if (shift == on) return
        shift = on
        keys.forEach { it.view.text = face(it.k.label, it.k.hint, it.k.style, shift) }
    }

    /** 键盘高度设置变化后重建（键高是构建期算出来的）。 */
    fun rebuild() = build()

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
        key.firedOnDown = false
        key.longFired = false
        key.consumed = false
        key.downX = x
        key.downY = y
        activePointers.put(pointerId, key)
        key.view.isPressed = true
        if (key.hasGesture) {
            // 有长按/滑动的键：等松手（或长按计时到）再定
            if (key.long != null) scheduleLongPress(pointerId, key)
        } else {
            val action = resolveClick(key)
            key.firedOnDown = true
            if (action !is KeyAction.None) onKey?.invoke(action)
            if (action is KeyAction.Named && action.name == "BackSpace") startRepeat { resolveClick(key) }
        }
    }

    /** 真正要执行的动作：ascii 替身 > 组合中候选 > click。 */
    private fun resolveClick(key: KeyHolder): KeyAction {
        val k = key.k
        if (asciiMode && k.asciiAlt != null) return toAction(k.asciiAlt, shift)
        val composing = key.composing
        if (composing != null && isComposing?.invoke() == true) return composing
        return key.click
    }

    private fun handleMove(pointerId: Int, x: Float, y: Float) {
        val current = activePointers.get(pointerId) ?: return
        if (current.firedOnDown) {
            // 立即触发的键：沿用「手指滑到别的键就按那个键」
            val target = keyAt(x, y)
            if (target == null || target === current) return
            current.view.isPressed = false
            stopRepeat()
            target.firedOnDown = true
            target.longFired = false
            target.consumed = false
            target.downX = x
            target.downY = y
            activePointers.put(pointerId, target)
            target.view.isPressed = true
            val action = resolveClick(target)
            if (action !is KeyAction.None) onKey?.invoke(action)
            return
        }
        if (current.consumed || current.longFired) return
        val dx = x - current.downX
        val dy = y - current.downY
        val slop = SWIPE_SLOP_DP * resources.displayMetrics.density
        if (abs(dx) < slop && abs(dy) < slop) return
        val dir = if (abs(dx) > abs(dy)) {
            if (dx > 0) DIR_RIGHT else DIR_LEFT
        } else {
            if (dy > 0) DIR_DOWN else DIR_UP
        }
        val swipe = current.swipe(dir)
        if (swipe != null) {
            current.consumed = true
            cancelLongPress(pointerId)
            current.view.isPressed = false
            onKey?.invoke(swipe)
        } else {
            // 没有该方向的动作：手指已经划出去了，取消长按
            cancelLongPress(pointerId)
        }
    }

    private fun handleUp(pointerId: Int) {
        val key = activePointers.get(pointerId)
        cancelLongPress(pointerId)
        if (key != null) {
            key.view.isPressed = false
            if (!key.firedOnDown && !key.consumed && !key.longFired) {
                val action = resolveClick(key)
                if (action !is KeyAction.None) onKey?.invoke(action)
            }
        }
        activePointers.remove(pointerId)
        if (activePointers.size() == 0) stopRepeat()
    }

    private fun scheduleLongPress(pointerId: Int, key: KeyHolder) {
        val action = key.long ?: return
        val runnable = Runnable {
            longPressRunnables.remove(pointerId)
            key.longFired = true
            key.view.isPressed = false
            onKey?.invoke(action)
        }
        longPressRunnables.put(pointerId, runnable)
        handler.postDelayed(runnable, LONG_PRESS_MS)
    }

    private fun cancelLongPress(pointerId: Int) {
        val runnable = longPressRunnables.get(pointerId) ?: return
        handler.removeCallbacks(runnable)
        longPressRunnables.remove(pointerId)
    }

    private fun cancelAllLongPress() {
        for (i in 0 until longPressRunnables.size()) {
            handler.removeCallbacks(longPressRunnables.valueAt(i))
        }
        longPressRunnables.clear()
    }

    private fun keyAt(x: Float, y: Float): KeyHolder? {
        val px = x.toInt()
        val py = y.toInt()
        for (key in keys) {
            if (!key.rect.isEmpty && key.rect.contains(px, py)) return key
        }
        // 兜底：点在键缝/行缝上时按最近的键算，别再出现「只有震动没有字」
        val tol = TOUCH_SLOP_DP * resources.displayMetrics.density
        var best: KeyHolder? = null
        var bestScore = Float.MAX_VALUE
        for (key in keys) {
            if (key.rect.isEmpty) continue
            val dx = when {
                px < key.rect.left -> (key.rect.left - px).toFloat()
                px > key.rect.right -> (px - key.rect.right).toFloat()
                else -> 0f
            }
            val dy = when {
                py < key.rect.top -> (key.rect.top - py).toFloat()
                py > key.rect.bottom -> (py - key.rect.bottom).toFloat()
                else -> 0f
            }
            if (dx > tol || dy > tol) continue
            val score = dx + dy
            if (score < bestScore) {
                bestScore = score
                best = key
            }
        }
        return best
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        for (key in keys) {
            // 必须先把 rect 填成子视图自身的大小，再转换到本视图坐标系；
            // 否则 rect 为空，命中测试永远失败。
            key.view.getDrawingRect(key.rect)
            offsetDescendantRectToMyCoords(key.view, key.rect)
        }
    }

    private fun startRepeat(action: () -> KeyAction) {
        stopRepeat()
        val runnable = object : Runnable {
            override fun run() {
                val a = action()
                if (a !is KeyAction.None) onKey?.invoke(a)
                handler.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        repeatRunnable = runnable
        handler.postDelayed(runnable, REPEAT_DELAY_MS)
    }

    private fun stopRepeat() {
        repeatRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable = null
    }

    // ---------------------------------------------------------------- 渲染

    private fun build() {
        removeAllViews()
        keys.clear()
        activePointers.clear()
        cancelAllLongPress()
        stopRepeat()
        val d = def ?: return
        val rowCount = d.rows.size.coerceAtLeast(1)
        for (row in d.rows) {
            val rl = LinearLayout(context).apply {
                orientation = HORIZONTAL
                setPadding(0, dp(2), 0, dp(2))
            }
            // trime2 的行高是键盘总高的百分比 → 换算成用户设置的键高
            val h = (keyHeightPx * row.heightPercent / 100f * rowCount).toInt()
                .coerceAtLeast(dp(18))
            for (k in row.keys) {
                val placeholder = k.label.isEmpty() && k.hint.isEmpty() && k.click == null
                val tv = TextView(context).apply {
                    text = face(k.label, k.hint, k.style, shift)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (k.hint.isNotEmpty()) 15f else 19f)
                    setTextColor(Color.parseColor("#212121"))
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, 0)
                    isClickable = false
                    background = if (placeholder) null else keyBackground()
                }
                val lp = LayoutParams(0, h, k.width)
                lp.marginStart = dp(2)
                lp.marginEnd = dp(2)
                rl.addView(tv, lp)
                keys.add(
                    KeyHolder(
                        view = tv,
                        k = k,
                        click = toAction(k.click, shift),
                        long = k.long?.let { toAction(it, shift) },
                        composing = k.composing?.let { candToAction(it) },
                        up = k.swipeUp?.let { toAction(it, shift) },
                        down = k.swipeDown?.let { toAction(it, shift) },
                        left = k.swipeLeft?.let { toAction(it, shift) },
                        right = k.swipeRight?.let { toAction(it, shift) },
                    ),
                )
            }
            addView(rl)
        }
    }

    /** 键面文字：主字 + 小号 hint（小鹤助记键盘的韵母/字根）。 */
    private fun face(label: String, hint: String, style: String, shift: Boolean): CharSequence {
        val main = when {
            label.isEmpty() && style == "space" -> "空格"
            shift && label.length == 1 && label[0] in 'a'..'z' -> label.uppercase()
            else -> label
        }
        if (hint.isEmpty()) return main
        if (main.isEmpty()) {
            val ss = SpannableStringBuilder(hint)
            ss.setSpan(RelativeSizeSpan(0.62f), 0, ss.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return ss
        }
        val ss = SpannableStringBuilder(main).append("\n")
        val start = ss.length
        ss.append(hint)
        ss.setSpan(RelativeSizeSpan(0.55f), start, ss.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return ss
    }

    private fun candToAction(a: KbAction): KeyAction =
        KeyAction.Cand(a.arg.toIntOrNull() ?: 0)

    private fun toAction(a: KbAction?, shift: Boolean): KeyAction {
        if (a == null) return KeyAction.None
        return when (a.kind) {
            "sym" -> if (a.arg.isEmpty()) {
                KeyAction.None
            } else {
                KeyAction.Sym(a.arg[0].code, if (shift || a.shift) InputEngine.MASK_SHIFT else 0)
            }
            "key" -> if (a.arg.isEmpty()) KeyAction.None else KeyAction.Named(a.arg)
            "ctrl" -> if (a.arg.isEmpty()) KeyAction.None else KeyAction.Ctrl(a.arg[0])
            "text" -> KeyAction.Text(a.arg)
            "kb" -> if (a.arg.isEmpty()) KeyAction.None else KeyAction.Kb(a.arg)
            "toggle" -> KeyAction.Toggle(a.arg)
            "cand" -> KeyAction.Cand(a.arg.toIntOrNull() ?: 0)
            "cmd" -> KeyAction.Cmd(a.arg, a.option)
            else -> KeyAction.None
        }
    }

    private fun keyBackground(): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(Color.parseColor("#90A4AE")))
        addState(intArrayOf(), ColorDrawable(Color.parseColor("#FFFFFF")))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val LONG_PRESS_MS = 400L
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 55L

        /** 缝隙兜底命中的最大距离（dp）。 */
        const val TOUCH_SLOP_DP = 12f

        /** 判定滑动所需的最小位移（dp）。 */
        const val SWIPE_SLOP_DP = 16f

        const val DIR_UP = 0
        const val DIR_DOWN = 1
        const val DIR_LEFT = 2
        const val DIR_RIGHT = 3
    }
}
