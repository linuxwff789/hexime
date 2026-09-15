# SPDX-License-Identifier: GPL-3.0-or-later

find_package(Opencc REQUIRED)

add_library(hexime_rime SHARED rime_jni.cpp)
target_link_libraries(hexime_rime rime-static ${Opencc_LIBRARY} log)
target_include_directories(hexime_rime PRIVATE
  "${CMAKE_BINARY_DIR}/librime/src"
  "${CMAKE_SOURCE_DIR}/librime/src"
  "${CMAKE_SOURCE_DIR}/librime/include"
  "${Opencc_INCLUDE_PATH}"
)
target_compile_options(hexime_rime PRIVATE "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=.")

if(HEXIME_LUA_OBJS)
  target_compile_definitions(hexime_rime PRIVATE HEXIME_FORCE_LINK_PLUGINS)
  target_include_directories(hexime_rime PRIVATE
    "${CMAKE_SOURCE_DIR}/librime-lua/src"
    "${CMAKE_SOURCE_DIR}/librime-lua/src/lib"
  )
  if(NOT HEXIME_LUA_MERGED)
    target_sources(hexime_rime PRIVATE ${HEXIME_LUA_OBJS})
  endif()
endif()
