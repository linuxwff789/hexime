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

# 关闭 librime 自带的插件自动发现（其 ENV 机制在 CI 下未生效），
# 改为在下面显式集成 librime-lua，行为确定。
set(ENV{RIME_PLUGINS} "")

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

# 编译 librime 本体（静态 rime-static）
include(Rime)

# ---- 显式集成 librime-lua ----
# 直接把 lua 插件对象编进 JNI .so，不依赖 librime 的插件合并。
if(EXISTS "${CMAKE_SOURCE_DIR}/librime-lua")
  if(NOT EXISTS "${CMAKE_SOURCE_DIR}/librime-lua/thirdparty")
    file(CREATE_LINK "${CMAKE_SOURCE_DIR}/librime-lua-deps"
         "${CMAKE_SOURCE_DIR}/librime-lua/thirdparty" COPY_ON_ERROR SYMBOLIC)
  endif()
  add_subdirectory("${CMAKE_SOURCE_DIR}/librime-lua" "${CMAKE_BINARY_DIR}/librime-lua")
  target_include_directories(rime-lua-objs PRIVATE
    "${CMAKE_SOURCE_DIR}/librime/src"
    "${CMAKE_BINARY_DIR}/librime/src"
    "${CMAKE_SOURCE_DIR}/librime/include"
    "${CMAKE_SOURCE_DIR}/OpenCC/src"
    "${CMAKE_BINARY_DIR}/OpenCC/src")
  set(HEXIME_LUA_OBJS $<TARGET_OBJECTS:rime-lua-objs>)
  message(STATUS "hexime: librime-lua enabled")
else()
  set(HEXIME_LUA_OBJS "")
  message(STATUS "hexime: librime-lua NOT found, lua disabled")
endif()

# hexime 基准 JNI
add_subdirectory(bench)
