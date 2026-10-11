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
        itemPool.forEach { it.setTextColor(palette.candidateText) }
        lastCandidates = emptyList() // 强制下次 setCandidates 重刷（颜色变了）
    }

    // ---------------------------------------------------------------- 配色选择模式

    private var pickerCallback: ((Int) -> Unit)? = null
    private val pickerViews = ArrayList<TextView>(HeximePalettes.ALL.size)

    /** 正在当配色选择条用（此时别被候选覆盖）。 */
    val inPicker: Boolean get() = pickerCallback != null

    /**
     * 候选栏变身配色条：每一项用它**自己的配色**画出来（底色/字色就是那套配色的），
     * 点一下回调下标。这样"键盘上就能改配色"且改之前先看到效果。
     */
    fun showPalettePicker(onPick: (Int) -> Unit) {
        pickerCallback = onPick
        clearPickerViews()
        // 候选先让位（它们和配色项共用同一个 row）
        itemPool.forEach { it.visibility = GONE }
        HeximePalettes.ALL.forEachIndexed { index, p ->
            val item = TextView(context).apply {
                text = p.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(p.candidateText)
                setBackgroundColor(p.candidateBg)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { pickerCallback?.invoke(index) }
            }
            pickerViews.add(item)
            row.addView(item)
        }
        scrollTo(0, 0)
    }

    fun clearPicker() {
        pickerCallback = null
        clearPickerViews()
        lastCandidates = emptyList() // 下次 setCandidates 重新把候选摆回来
    }

    private fun clearPickerViews() {
        pickerViews.forEach { row.removeView(it) }
        pickerViews.clear()
    }

    /** 候选 TextView 复用池：按键热路径上只改文字，不再每次 removeAllViews + 新建。 */
    private val itemPool = ArrayList<TextView>(10)

    /** 上一次的候选列表：内容没变就完全不碰 UI。 */
    private var lastCandidates: List<Candidate> = emptyList()

    fun setCandidates(candidates: List<Candidate>) {
        if (pickerCallback != null) return
        // 组合串每变一次候选就变一次；同一份候选（翻页边界/重复刷新）不要重排 UI
        if (candidates == lastCandidates) return
        lastCandidates = candidates

        while (itemPool.size < candidates.size) {
            val tv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(palette.candidateText)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                gravity = Gravity.CENTER
                isClickable = true
                isLongClickable = true
            }
            itemPool.add(tv)
            row.addView(tv)
        }
        itemPool.forEachIndexed { index, tv ->
            if (index >= candidates.size) {
                tv.visibility = GONE
                return@forEachIndexed
            }
            val candidate = candidates[index]
            tv.visibility = VISIBLE
            tv.text = if (index < 9) "${index + 1} ${candidate.text}" else candidate.text
            tv.setTextColor(palette.candidateText)
            tv.setOnClickListener { onSelect?.invoke(index) }
            // 长按 = 加词窗口（预填该候选词）
            tv.setOnLongClickListener {
                onLongSelect?.invoke(candidate.text)
                true
            }
        }
        scrollTo(0, 0)
    }

    fun clear() {
        lastCandidates = emptyList()
        itemPool.forEach { it.visibility = GONE }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
