#!/usr/bin/env bash
# 下载 RIME 方案数据到 benchmark 的 assets，供基准 App 部署使用。
#
# 包含：
#   - luna_pinyin（拼音）+ essay（预置词频）+ stroke（反查）
#   - openfly（开源小鹤音形码表，MIT），配一个去 lua 的最小 schema
#
# 如需额外方案，把文件放到 benchmark/rime-extra/ 下，会被合并进来。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/benchmark/app/src/main/assets/rime"
EXTRA="$ROOT/benchmark/rime-extra"
WORK="$(mktemp -d)"
trap 'chmod -R u+w "$WORK" 2>/dev/null; rm -rf "$WORK" 2>/dev/null || true' EXIT

rm -rf "$DEST"
mkdir -p "$DEST"

clone() {
  echo "==> clone $1"
  git clone --depth 1 "https://github.com/$1.git" "$WORK/$2"
}

# ---- 拼音：prelude / luna-pinyin / essay / stroke ----
clone rime/rime-prelude prelude
clone rime/rime-luna-pinyin luna
clone rime/rime-essay essay
clone rime/rime-stroke stroke

find "$WORK/prelude" "$WORK/luna" "$WORK/stroke" -maxdepth 1 -type f \
  \( -name '*.yaml' -o -name '*.txt' \) -exec cp {} "$DEST/" \;

# essay.txt 是词频数据（非 yaml），use_preset_vocabulary 必需
[ -f "$WORK/essay/essay.txt" ] && cp "$WORK/essay/essay.txt" "$DEST/"

# ---- 小鹤音形：开源码表 openfly (MIT) ----
clone amorphobia/openfly openfly
for f in "$WORK/openfly"/openfly*.dict.yaml; do
  base="$(basename "$f")"
  # 反查表体积大且基准用不到，跳过
  [ "$base" = "openfly_reverse.dict.yaml" ] && continue
  cp "$f" "$DEST/"
done

# 去 lua 的最小化 openfly schema，仅保留音形码表核心，用于性能基准
cat > "$DEST/openfly.schema.yaml" <<'YAML'
# 最小化「开源小鹤」方案：去掉 lua 依赖，仅保留音形码表核心，用于性能基准。
# 码表来源 amorphobia/openfly (MIT)。
schema:
  schema_id: openfly
  name: 开源小鹤(基准)
  version: "v10.9z-bench"
  author:
    - 方案设计: 何海峰
    - 码表: amorphobia/openfly (MIT)
  description: 小鹤音形码表，去 lua，仅用于基准

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

# ---- 测 librime-lua 开销：同一个码表，仅多一个 lua filter ----
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
text = text.replace("name: 开源小鹤(基准)", "name: 开源小鹤(基准+lua)")
text = text.replace(
    "  translators:\n    - table_translator\n",
    "  translators:\n    - table_translator\n  filters:\n    - lua_filter@*bench_filter\n",
)
open(dst, "w", encoding="utf-8").write(text)
print("wrote openfly_lua.schema.yaml")
PY

# default.yaml 精简 schema_list；列出拼音、小鹤(无lua)、小鹤(+lua)
python3 - "$DEST/default.yaml" <<'PY'
import re
import sys

path = sys.argv[1]
text = open(path, encoding="utf-8").read()
patched = re.sub(
    r"schema_list:\n(?:[ \t]*-[ \t]*schema:.*\n)+",
    "schema_list:\n  - schema: luna_pinyin\n  - schema: openfly\n  - schema: openfly_lua\n",
    text,
)
open(path, "w", encoding="utf-8").write(patched)
print("schema_list ->", "luna_pinyin, openfly, openfly_lua" if patched != text else "unchanged")
PY

# 合并本地额外方案
if [ -d "$EXTRA" ]; then
  echo "==> merge extra rime data from $EXTRA"
  cp -r "$EXTRA/." "$DEST/"
fi

echo "==> rime assets: $(find "$DEST" -type f | wc -l) files, $(du -sh "$DEST" | cut -f1)"
