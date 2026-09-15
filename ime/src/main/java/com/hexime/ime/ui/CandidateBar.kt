package com.hexime.ime.ui

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.hexime.ime.engine.Candidate

/** 极简候选栏：一行横向可滚动，点击选词。 */
class CandidateBar(context: Context) : HorizontalScrollView(context) {

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
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
        removeAllViews()
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
        removeAllViews()
        row.removeAllViews()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
