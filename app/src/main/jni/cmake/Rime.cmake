# SPDX-FileCopyrightText: 2015 - 2024 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

# if you want to add some new plugins, add them to librime_jni/rime_jni.cc too
set(RIME_PLUGINS librime-lua librime-octagram librime-predict)

# symlink plugins
foreach(plugin ${RIME_PLUGINS})
  if(NOT EXISTS "${CMAKE_SOURCE_DIR}/librime/plugins/${plugin}")
    file(CREATE_LINK "${CMAKE_SOURCE_DIR}/${plugin}"
         "${CMAKE_SOURCE_DIR}/librime/plugins/${plugin}" COPY_ON_ERROR SYMBOLIC)
  endif()
endforeach()

# librime-lua
if(NOT EXISTS "${CMAKE_SOURCE_DIR}/librime/plugins/librime-lua/thirdparty")
  file(CREATE_LINK "${CMAKE_SOURCE_DIR}/librime-lua-deps"
       "${CMAKE_SOURCE_DIR}/librime/plugins/librime-lua/thirdparty"
       COPY_ON_ERROR SYMBOLIC)
endif()

option(BUILD_TEST "" OFF)
option(BUILD_STATIC "" ON)
add_subdirectory(librime)
target_compile_options(
  rime-static PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=." "-Wno-error=deprecated-declarations")

target_compile_options(
  rime-lua-objs PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

# Vendored Lua's lprefix.h forces _FILE_OFFSET_BITS=64, which on 32-bit
# Android ABIs (armeabi-v7a/x86) gates fseeko/ftello behind API level 24 in
# NDK's bionic headers. Our minSdk is 21, so those declarations disappear
# and the build fails with implicit-function-declaration errors. Pinning
# the macro to 32 before lprefix.h sees it keeps fseeko/ftello declared
# unconditionally.
target_compile_definitions(rime-lua-objs PRIVATE _FILE_OFFSET_BITS=32)

target_compile_options(
  rime-octagram-objs PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")
