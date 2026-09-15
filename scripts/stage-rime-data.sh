#!/usr/bin/env bash
# 下载 RIME 方案数据到 benchmark 的 assets，供基准 App 部署使用。
#
# 默认只装 luna_pinyin（拼音）。如需小鹤音形，把码表放到
# benchmark/rime-extra/ 下（本仓库不内置受版权保护的码表），会被合并进来。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/benchmark/app/src/main/assets/rime"
EXTRA="$ROOT/benchmark/rime-extra"
WORK="$(mktemp -d)"
trap 'chmod -R u+w "$WORK" 2>/dev/null; rm -rf "$WORK" 2>/dev/null || true' EXIT

rm -rf "$DEST"
mkdir -p "$DEST"

clone() {
  local repo="$1" dir="$2"
  echo "==> clone $repo"
  git clone --depth 1 "https://github.com/rime/$repo.git" "$WORK/$dir"
}

clone rime-prelude prelude
clone rime-luna-pinyin luna

# 只取顶层的 yaml / txt（词库、方案、符号表等）
find "$WORK/prelude" "$WORK/luna" -maxdepth 1 -type f \
  \( -name '*.yaml' -o -name '*.txt' \) -exec cp {} "$DEST/" \;

# 合并本地提供的额外方案（如小鹤音形）
if [ -d "$EXTRA" ]; then
  echo "==> merge extra rime data from $EXTRA"
  cp -r "$EXTRA/." "$DEST/"
fi

echo "==> rime assets: $(find "$DEST" -type f | wc -l) files in $DEST"
