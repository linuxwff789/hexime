# SPDX-License-Identifier: GPL-3.0-or-later

find_package(Opencc REQUIRED)

add_library(hexime_bench SHARED rime_bench_jni.cpp)
target_link_libraries(hexime_bench rime-static ${Opencc_LIBRARY})
target_include_directories(hexime_bench PRIVATE
  "${CMAKE_BINARY_DIR}/librime/src"
  "${CMAKE_SOURCE_DIR}/librime/src"
  "${CMAKE_SOURCE_DIR}/librime/include"
  "${Opencc_INCLUDE_PATH}"
)
target_compile_options(hexime_bench PRIVATE "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=.")

# librime 通过 log 库输出，benchmark 用 logcat
target_link_libraries(hexime_bench log)
