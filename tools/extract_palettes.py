#!/usr/bin/env python3
"""从 trime2 小鹤主题的 12 个配色里抽出 hexime 需要的色板字段。

用法: python3 extract_palettes.py <theme_dir> <dump_json_dir> [--kt out.kt]
"""
import glob
import json
import os
import sys

sys.path.insert(0, os.path.expanduser("~/.hermes/skills/android/trime-ime-themes/scripts"))
from style_audit import find_image, img_stats  # noqa: E402


def resolve(node, theme_dir, style_dir):
    """色值可能是 int(0xAARRGGBB) 或 png 文件名 → 返回 (rgb, alpha) 或 None。"""
    if node is None:
        return None
    if isinstance(node, int):
        return ((node >> 16) & 0xFF, (node >> 8) & 0xFF, node & 0xFF), (node >> 24) & 0xFF
    if isinstance(node, str):
        p = find_image(node, style_dir, os.path.join(theme_dir, "images"))
        if not p:
            return None
        st = img_stats(p)
        if not st:
            return None
        return st[0], 255
    return None


def argb(node, theme_dir, style_dir, fallback=(0, 0, 0)):
    r = resolve(node, theme_dir, style_dir)
    rgb = r[0] if r else fallback
    return "0xFF%02X%02X%02X" % rgb


def blend(a, b, t):
    """a、b 是 (r,g,b)，t=0 全 a，1 全 b。"""
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def contrast_ratio(hex1, hex2):
    a, b = rgb_of(hex1), rgb_of(hex2)
    l1, l2 = lum(a), lum(b)
    if l1 < l2:
        l1, l2 = l2, l1
    return (l1 + 0.05) / (l2 + 0.05)


def argb_from_rgb(rgb):
    return "0xFF%02X%02X%02X" % rgb


def rgb_of(argb_hex):
    """把 "0xFFRRGGBB" 拆回 (r,g,b) 当 fallback 用。"""
    c = int(argb_hex, 16)
    return ((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF)


def get(tbl, *path):
    node = tbl
    for k in path:
        if isinstance(node, dict) and k in node:
            node = node[k]
        else:
            return None
    return node


def lum(rgb):
    def f(x):
        x /= 255.0
        return x / 12.92 if x <= 0.03928 else ((x + 0.055) / 1.055) ** 2.4
    return 0.2126 * f(rgb[0]) + 0.7152 * f(rgb[1]) + 0.0722 * f(rgb[2])


def contrast_color(bg_argb, prefer_light="0xFFFFFFFF", prefer_dark="0xFF212121"):
    c = int(bg_argb, 16)
    rgb = ((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF)
    return prefer_dark if lum(rgb) > 0.5 else prefer_light


def main():
    theme_dir = sys.argv[1]
    dump_dir = sys.argv[2]
    out_kt = None
    if "--kt" in sys.argv:
        out_kt = sys.argv[sys.argv.index("--kt") + 1]

    palettes = []
    for f in sorted(glob.glob(os.path.join(dump_dir, "style_*.json"))):
        base = os.path.basename(f)[len("style_"):-len(".json")]
        # 目录名（含 ／ 空格）还原
        name = base.replace("___Lost_Temple", "／Lost Temple").replace("___Dark_Temple", "／Dark Temple")
        style_dir = os.path.join(theme_dir, "styles", name)
        if not os.path.isdir(style_dir):
            # 兜底：按前缀找目录
            cands = [d for d in glob.glob(os.path.join(theme_dir, "styles", "*")) if os.path.basename(d).startswith(base[:2])]
            if not cands:
                print("!! 找不到样式目录:", base)
                continue
            style_dir = cands[0]
        tbl = json.load(open(f, encoding="utf-8"))

        page_bg = argb(tbl.get("background"), theme_dir, style_dir)
        kb_bg = argb(get(tbl, "keyboard", "background"), theme_dir, style_dir, fallback=(0, 0, 0))
        key_face = argb(get(tbl, "key", "background"), theme_dir, style_dir)
        key_text = argb(get(tbl, "key", "text_color"), theme_dir, style_dir)
        # 小字（hint/字根）和键角长按字符的颜色 —— 小鹤叫 key_symbol_color
        key_symbol = argb(get(tbl, "key", "key_symbol_color"), theme_dir, style_dir, fallback=rgb_of(key_text))
        # 键边框：浅色几套配色的差别很大（e9e9e9 / d7dade / c1c7ca / d1d5db），不搬就看不出区别
        key_border = argb(get(tbl, "key", "border_color"), theme_dir, style_dir, fallback=rgb_of(key_face))
        key_pressed = argb(get(tbl, "key", "pressed", "background"), theme_dir, style_dir, fallback=(0x90, 0xA4, 0xAE))
        key_pressed_text = get(tbl, "key", "pressed", "text_color")
        if key_pressed_text is None:
            key_pressed_text = contrast_color(key_pressed)
        else:
            key_pressed_text = argb(key_pressed_text, theme_dir, style_dir)
        # 功能键（⌫/⇧/选单…）、回车、空格：小鹤主题里各自一套底色（functional/enter/space）
        func_face = argb(get(tbl, "functional", "background"), theme_dir, style_dir, fallback=rgb_of(key_face))
        func_text = argb(get(tbl, "functional", "text_color"), theme_dir, style_dir, fallback=rgb_of(key_text))
        func_symbol = argb(get(tbl, "functional", "key_symbol_color"), theme_dir, style_dir, fallback=rgb_of(func_text))
        func_pressed = argb(get(tbl, "functional", "pressed", "background"), theme_dir, style_dir, fallback=rgb_of(key_face))
        func_pressed_text = argb(get(tbl, "functional", "pressed", "text_color"), theme_dir, style_dir, fallback=rgb_of(key_pressed_text))
        enter_face = argb(get(tbl, "enter", "background"), theme_dir, style_dir, fallback=rgb_of(func_face))
        enter_text = argb(get(tbl, "enter", "text_color"), theme_dir, style_dir, fallback=rgb_of(func_text))
        enter_pressed = argb(get(tbl, "enter", "pressed", "background"), theme_dir, style_dir, fallback=rgb_of(func_pressed))
        enter_pressed_text = argb(get(tbl, "enter", "pressed", "text_color"), theme_dir, style_dir, fallback=rgb_of(func_pressed_text))
        space_face = argb(get(tbl, "space", "background"), theme_dir, style_dir, fallback=rgb_of(key_face))
        space_text = argb(get(tbl, "space", "text_color"), theme_dir, style_dir, fallback=rgb_of(key_text))
        # 角标色和键面几乎同色时（朝朝 key_symbol_color = 0xffffffff 配白键面）会看不见，
        # 这种就退回「键字色往键面方向压 55%」的淡色。
        def fix_symbol(sym, face, text):
            if contrast_ratio(sym, face) < 1.4:
                return argb_from_rgb(blend(rgb_of(text), rgb_of(face), 0.55))
            return sym

        key_symbol = fix_symbol(key_symbol, key_face, key_text)
        func_symbol = fix_symbol(func_symbol, func_face, func_text)

        # 顶栏/状态栏文字（trime2 顶层 text_color）
        status_text = argb(tbl.get("text_color"), theme_dir, style_dir, fallback=rgb_of(key_text))
        cand_bg = argb(get(tbl, "candidate", "background"), theme_dir, style_dir, fallback=(0xFF, 0xFF, 0xFF))
        cand_text = argb(get(tbl, "candidate", "text_color"), theme_dir, style_dir)
        cand_comment = get(tbl, "candidate", "comment", "text_color")
        cand_comment = argb(cand_comment, theme_dir, style_dir) if cand_comment is not None else cand_text
        cand_pressed = argb(get(tbl, "candidate", "pressed", "background"), theme_dir, style_dir, fallback=(0x90, 0xA4, 0xAE))

        # 键盘区底色：trime2 用 keyboard.background（整块），hexime 用它当键外底色
        p = {
            "id": base,
            "name": name,
            "pageBg": page_bg,
            "keyboardBg": kb_bg,
            "keyFace": key_face,
            "keyText": key_text,
            "keySymbol": key_symbol,
            "keyBorder": key_border,
            "keyPressed": key_pressed,
            "keyPressedText": key_pressed_text,
            "funcFace": func_face,
            "funcText": func_text,
            "funcSymbol": func_symbol,
            "funcPressed": func_pressed,
            "funcPressedText": func_pressed_text,
            "enterFace": enter_face,
            "enterText": enter_text,
            "enterPressed": enter_pressed,
            "enterPressedText": enter_pressed_text,
            "spaceFace": space_face,
            "spaceText": space_text,
            "statusText": status_text,
            "candidateBg": cand_bg,
            "candidateText": cand_text,
            "candidateComment": cand_comment,
            "candidatePressed": cand_pressed,
        }
        palettes.append(p)
        print(f"{name:18s} face={key_face} text={key_text} sym={key_symbol} border={key_border} "
              f"func={func_face}/{func_text} enter={enter_face} kb={kb_bg}")

    if out_kt:
        # 展示顺序：默认浅色在前，深色在后（hexime 是极简键盘，先给常用的）
        order = ["小鹤", "朝朝", "纯白", "谷歌", "炫彩", "IOS", "孤寺／Lost Temple",
                 "夜色", "夜色2", "暮暮", "暗堂／Dark Temple", "哲豆黑"]
        palettes.sort(key=lambda p: order.index(p["name"]) if p["name"] in order else 99)
        lines = [
            "package com.hexime.ime.ui",
            "",
            "/**",
            " * 配色板 —— 由 tools/extract_palettes.py 从 trime2 小鹤主题的 styles/<配色>/main.lua 抽出，",
            " * 图片键面取该 png 的主色（hexime 没有皮肤系统，只用颜色）。改配色跑一遍脚本即可。",
            " */",
            "data class Palette(",
            "    val id: String,",
            "    val name: String,",
            "    val pageBg: Int,",
            "    val keyboardBg: Int,",
            "    val keyFace: Int,",
            "    val keyText: Int,",
            "    /** 小字/键角长按字符的颜色（trime2 key_symbol_color）。 */",
            "    val keySymbol: Int,",
            "    /** 键边框色（浅色几套配色的主要差别就在这）。 */",
            "    val keyBorder: Int,",
            "    val keyPressed: Int,",
            "    val keyPressedText: Int,",
            "    /** 功能键（⌫/⇧/选单…）。 */",
            "    val funcFace: Int,",
            "    val funcText: Int,",
            "    val funcSymbol: Int,",
            "    val funcPressed: Int,",
            "    val funcPressedText: Int,",
            "    /** 回车键。 */",
            "    val enterFace: Int,",
            "    val enterText: Int,",
            "    val enterPressed: Int,",
            "    val enterPressedText: Int,",
            "    /** 空格键。 */",
            "    val spaceFace: Int,",
            "    val spaceText: Int,",
            "    /** 顶栏/状态栏文字。 */",
            "    val statusText: Int,",
            "    val candidateBg: Int,",
            "    val candidateText: Int,",
            "    val candidateComment: Int,",
            "    val candidatePressed: Int,",
            ")",
            "",
            "object HeximePalettes {",
            "",
            "    val ALL: List<Palette> = listOf(",
        ]
        for p in palettes:
            lines.append("        Palette(")
            lines.append(f'            id = "{p["id"]}", name = "{p["name"]}",')
            for k in ("pageBg", "keyboardBg", "keyFace", "keyText", "keySymbol", "keyBorder",
                      "keyPressed", "keyPressedText", "funcFace", "funcText", "funcSymbol",
                      "funcPressed", "funcPressedText", "enterFace", "enterText",
                      "enterPressed", "enterPressedText", "spaceFace", "spaceText", "statusText",
                      "candidateBg", "candidateText", "candidateComment", "candidatePressed"):
                # 0xFF...... 超过 Int 范围，Kotlin 会推成 Long → 必须显式 .toInt()
                lines.append(f"            {k} = {p[k]}.toInt(),")
            lines.append("        ),")
        lines += [
            "    )",
            "",
            '    val DEFAULT_ID = "小鹤"',
            "",
            "    fun byId(id: String): Palette =",
            "        ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT_ID }",
            "}",
            "",
        ]
        open(out_kt, "w", encoding="utf-8").write("\n".join(lines))
        print("wrote", out_kt)


if __name__ == "__main__":
    main()
