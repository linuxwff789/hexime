package com.hexime.ime.ui

import android.content.Context
import org.json.JSONObject

/**
 * 按键动作。kind 由 `tools/kbconvert.lua` 在转换期就归一化好：
 *   sym    直接按键（arg = 字符，如 "q"、"+"、1）
 *   key    具名功能键（arg = BackSpace / space / Return / Up / Tab / copy ...）
 *   ctrl   Ctrl+字母（arg = "a"）
 *   text   按键序列宏（arg = "{End}{Shift+Home}{Control+c}"）
 *   kb     切键盘（arg = 键盘 id）
 *   toggle 开关（arg = ascii_mode / simplification / _hide_candidate ...）
 *   cand   选第 N 候选（arg = "2"/"3"）
 *   cmd    主题脚本类功能（arg = settings / kbheight / date / run / web / addword / deploy）
 */
data class KbAction(
    val kind: String,
    val arg: String = "",
    val option: String = "",
    val shift: Boolean = false,
)

/** 一个按键。width 是屏宽百分比（行内求和 = 100）。 */
data class KeyDef(
    val width: Float,
    val label: String,
    val hint: String,
    val style: String,
    /** 键角上要显示的长按字符。 */
    val longLabel: String = "",
    /** 键面图标：单色 PNG 名（assets/icons/<icon>.png），按配色染色画在键面。 */
    val icon: String = "",
    /** 状态类按键的第二个图标（states[1]），开关打开时用。 */
    val iconAlt: String = "",
    val click: KbAction? = null,
    val long: KbAction? = null,
    /** 组合中按此键 = 选第 N 候选（布局里的 composing = delimiterJ/S）。 */
    val composing: KbAction? = null,
    val swipeUp: KbAction? = null,
    val swipeDown: KbAction? = null,
    val swipeLeft: KbAction? = null,
    val swipeRight: KbAction? = null,
    /** ascii_mode 下的替身（布局里的 ascii 字段，如 ascii = "!"）。 */
    val asciiAlt: KbAction? = null,
)

data class RowDef(val heightPercent: Float, val keys: List<KeyDef>)

data class KeyboardDef(
    val id: String,
    val name: String,
    val keyWidth: Float,
    val asciiDefault: Boolean,
    val rows: List<RowDef>,
)

/** 键盘仓库：读 assets/keyboards/<id>.json，解析结果进程内缓存。 */
object KeyboardRepo {

    const val DEFAULT_ID = "default"

    private val cache = HashMap<String, KeyboardDef>()

    fun load(context: Context, id: String): KeyboardDef? {
        cache[id]?.let { return it }
        val text = try {
            context.assets.open("keyboards/$id.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return null
        }
        val def = parse(text) ?: return null
        cache[id] = def
        return def
    }

    /** 已随包发布的键盘 id。 */
    fun ids(context: Context): List<String> =
        (context.assets.list("keyboards") ?: emptyArray())
            .filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json") }
            .sorted()

    fun displayName(context: Context, id: String): String =
        load(context, id)?.name?.takeIf { it.isNotEmpty() } ?: id

    private fun parse(text: String): KeyboardDef? = try {
        val o = JSONObject(text)
        val rowsJson = o.optJSONArray("rows") ?: return null
        val rows = ArrayList<RowDef>(rowsJson.length())
        for (i in 0 until rowsJson.length()) {
            val r = rowsJson.getJSONObject(i)
            val keysJson = r.optJSONArray("keys") ?: continue
            val keys = ArrayList<KeyDef>(keysJson.length())
            for (j in 0 until keysJson.length()) {
                val k = keysJson.getJSONObject(j)
                keys.add(
                    KeyDef(
                        width = k.optDouble("w", 10.0).toFloat(),
                        label = k.optString("label", ""),
                        hint = k.optString("hint", ""),
                        style = k.optString("style", ""),
                        longLabel = k.optString("longLabel", ""),
                        icon = k.optString("icon", ""),
                        iconAlt = k.optString("iconAlt", ""),
                        click = action(k.optJSONObject("click")),
                        long = action(k.optJSONObject("long")),
                        composing = action(k.optJSONObject("composing")),
                        swipeUp = action(k.optJSONObject("up")),
                        swipeDown = action(k.optJSONObject("down")),
                        swipeLeft = action(k.optJSONObject("left")),
                        swipeRight = action(k.optJSONObject("right")),
                        asciiAlt = action(k.optJSONObject("ascii")),
                    ),
                )
            }
            rows.add(RowDef(r.optDouble("h", 25.0).toFloat(), keys))
        }
        KeyboardDef(
            id = o.optString("id", ""),
            name = o.optString("name", ""),
            keyWidth = o.optDouble("keyWidth", 10.0).toFloat(),
            asciiDefault = o.optBoolean("ascii", false),
            rows = rows,
        )
    } catch (e: Exception) {
        null
    }

    private fun action(o: JSONObject?): KbAction? {
        if (o == null) return null
        val kind = o.optString("k", "none")
        if (kind.isEmpty() || kind == "none") return null
        return KbAction(
            kind = kind,
            arg = o.optString("v", ""),
            option = o.optString("opt", ""),
            shift = o.optString("mask", "") == "shift",
        )
    }
}
