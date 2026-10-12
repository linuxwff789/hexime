#!/usr/bin/env bash
# 下载 RIME 方案数据到指定 assets 目录。
#
# 用法: bash scripts/stage-rime-data.sh [目标 assets/rime 目录] [--bench]
#   默认: benchmark/app/src/main/assets/rime --bench
#
# --bench 会额外生成「+lua filter」对比方案（仅基准用）。
# 默认（正式 App）包含：小鹤音形 openfly、雾凇拼音 rime_ice（含 melt_eng / radical_pinyin 依赖）。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST=""
BENCH=0
for arg in "$@"; do
  case "$arg" in
    --bench) BENCH=1 ;;
    *) DEST="$arg" ;;
  esac
done
if [ -z "$DEST" ]; then
  DEST="benchmark/app/src/main/assets/rime"
  BENCH=1
fi
case "$DEST" in /*) ;; *) DEST="$ROOT/$DEST" ;; esac
EXTRA="$ROOT/benchmark/rime-extra"

WORK="$(mktemp -d)"
trap 'chmod -R u+w "$WORK" 2>/dev/null; rm -rf "$WORK" 2>/dev/null || true' EXIT

rm -rf "$DEST"
mkdir -p "$DEST"

clone() {
  echo "==> clone $1"
  # 网络抽风时 git clone 会 TLS unexpected eof（手机网络尤其常见）；
  # set -e 下这一次失败会让整个 stage 中止、assets 变成半成品，所以自己重试。
  local i
  for i in 1 2 3 4; do
    rm -rf "$WORK/$2"
    if git clone --depth 1 "https://github.com/$1.git" "$WORK/$2" 2>&1; then
      return 0
    fi
    echo "   clone $1 失败（第 $i 次），重试…"
    sleep 3
  done
  echo "   !! clone $1 重试 4 次仍失败" >&2
  return 1
}

# 基础配置：prelude（default.yaml / symbols.yaml 等，所有方案 import_preset 都要用）
clone rime/rime-prelude prelude

find "$WORK/prelude" -maxdepth 1 -type f \
  \( -name '*.yaml' -o -name '*.txt' \) -exec cp {} "$DEST/" \;

# 拼音：雾凇拼音 rime-ice (GPL-3.0) —— 原来这里是朙月拼音 luna_pinyin
# （luna 整套连同 essay 词频、stroke 笔画反查一起撤掉，省下约 11MB）
clone iDvel/rime-ice rimeice

# 主方案 + 词库入口（rime_ice.dict.yaml 用 import_tables 挂 cn_dicts/*）
cp "$WORK/rimeice/rime_ice.schema.yaml" "$WORK/rimeice/rime_ice.dict.yaml" "$DEST/"
# 两个 dependency 方案：英文 melt_eng、部件拆字 radical_pinyin
cp "$WORK/rimeice/melt_eng.schema.yaml" "$WORK/rimeice/melt_eng.dict.yaml" "$DEST/"
cp "$WORK/rimeice/radical_pinyin.schema.yaml" "$WORK/rimeice/radical_pinyin.dict.yaml" "$DEST/"
# 符号/标点预设（schemas 里 import_preset 引用）
cp "$WORK/rimeice/symbols_v.yaml" "$WORK/rimeice/symbols_caps_v.yaml" "$DEST/"
# 词库目录 + lua 脚本（雾凇大量用 lua_translator/lua_filter）+ opencc（简繁、emoji）
cp -r "$WORK/rimeice/cn_dicts" "$WORK/rimeice/en_dicts" "$WORK/rimeice/lua" "$WORK/rimeice/opencc" "$DEST/"

# 小鹤音形：开源码表 openfly (MIT)
clone amorphobia/openfly openfly
# 反查 schema 本体（openfly_reverse.schema.yaml）——它是 openfly 的 dependency，
# 部署器靠它把 openfly_reverse.dict.yaml 编译成 openfly_reverse.table.bin
cp "$WORK/openfly/openfly_reverse.schema.yaml" "$DEST/" 2>/dev/null || true

for f in "$WORK/openfly"/openfly*.dict.yaml; do
  base="$(basename "$f")"
  # openfly_reverse.dict.yaml 就是「反查」用的码表（官方方案里 recognizer 用反引号触发），要带上
  cp "$f" "$DEST/"
done

# 去 lua 的最小化 openfly schema（正式 App 与基准共用）
cat > "$DEST/openfly.schema.yaml" <<'YAML'
# 最小化「开源小鹤」方案：去掉 lua 依赖，仅保留音形码表核心。
# 码表来源 amorphobia/openfly (MIT)。
schema:
  schema_id: openfly
  name: 开源小鹤
  version: "v10.9z-hexime"
  author:
    - 方案设计: 何海峰
    - 码表: amorphobia/openfly (MIT)
  description: 小鹤音形码表
  # ★ 关键：反查表 openfly_reverse 必须在这里声明成依赖。
  # librime 的部署器（deployment_tasks.cc）只编译 schema_list 里的方案 + 它们
  # schema/dependencies 里声明的依赖；reverse_lookup/dictionary 指到的表不会自动编译。
  # luna 的笔画反查能用，正是因为 luna_pinyin.schema.yaml 里写了 dependencies: [stroke]。
  dependencies:
    - openfly_reverse

# ★ 标点映射：不引这个的话引擎会一直报 `punctuator.cc: missing punctuation mapping`，
# 句号/逗号会落回「直接上屏 ASCII」，中文下打出英文点、全半角开关也没效果。
# punctuation.yaml（rime-prelude）里 half_shape/full_shape 两套映射，正好对应 中英/全半角 开关。
punctuator:
  import_preset: default

switches:
  - name: ascii_mode
    reset: 0
    states: [ 中文, 西文 ]
  - name: full_shape
    states: [ 半角, 全角 ]
  - name: ascii_punct
    states: [ 。，, ．， ]

engine:
  processors:
    - ascii_composer
    - recognizer
    - key_binder
    - speller
    - punctuator
    - selector
    - navigator
    - express_editor
  segmentors:
    - ascii_segmentor
    - matcher
    - abc_segmentor
    - fallback_segmentor
  translators:
    - table_translator
    # 加词：custom_phrase 走 stabledb，直读 user 目录下的 custom_phrase.txt
    # （小鹤方案原作者把 translator 的 enable_user_dict 关了，用户词库只能走这条 txt 路）
    - table_translator@custom_phrase
    # 反查：照 openfly 官方接法 —— 输入全码再打一个反引号，用 openfly_reverse 码表反查
    - reverse_lookup_translator

speller:
  alphabet: '/;zyxwvutsrqponmlkjihgfedcba'
  initials: 'abcdefghijklmnopqrstuvwxyz;'
  finals: '/'
  max_code_length: 4
  auto_select: true
  auto_select_pattern: ^;.$|^\w{4}$

translator:
  dictionary: openfly
  enable_charset_filter: false
  enable_sentence: false
  enable_completion: false
  enable_user_dict: false

# 反查（官方 openfly 同款）：recognizer 用反引号触发，字典是 openfly_reverse
reverse_lookup:
  dictionary: openfly_reverse
  tips: 〔反查〕

recognizer:
  import_preset: default
  patterns:
    uppercase: ""
    reverse_lookup: "[a-z`]*`+[a-z`]*"

# 用户加词：词条写在 <user>/custom_phrase.txt，一行「词<tab>码」
custom_phrase:
  dictionary: ""
  user_dict: custom_phrase
  db_class: stabledb
  enable_completion: false
  enable_sentence: false
  initial_quality: 99

# 次选：组合中按 . 直接上屏第 2 个候选（等价于按数字 2）
key_binder:
  bindings:
    - { when: composing, accept: period, send: "2" }
YAML

SCHEMAS="openfly,rime_ice"

if [ "$BENCH" = "1" ]; then
  # 测 librime-lua 开销：同一个码表，仅多一个 lua filter
  mkdir -p "$DEST/lua"
  cat > "$DEST/lua/bench_filter.lua" <<'LUA'
-- 用于测量 librime-lua 的固定调用开销：遍历候选并原样输出
return function(input)
  for cand in input:iter() do
    yield(cand)
  end
end
LUA
  python3 - "$DEST/openfly.schema.yaml" "$DEST/openfly_lua.schema.yaml" <<'PY'
import sys

src, dst = sys.argv[1], sys.argv[2]
text = open(src, encoding="utf-8").read()
text = text.replace("schema_id: openfly", "schema_id: openfly_lua")
text = text.replace("name: 开源小鹤", "name: 开源小鹤(基准+lua)")
text = text.replace(
    "  translators:\n    - table_translator\n",
    "  translators:\n    - table_translator\n  filters:\n    - lua_filter@*bench_filter\n",
)
open(dst, "w", encoding="utf-8").write(text)
print("wrote openfly_lua.schema.yaml")
PY
  SCHEMAS="rime_ice,openfly,openfly_lua"
fi

# default.yaml 精简 schema_list
python3 - "$DEST/default.yaml" "$SCHEMAS" <<'PY'
import re
import sys

path, schemas = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8").read()
replacement = "schema_list:\n" + "".join(
    f"  - schema: {name}\n" for name in schemas.split(",") if name
)
patched = re.sub(r"schema_list:\n(?:[ \t]*-[ \t]*schema:.*\n)+", replacement, text)
open(path, "w", encoding="utf-8").write(patched)
print("schema_list ->", schemas)
PY

if [ -d "$EXTRA" ]; then
  echo "==> merge extra rime data from $EXTRA"
  cp -r "$EXTRA/." "$DEST/"
fi

echo "==> rime assets: $(find "$DEST" -type f | wc -l) files, $(du -sh "$DEST" | cut -f1)"
