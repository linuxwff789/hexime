package com.hexime.ime.data

import android.content.Context
import android.util.Log

/**
 * 小鹤音形「自动查码」：给一个词算出它在小鹤里该用什么码。
 *
 * 数据来源：`assets/xiaohe/char_code.txt`（由 tools/gen_char_code.py 生成：
 * 字 → 音码/声母/韵母键，多音字用 openfly 词表反查校验过）。
 *
 * 词组规则（用 openfly 的 6 万条词表反推 + 验证：2 字 96%、3 字 80%、4 字 94%、5 字 93%）：
 *   单字 = 音码（声母键+韵母键；形码没有数据，只给音码 2 键）
 *   二字 = 音码1 + 音码2
 *   三字 = 声1 + 声2 + (声3 + 韵3)
 *   四字 = 声1 + 声2 + 声3 + 声4
 *   五字以上 = 声1 + 声2 + 声3 + 声(末字)
 */
object XiaoheCode {

    private const val TAG = "XiaoheCode"
    private const val ASSET = "xiaohe/char_code.txt"

    /** 字 → [音码, 声母, 韵母键]，首次用到时读一次（~4 万字，几 MB 内存）。 */
    private var chars: Map<Char, Triple<String, String, String>>? = null

    /** 查一个词的编码；算不出来返回 null（词里有表外的字）。 */
    fun lookup(context: Context, word: String): String? {
        val w = word.trim()
        if (w.isEmpty()) return null
        val table = table(context) ?: return null
        val n = w.length
        val out = StringBuilder()
        for ((i, ch) in w.withIndex()) {
            val e = table[ch] ?: return null
            val (yin, sheng, yun) = e
            if (n >= 5 && i in 3 until n - 1) continue // 五字以上：前三字 + 末字
            when {
                n <= 2 -> out.append(yin)
                n == 3 -> {
                    out.append(sheng)
                    if (i == 2) out.append(yun)
                }
                else -> out.append(sheng)
            }
        }
        val code = out.toString()
        return code.ifEmpty { null }
    }

    private fun table(context: Context): Map<Char, Triple<String, String, String>>? {
        chars?.let { return it }
        val map = HashMap<Char, Triple<String, String, String>>(41000)
        try {
            context.assets.open(ASSET).bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line[0] == '#') continue
                    val p = line.split('\t')
                    if (p.size < 2 || p[0].length != 1) continue
                    map[p[0][0]] = Triple(p[1], p.getOrElse(2) { "" }, p.getOrElse(3) { "" })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "load $ASSET failed", e)
            return null
        }
        Log.i(TAG, "char table loaded: ${map.size}")
        chars = map
        return map
    }
}
