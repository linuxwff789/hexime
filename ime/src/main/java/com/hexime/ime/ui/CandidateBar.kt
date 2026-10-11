package com.hexime.ime.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.hexime.ime.data.HeximeSettings
import com.hexime.ime.engine.Candidate

/** 极简候选栏：一行横向可滚动，点击选词。配色跟随 HeximePalettes。 */
class CandidateBar(context: Context) : HorizontalScrollView(context) {

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(46)
    }

    var onSelect: ((Int) -> Unit)? = null

    /** 长按候选：快捷加词（把该候选词写进自定义词表，码取当前组合区）。 */
    var onLongSelect: ((String) -> Unit)? = null

    private var palette: Palette = HeximePalettes.byId(HeximeSettings.paletteId(context))

    init {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(palette.candidateBg)
        addView(
            row,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
        )
    }

    /** 换配色：底色立刻变，候选文字在下次 setCandidates 时刷新。 */
    fun applyPalette(palette: Palette) {
        this.palette = palette
        setBackgroundColor(palette.candidateBg)
        for (i in 0 until row.childCount) {
            (row.getChildAt(i) as? TextView)?.setTextColor(palette.candidateText)
        }
    }

    fun setCandidates(candidates: List<Candidate>) {
        // 注意：只清空 row 的子视图，不能 removeAllViews()，
        // 否则会把 row 本身从 HorizontalScrollView 里移除。
        row.removeAllViews()
        if (candidates.isEmpty()) return

        candidates.forEachIndexed { index, candidate ->
            val view = TextView(context).apply {
                text = if (index < 9) "${index + 1} ${candidate.text}" else candidate.text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(palette.candidateText)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { onSelect?.invoke(index) }
                // 长按 = 快捷加词（这个候选词 + 当前组合区的码，写进 custom_phrase 后置顶）
                isLongClickable = true
                setOnLongClickListener {
                    onLongSelect?.invoke(candidate.text)
                    true
                }
            }
            row.addView(view)
        }
        scrollTo(0, 0)
    }

    fun clear() {
        row.removeAllViews()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
