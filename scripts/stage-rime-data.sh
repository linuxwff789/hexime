#!/usr/bin/env bash
# 下载 RIME 方案数据到指定 assets 目录。
#
# 用法: bash scripts/stage-rime-data.sh [目标 assets/rime 目录] [--bench]
#   默认: benchmark/app/src/main/assets/rime --bench
#
# --bench 会额外生成「+lua filter」对比方案（仅基准用）。
# 默认（正式 App）包含：小鹤音形 openfly、拼音 luna_pinyin、词频 essay、反查 stroke。

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
  git clone --depth 1 "https://github.com/$1.git" "$WORK/$2"
}

# 拼音：prelude / luna-pinyin / essay / stroke
clone rime/rime-prelude prelude
clone rime/rime-luna-pinyin luna
clone rime/rime-essay essay
clone rime/rime-stroke stroke

find "$WORK/prelude" "$WORK/luna" "$WORK/stroke" -maxdepth 1 -type f \
  \( -name '*.yaml' -o -name '*.txt' \) -exec cp {} "$DEST/" \;

# essay.txt 是词频数据（非 yaml），use_preset_vocabulary 必需
[ -f "$WORK/essay/essay.txt" ] && cp "$WORK/essay/essay.txt" "$DEST/"

# 小鹤音形：开源码表 openfly (MIT)
clone amorphobia/openfly openfly
for f in "$WORK/openfly"/openfly*.dict.yaml; do
  base="$(basename "$f")"
  [ "$base" = "openfly_reverse.dict.yaml" ] && continue
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
YAML

SCHEMAS="openfly,luna_pinyin"

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
  SCHEMAS="luna_pinyin,openfly,openfly_lua"
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
