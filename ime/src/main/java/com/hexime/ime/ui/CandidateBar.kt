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
 * 候选栏 = 左侧「编码」+ 右侧可滚动的候选词。
 *
 * 编码（preedit）单独放在最左边，候选词再多也不会把编码挤走 ——
 * 输入框里字太小/被滚走时也能看到自己打了什么。
 *
 * 资源优化（打字热路径）：
 *  * 候选 TextView **池化复用**：按键不再 removeAllViews + 重建 N 个 View
 *    （旧实现每次按键都会 new N 个 TextView + 解析颜色 + LayoutParams + 监听器）。
 *  * 颜色/内边距/字号**只解析一次**，配色来自 [HeximeTheme]（跟随系统深色模式）。
 *  * 候选文本序列与上次相同则**整帧跳过**（不碰 View、不触发 requestLayout）。
 */
class CandidateBar(context: Context) : LinearLayout(context) {

    var onSelect: ((Int) -> Unit)? = null

    private val palette = HeximeTheme.of(context)

    /** 左侧编码显示。 */
    private val codeView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTextColor(palette.code)
        setPadding(dp(12), 0, dp(8), 0)
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        visibility = GONE
        // 编码长了自己截断，不挤压候选区
        ellipsize = android.text.TextUtils.TruncateAt.START
        maxWidth = dp(360)
    }

    private val row = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val scroller = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
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
    private var lastComposition = ""

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(palette.barBg)
        minimumHeight = dp(46)
        addView(codeView)
        addView(scroller, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    /** 显示当前输入的编码（组合串）。空串则隐藏。 */
    fun setComposition(text: String) {
        if (text == lastComposition) return
        lastComposition = text
        codeView.text = text
        codeView.visibility = if (text.isEmpty()) GONE else VISIBLE
        // 编码出现/消失会改变左侧宽度，候选区重新布局一次即可
        if (scrollX != 0) scroller.scrollTo(0, 0)
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
        if (scroller.scrollX != 0) scroller.scrollTo(0, 0)
    }

    fun clear() {
        if (lastTexts.isEmpty() && codeView.visibility == GONE) return
        lastTexts.clear()
        for (view in pool) hide(view)
        setComposition("")
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
