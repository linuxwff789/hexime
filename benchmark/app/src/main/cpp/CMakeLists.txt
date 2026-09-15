# SPDX-License-Identifier: GPL-3.0-or-later
#
# 结构参考 osfans/trime 的 app/src/main/jni/CMakeLists.txt (GPL-3.0)，
# 去掉了 trime 自带的 librime_jni，换成 hexime 的 benchmark JNI。
#
# 依赖目录（librime / OpenCC / snappy / 插件 / cmake 脚本）由
# scripts/prepare-native.sh 在 CI 中注入到本目录。

cmake_minimum_required(VERSION 3.22.1)

project(hexime_bench VERSION 0.1.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 可复现构建
add_link_options("LINKER:--hash-style=both,--build-id=none")

set(CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake" ${CMAKE_MODULE_PATH})

option(BUILD_SHARED_LIBS "" OFF)
option(BUILD_TESTING "" OFF)

# 下载并编译 Boost（1.89.0），与 trime 一致
include(Boost)

option(WITH_GFLAGS "Use gflags" OFF)
option(WITH_GTEST "Use googletest" OFF)
option(WITH_UNWIND "Enable libunwind support" OFF)
add_subdirectory(librime/deps/glog)

option(YAML_CPP_BUILD_TOOLS "" OFF)
option(YAML_CPP_BUILD_TESTS "" OFF)
option(YAML_CPP_INSTALL "" OFF)
add_subdirectory(librime/deps/yaml-cpp)

option(SNAPPY_BUILD_TESTS "" OFF)
option(SNAPPY_BUILD_BENCHMARKS "" OFF)
option(SNAPPY_INSTALL "" OFF)
add_subdirectory(snappy)

set(HAVE_SNAPPY TRUE)
option(LEVELDB_BUILD_TESTS "" OFF)
option(LEVELDB_BUILD_BENCHMARKS "" OFF)
option(LEVELDB_INSTALL "" OFF)
add_subdirectory(librime/deps/leveldb)

add_subdirectory(librime/deps/marisa-trie)
set(LIBMARISA marisa)
option(USE_SYSTEM_MARISA "" ON)

add_subdirectory(OpenCC)
include(OpenccWorkarounds)

include_directories("${CMAKE_SOURCE_DIR}/librime/include")

# 编译 librime 本体（静态 rime-static）。
# 注意：Rime.cmake 会把 librime-lua / octagram / predict 以符号链接形式挂进
# librime/plugins/，librime 自己会 add_subdirectory 它们并（默认）合并进 rime-static。
include(Rime)

# ---- 检测 lua 插件是否已合并进 rime-static ----
set(HEXIME_LUA_OBJS "")
if(TARGET rime-lua-objs)
  set(HEXIME_LUA_OBJS $<TARGET_OBJECTS:rime-lua-objs>)
endif()
get_target_property(_hib_rime_src rime-static SOURCES)
set(HEXIME_LUA_MERGED FALSE)
if(_hib_rime_src MATCHES "rime-lua-objs")
  set(HEXIME_LUA_MERGED TRUE)
endif()
set(_hib_diag "rime-lua-objs target: ${HEXIME_LUA_OBJS}\n")
string(APPEND _hib_diag "lua merged into rime-static: ${HEXIME_LUA_MERGED}\n")
string(APPEND _hib_diag "rime-static sources:\n${_hib_rime_src}\n")
file(WRITE "${CMAKE_BINARY_DIR}/hexime_diag.txt" "${_hib_diag}")
message(STATUS "hexime: lua merged=${HEXIME_LUA_MERGED}")

# hexime 基准 JNI
add_subdirectory(bench)
