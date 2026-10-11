#!/usr/bin/env python3
"""生成 assets/xiaohe/char_code.txt —— 自动编码用的「字 → 小鹤音形部件」表。

用法: python3 tools/gen_char_code.py [rime_dir] [out_file]
  默认 rime_dir = ime/src/main/assets/rime
  默认 out_file = ime/src/main/assets/xiaohe/char_code.txt

输出每行: 字 <TAB> 音码 <TAB> 声母 <TAB> 韵母键
  * 音码   = 声母键 + 韵母键；零声母音节(ai/an/ao/e/er/ou…)直接写拼音
  * 声母   = 词组码里要用的首键（zh→v ch→i sh→u；零声母取拼音首字母）
  * 韵母键 = 三字词尾字要用的韵母键

拼音取自 rime-luna-pinyin 的 luna_pinyin.dict.yaml；多音字**用 openfly 词表反查校验**
（该字在小鹤里的音码前缀能对上的那个读音优先），避免 luna 里 开\tjian\t95% 这类脏数据。
"""
import os
import re
import sys
from collections import defaultdict

SHENG = {"zh": "v", "ch": "i", "sh": "u"}
YM = {
    "a": "a", "ai": "d", "an": "j", "ang": "h", "ao": "c", "e": "e", "ei": "w", "en": "f",
    "eng": "g", "er": "r", "i": "i", "ia": "x", "ian": "m", "iang": "l", "iao": "n", "ie": "p",
    "in": "b", "ing": "k", "iong": "s", "iu": "q", "o": "o", "ong": "s", "ou": "z", "u": "u",
    "ua": "x", "uai": "k", "uan": "r", "uang": "l", "ui": "v", "un": "y", "uo": "o",
    "v": "v", "ve": "t", "van": "r", "vn": "y",
}
ZERO = ("a", "ai", "an", "ang", "ao", "e", "ei", "en", "eng", "er", "o", "ou")


def parse_py(py):
    py = py.strip().lower().replace("ü", "v")
    for s in ("zh", "ch", "sh"):
        if py.startswith(s):
            return s, py[2:]
    if py and py[0] in "bpmfdtnlgkhjqxrzcsyw":
        return py[0], py[1:]
    return "", py


def yin(py):
    """音码：有声母 = 声母键+韵母键；零声母 = 拼音本身。"""
    s, ym = parse_py(py)
    if s == "":
        return ym if ym in ZERO else None
    return (SHENG.get(s, s) + YM[ym]) if ym in YM else None


def sheng(py):
    s, ym = parse_py(py)
    return (SHENG.get(s, s) if s else ym[:1]) if (s or ym) else None


def yunk(py):
    s, ym = parse_py(py)
    return YM.get(ym)


def load_dicts(rime_dir):
    words, char_codes = defaultdict(set), defaultdict(set)
    for name in sorted(os.listdir(rime_dir)):
        if not name.startswith("openfly") or not name.endswith(".dict.yaml"):
            continue
        if "reverse" in name:
            continue
        with open(os.path.join(rime_dir, name), encoding="utf-8") as fh:
            for line in fh:
                if line.startswith("#") or "\t" not in line:
                    continue
                p = line.rstrip("\n").split("\t")
                if len(p) < 2 or not p[0] or not p[1]:
                    continue
                words[p[0]].add(p[1])
                if len(p[0]) == 1:
                    char_codes[p[0]].add(p[1])
    return words, char_codes


def load_readings(rime_dir):
    readings = defaultdict(list)
    with open(os.path.join(rime_dir, "luna_pinyin.dict.yaml"), encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("#") or "\t" not in line:
                continue
            p = line.rstrip("\n").split("\t")
            if len(p) < 2 or len(p[0]) != 1:
                continue
            w = 0.0
            if len(p) > 2:
                m = re.match(r"([\d.]+)", p[2])
                if m:
                    w = float(m.group(1))
            readings[p[0]].append((p[1], w))
    return readings


def best_reading(readings, char_codes):
    """同字多音：优先取「音码能对上 openfly 里该字某条码的前缀」的读音。"""
    out = {}
    for ch, rs in readings.items():
        cands = char_codes.get(ch, set())
        best = None
        for py, _w in sorted(rs, key=lambda x: -x[1]):
            y = yin(py)
            if y and any(c.startswith(y) for c in cands):
                best = py
                break
        out[ch] = best if best else max(rs, key=lambda x: x[1])[0]
    return out


def code_of(word, char_py):
    """按小鹤音形词组规则算全码（4 键）；单字只给音码（形码没有数据）。"""
    n = len(word)
    if n == 0:
        return None
    out = []
    for i, ch in enumerate(word):
        py = char_py.get(ch)
        if not py:
            return None
        if n >= 5 and 3 <= i < n - 1:
            continue  # 五字及以上：前三字 + 末字
        if n <= 2:
            x = yin(py)
        elif n == 3:
            x = ((sheng(py) or "") + (yunk(py) or "")) if i == 2 else sheng(py)
        else:
            x = sheng(py)
        if not x:
            return None
        out.append(x)
    return "".join(out)


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    rime_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(root, "ime/src/main/assets/rime")
    out_file = sys.argv[2] if len(sys.argv) > 2 else os.path.join(root, "ime/src/main/assets/xiaohe/char_code.txt")

    words, char_codes = load_dicts(rime_dir)
    char_py = best_reading(load_readings(rime_dir), char_codes)

    # 自检：自动编码 vs 词表全码
    stat = defaultdict(lambda: [0, 0, 0])
    for w, codes in words.items():
        if len(w) == 1:
            continue
        c = code_of(w, char_py)
        L = len(w)
        if c is None:
            stat[L][2] += 1
        elif c in codes:
            stat[L][0] += 1
        else:
            stat[L][1] += 1
    print("自检（算出的全码是否出现在 openfly 词表里）:")
    for L in sorted(stat):
        h, m, n = stat[L]
        t = h + m
        print(f"  {L} 字词: {h}/{t} = {100 * h / max(t, 1):.0f}%   缺数据 {n}")

    os.makedirs(os.path.dirname(out_file), exist_ok=True)
    kept = 0
    with open(out_file, "w", encoding="utf-8") as fh:
        fh.write("# 字\t音码\t声母\t韵母键 —— 由 tools/gen_char_code.py 生成，勿手改\n")
        for ch in sorted(char_py):
            py = char_py[ch]
            y, s, k = yin(py), sheng(py), yunk(py)
            if not y:
                continue
            fh.write(f"{ch}\t{y}\t{s or ''}\t{k or ''}\n")
            kept += 1
    print(f"写出 {kept} 个字 → {out_file}")


if __name__ == "__main__":
    main()
