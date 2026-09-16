package com.hexime.ime.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.hexime.ime.engine.Candidate

/**
 * 候选栏：一行横向可滚动的候选词，高度固定（没有候选时也占住同样的高度，输入时不跳行高）。
 *
 * 当前输入的编码不在这里显示 —— 它在上面那一行（原来是状态栏的位置，见 HeximeService）。
 *
 * 资源优化（打字热路径）：
 *  * 候选 TextView **池化复用**：按键不再 removeAllViews + 重建 N 个 View
 *    （旧实现每次按键都会 new N 个 TextView + 解析颜色 + LayoutParams + 监听器）。
 *  * 颜色/内边距/字号**只解析一次**，配色来自 [HeximeTheme]（跟随系统深色模式）。
 *  * 候选文本序列与上次相同则**整帧跳过**（不碰 View、不触发 requestLayout）。
 */
class CandidateBar(context: Context) : HorizontalScrollView(context) {

    var onSelect: ((Int) -> Unit)? = null

    private val palette = HeximeTheme.of(context)

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    // 只解析一次的样式
    private val padH = dp(14)
    private val padV = dp(6)
    private val textSizeSp = 20f

    /** 视图池：row 里最多有多少个候选，就一直复用这些 TextView。 */
    private val pool = ArrayList<TextView>(10)
    /** 上一次真正渲染出去的候选文本，用于跳过无变化的刷新。 */
    private val lastTexts = ArrayList<String>(10)
    /** 本次的候选文本（复用同一个 ArrayList，避免每次按键分配）。 */
    private val pending = ArrayList<String>(10)

    init {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(palette.barBg)
        // 固定高度：有没有候选都一样高，避免输入时整个键盘上下跳
        minimumHeight = dp(46)
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    fun setCandidates(candidates: List<Candidate>) {
        pending.clear()
        for (candidate in candidates) pending.add(candidate.text)
        if (pending == lastTexts) return // 候选没变：一行 View 操作都不做

        if (pool.size < candidates.size) growPool(candidates.size)
        for (i in candidates.indices) bind(pool[i], i, candidates[i].text)
        for (i in candidates.size until pool.size) hide(pool[i])

        lastTexts.clear()
        lastTexts.addAll(pending)
        if (scrollX != 0) scrollTo(0, 0)
    }

    fun clear() {
        lastTexts.clear()
        for (view in pool) hide(view)
    }

    private fun growPool(size: Int) {
        while (pool.size < size) {
            val tv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                setTextColor(palette.barText)
                setPadding(padH, padV, padH, padV)
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { onSelect?.invoke(tag as Int) }
            }
            pool.add(tv)
            row.addView(tv)
        }
    }

    private fun bind(view: TextView, index: Int, text: String) {
        view.tag = index
        val label = if (index < 9) "${index + 1} $text" else text
        // setText 内部会 requestLayout，内容没变就不要设
        if (view.text?.toString() != label) view.text = label
        if (view.visibility != VISIBLE) view.visibility = VISIBLE
    }

    private fun hide(view: TextView) {
        if (view.visibility != View.GONE) view.visibility = View.GONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
