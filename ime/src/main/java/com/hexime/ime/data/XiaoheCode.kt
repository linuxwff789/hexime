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
 *
 * 存储：4 万字放 `HashMap<Char, Triple<String,String,String>>` 的话，箱化的 Character + Node +
 * Triple + 3 个短 String 各带一份对象头，量级 ≈ 8MB 常驻。改成**有序 CharArray + 等长 packed
 * 字符串数组**（值 = `"音码\u0001声母\u0001韵母键"`）后 ≈ 2MB，查表走二分（不装箱、不走 hash 桶）。
 * 加词窗口关掉时 [release] 直接放掉 —— 只有加词这一个入口用到它。
 */
object XiaoheCode {

    private const val TAG = "XiaoheCode"
    private const val ASSET = "xiaohe/char_code.txt"
    private const val SEP = '\u0001'

    /** 按字升序的键，与 [vals] 一一对应。 */
    private var keys: CharArray? = null

    /** 与 [keys] 对应的 packed 值：`"音码\u0001声母\u0001韵母键"`。 */
    private var vals: Array<String>? = null

    /** 放掉字表（加词窗口关闭时调用）。下次 lookup 会重新读一次（约 40ms）。 */
    fun release() {
        keys = null
        vals = null
    }

    /** 查一个词的编码；算不出来返回 null（词里有表外的字）。 */
    fun lookup(context: Context, word: String): String? {
        val w = word.trim()
        if (w.isEmpty()) return null
        if (!ensureLoaded(context)) return null
        val n = w.length
        val out = StringBuilder()
        for ((i, ch) in w.withIndex()) {
            val packed = find(ch) ?: return null
            if (n >= 5 && i in 3 until n - 1) continue // 五字以上：前三字 + 末字
            when {
                n <= 2 -> out.append(field(packed, 0)) // 音码
                n == 3 -> {
                    out.append(field(packed, 1)) // 声母
                    if (i == 2) out.append(field(packed, 2)) // 末字补韵母键
                }
                else -> out.append(field(packed, 1)) // 声母
            }
        }
        return out.toString().ifEmpty { null }
    }

    /** 二分查字（有序数组，不用 hash、不装箱）。 */
    private fun find(ch: Char): String? {
        val k = keys ?: return null
        val v = vals ?: return null
        var lo = 0
        var hi = k.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = k[mid]
            when {
                c == ch -> return v[mid]
                c < ch -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return null
    }

    /** 从 packed 值里取第 index 段（0=音码 1=声母 2=韵母键）。 */
    private fun field(packed: String, index: Int): String {
        var start = 0
        var n = 0
        while (n < index) {
            val i = packed.indexOf(SEP, start)
            if (i < 0) return ""
            start = i + 1
            n++
        }
        val end = packed.indexOf(SEP, start)
        return if (end < 0) packed.substring(start) else packed.substring(start, end)
    }

    private fun ensureLoaded(context: Context): Boolean {
        if (keys != null) return true
        // 读的时候先用 map 去重（后出现的覆盖先出现的，和原来一致），读完立刻换成两个数组。
        val map = HashMap<Char, String>(45000)
        try {
            context.assets.open(ASSET).bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line[0] == '#') continue
                    val p = line.split('\t')
                    if (p.size < 2 || p[0].length != 1) continue
                    map[p[0][0]] = p[1] + SEP + p.getOrElse(2) { "" } + SEP + p.getOrElse(3) { "" }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "load $ASSET failed", e)
            return false
        }
        val n = map.size
        if (n == 0) return false
        val tmpKeys = CharArray(n)
        val tmpVals = arrayOfNulls<String>(n)
        var i = 0
        for ((ch, packed) in map) {
            tmpKeys[i] = ch
            tmpVals[i] = packed
            i++
        }
        val order = (0 until n).sortedBy { tmpKeys[it] }
        val sortedKeys = CharArray(n)
        val sortedVals = arrayOfNulls<String>(n)
        for (j in 0 until n) {
            sortedKeys[j] = tmpKeys[order[j]]
            sortedVals[j] = tmpVals[order[j]]
        }
        keys = sortedKeys
        @Suppress("UNCHECKED_CAST")
        vals = sortedVals as Array<String>
        Log.i(TAG, "char table loaded: $n")
        return true
    }
}
