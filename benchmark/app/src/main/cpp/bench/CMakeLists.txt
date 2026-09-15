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

# 若 lua 插件未被合并进 rime-static，则直接将其对象编进本 .so；
# 若已合并，则只需强制引用 lua 模块即可（通过 HEXIME_FORCE_LINK_PLUGINS）。
if(HEXIME_LUA_OBJS)
  target_compile_definitions(hexime_bench PRIVATE HEXIME_FORCE_LINK_PLUGINS)
  target_include_directories(hexime_bench PRIVATE
    "${CMAKE_SOURCE_DIR}/librime-lua/src"
    "${CMAKE_SOURCE_DIR}/librime-lua/src/lib"
  )
  if(NOT HEXIME_LUA_MERGED)
    target_sources(hexime_bench PRIVATE ${HEXIME_LUA_OBJS})
  endif()
endif()
