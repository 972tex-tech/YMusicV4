cmake_minimum_required(VERSION 3.20)
project(quickjsjni C)

set(CMAKE_C_STANDARD 11)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

set(QJS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/quickjs)

# Bellard QuickJS (VERSION 2026-06-04, pinned commit 04be2460) + our JNI shim.
add_library(quickjsjni SHARED
  quickjs_jni.c
  quickjs_json.c
  ${QJS_DIR}/quickjs.c
  ${QJS_DIR}/cutils.c
  ${QJS_DIR}/libregexp.c
  ${QJS_DIR}/libunicode.c
  ${QJS_DIR}/dtoa.c
)

target_include_directories(quickjsjni PRIVATE ${QJS_DIR})

if(ANDROID)
  # The NDK sysroot provides jni.h automatically.
else()
  # Host JVM: JNI headers straight from JAVA_HOME. We avoid find_package(JNI) (it insists on the
  # AWT/jawt component we don't need) — a JNI shared library resolves JVM symbols at load time.
  if(NOT DEFINED ENV{JAVA_HOME})
    message(FATAL_ERROR "JAVA_HOME must be set to a JDK (with headers) to build the host JNI library")
  endif()
  target_include_directories(quickjsjni PRIVATE
    $ENV{JAVA_HOME}/include
    $ENV{JAVA_HOME}/include/darwin
    $ENV{JAVA_HOME}/include/linux
    $ENV{JAVA_HOME}/include/win32
  )
endif()
target_compile_definitions(quickjsjni PRIVATE _GNU_SOURCE CONFIG_VERSION="2026-06-04")
# QuickJS emits many warnings by design; silence them and optimize.
target_compile_options(quickjsjni PRIVATE -w -O2 -funsigned-char)
