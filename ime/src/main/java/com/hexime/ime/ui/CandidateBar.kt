package com.hexime.ime.ui

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.hexime.ime.engine.Candidate

/** 极简候选栏：一行横向可滚动，点击选词。 */
class CandidateBar(context: Context) : HorizontalScrollView(context) {

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(46)
    }

    var onSelect: ((Int) -> Unit)? = null

    init {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(Color.parseColor("#ECEFF1"))
        addView(
            row,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
        )
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
                setTextColor(Color.parseColor("#212121"))
                setPadding(dp(14), dp(6), dp(14), dp(6))
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { onSelect?.invoke(index) }
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
