#!/usr/bin/env bash
# 组装 librime 及其依赖到 benchmark 的原生构建目录。
#
# 复用 osfans/trime 已验证的 Android 构建布局（GPL-3.0），避免自己从零
# 交叉编译 Boost / OpenCC / leveldb / marisa 等一长串依赖。
#
# 用法: bash scripts/prepare-native.sh
# 可用环境变量 TRIME_REF 覆盖 trime 版本。

set -euo pipefail

TRIME_REF="${TRIME_REF:-v3.3.12}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/benchmark/app/src/main/cpp"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> cloning osfans/trime@$TRIME_REF with submodules"
git clone --depth 1 --branch "$TRIME_REF" \
  --recurse-submodules --shallow-submodules \
  https://github.com/osfans/trime.git "$WORK/trime"

SRC="$WORK/trime/app/src/main/jni"

# 这些目录是我们的 CMakeLists 需要的全部原生依赖
DIRS=(
  cmake
  librime
  OpenCC
  snappy
  librime-lua
  librime-lua-deps
  librime-octagram
  librime-predict
)

for d in "${DIRS[@]}"; do
  if [ ! -d "$SRC/$d" ]; then
    echo "ERROR: 缺少 $SRC/$d（trime 布局可能变了，请检查 TRIME_REF）" >&2
    exit 1
  fi
  echo "==> copy $d"
  rm -rf "$DEST/$d"
  mkdir -p "$DEST/$d"
  # 排除 .git，避免把 submodule 元数据带入
  (cd "$SRC/$d" && tar --exclude=.git -cf - .) | (cd "$DEST/$d" && tar -xf -)
done

echo "==> native sources ready:"
ls "$DEST"
