package com.hexime.ime.ui

import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.hexime.ime.data.XiaoheCode

/**
 * 加词窗口：**在键盘上直接弹出**，手输/粘贴词，编码默认自动查表生成，**也可以自己改**。
 *
 * 编码规则：自动查表给出默认值，跟着词一起变；一旦用户手动动过编码框，就固定住不再覆盖
 * （想恢复自动点「↺ 自动」）。编码长度上限 4 —— 方案里 `speller/max_code_length` 就是 4，
 * 更长的码在键盘上根本打不出来。
 *
 * 窗口类型必须是 `TYPE_APPLICATION_OVERLAY`：IME 里要弹一个**可聚焦**（能输字）的窗口，
 * TYPE_APPLICATION_ATTACHED_DIALOG + IME token、TYPE_INPUT_METHOD 都会
 * `BadTokenException`（几种 window type 都实测过）。需要 SYSTEM_ALERT_WINDOW 权限。
 */
class AddWordDialog(
    private val context: Context,
    private val palette: Palette,
    private val onConfirm: (word: String, code: String) -> Unit,
) {

    private var dialog: Dialog? = null
    private var wordView: EditText? = null
    private var codeView: EditText? = null
    private var noteView: TextView? = null

    /** 用户手动改过编码（改过就不再被自动查表覆盖）。 */
    private var codeEdited = false

    /** 自动写入编码时屏蔽自己的监听器（否则会被当成"用户改的"）。 */
    private var writingCode = false

    private val codeFilter = InputFilter { src, _, _, dest, dstart, dend ->
        val allowed = "abcdefghijklmnopqrstuvwxyz;/ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val kept = StringBuilder()
        for (ch in src) {
            if (ch in allowed) kept.append(ch.lowercaseChar())
        }
        // 还要保证拼完不超过 4 位
        val room = (MAX_CODE - (dest.length - (dend - dstart))).coerceAtLeast(0)
        val out = if (kept.length > room) kept.substring(0, room) else kept.toString()
        // 和原文一样就返回 null（让系统自己插入），否则给出过滤/小写化后的结果
        if (out == src.toString()) null else out
    }

    fun show(initialWord: String = "") {
        dismiss()
        val pad = dp(14)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(palette.candidateBg)
        }

        root.addView(label("加词", 16f, palette.candidateText))

        val word = EditText(context).apply {
            hint = "输入或粘贴词，如：小鹤音形"
            setText(initialWord)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(palette.candidateText)
            setHintTextColor(palette.candidateComment)
            setSingleLine()
            setPadding(pad, pad, pad, pad)
        }
        wordView = word
        root.addView(
            word,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        // 编码：默认自动生成，可手改
        val codeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        codeRow.addView(label("编码", 14f, palette.candidateComment))
        val code = EditText(context).apply {
            hint = "自动生成"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(palette.candidateText)
            setHintTextColor(palette.candidateComment)
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(codeFilter)
            setPadding(pad, dp(6), pad, dp(6))
        }
        codeView = code
        codeRow.addView(
            code,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        codeRow.addView(button("↺ 自动") { resetToAuto() })
        root.addView(codeRow)

        val note = label("", 12f, palette.candidateComment)
        noteView = note
        root.addView(note)

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(button("剪贴板") {
            val clip = clipboardText()
            if (clip != null) {
                wordView?.setText(clip)
                wordView?.setSelection(clip.length)
            }
        })
        buttons.addView(button("取消") { dismiss() })
        buttons.addView(button("确定") { confirm() })
        root.addView(buttons)

        // 词变了 → 没手动改过编码就跟着重算
        wordView?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = updateCode(s?.toString().orEmpty())
        })
        // 编码被手动改过 → 锁住自动生成
        codeView?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (writingCode) return
                codeEdited = (s?.toString().orEmpty().isNotEmpty())
                updateNote()
            }
        })
        updateCode(initialWord)

        val d = Dialog(context)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.setContentView(root)
        d.setCanceledOnTouchOutside(true)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(0x00000000))
            val lp = attributes
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            lp.gravity = Gravity.TOP
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            attributes = lp
        }
        d.show()
        dialog = d
        // 让词输入框拿到焦点（弹窗先 show 再请求焦点，顺序反了会拿不到）
        wordView?.requestFocus()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
        // 字表 ~2MB 常驻，只有这个窗口用得到 → 关掉就放掉（下次打开重新读）
        XiaoheCode.release()
    }

    private fun confirm() {
        val word = wordView?.text?.toString()?.trim().orEmpty()
        val code = codeView?.text?.toString()?.trim()?.lowercase().orEmpty()
        if (word.isEmpty()) {
            setNote("先输入词")
            return
        }
        if (code.isEmpty()) {
            setNote("编码不能空 —— 点「↺ 自动」让它自动查表")
            return
        }
        if (code.length > MAX_CODE) {
            setNote("编码最多 $MAX_CODE 位")
            return
        }
        onConfirm(word, code)
        dismiss()
    }

    /** 恢复自动：清掉手动标记，按当前词重新查表。 */
    private fun resetToAuto() {
        codeEdited = false
        updateCode(wordView?.text?.toString().orEmpty())
    }

    private fun updateCode(word: String) {
        if (codeEdited) {
            updateNote()
            return
        }
        val w = word.trim()
        writingCode = true
        try {
            if (w.isEmpty()) {
                codeView?.setText("")
            } else {
                codeView?.setText(XiaoheCode.lookup(context, w).orEmpty())
            }
        } finally {
            writingCode = false
        }
        updateNote()
    }

    private fun updateNote() {
        val w = wordView?.text?.toString()?.trim().orEmpty()
        val code = codeView?.text?.toString().orEmpty()
        when {
            w.isEmpty() -> setNote("输入词后自动查编码；编码也可以自己改")
            codeEdited -> setNote("编码是你自己填的，不会再被覆盖（点「↺ 自动」恢复查表）")
            code.isEmpty() -> setNote("查不到编码（词里有表外字）—— 可以自己填一个")
            else -> setNote("自动查表：打 $code 出「$w」")
        }
    }

    private fun setNote(text: String) {
        noteView?.text = text
    }

    private fun clipboardText(): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context).toString().trim().ifEmpty { null }
    }

    private fun label(text: String, size: Float, color: Int) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        setPadding(dp(6), dp(6), dp(6), dp(6))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(context).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        /** 方案里 `speller/max_code_length` 是 4，更长的码在键盘上打不出来。 */
        const val MAX_CODE = 4
    }
}
