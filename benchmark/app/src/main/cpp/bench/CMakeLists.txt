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

# 若 librime-lua 已集成，把其对象编进本 .so，并允许 JNI 强制引用 lua 模块
if(HEXIME_LUA_OBJS)
  target_sources(hexime_bench PRIVATE ${HEXIME_LUA_OBJS})
  target_compile_definitions(hexime_bench PRIVATE HEXIME_FORCE_LINK_PLUGINS)
  target_include_directories(hexime_bench PRIVATE
    "${CMAKE_SOURCE_DIR}/librime-lua/src"
    "${CMAKE_SOURCE_DIR}/librime-lua/src/lib"
  )
endif()
