package com.hexime.ime.ui

import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.text.Editable
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
 * 加词窗口：**在键盘上直接弹出**，手输/粘贴词，自动查小鹤编码，确定后写进用户词表。
 *
 * 窗口类型必须是 `TYPE_APPLICATION_OVERLAY`：IME 里要弹一个**可聚焦**（能输字）的窗口，
 * TYPE_APPLICATION_ATTACHED_DIALOG + IME token、TYPE_INPUT_METHOD 都会
 * `BadTokenException`（trime2 上实测过的矩阵）。需要 SYSTEM_ALERT_WINDOW 权限。
 */
class AddWordDialog(
    private val context: Context,
    private val palette: Palette,
    private val onConfirm: (word: String, code: String) -> Unit,
) {

    private var dialog: Dialog? = null
    private var wordView: EditText? = null
    private var codeView: TextView? = null
    private var currentCode: String? = null

    fun show(initialWord: String = "") {
        dismiss()
        val pad = dp(14)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(palette.candidateBg)
        }

        root.addView(label("加词（自动查码）", 16f, palette.candidateText))

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

        val code = label("编码：—", 15f, palette.candidateComment)
        codeView = code
        root.addView(code)

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

        wordView?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = updateCode(s?.toString().orEmpty())
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
        val code = currentCode
        if (word.isEmpty()) {
            codeView?.text = "先输入词"
            return
        }
        if (code.isNullOrEmpty()) {
            codeView?.text = "编码：算不出（词里有表外字）"
            return
        }
        onConfirm(word, code)
        dismiss()
    }

    private fun updateCode(word: String) {
        val w = word.trim()
        if (w.isEmpty()) {
            currentCode = null
            codeView?.text = "编码：—"
            return
        }
        val code = XiaoheCode.lookup(context, w)
        currentCode = code
        codeView?.text = if (code == null) {
            "编码：算不出（词里有表外字）"
        } else {
            "编码：$code　（确定后按 $code 打出来）"
        }
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
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(context).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
