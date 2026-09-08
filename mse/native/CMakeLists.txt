cmake_minimum_required(VERSION 3.20)
project(mseffmpegjni C)

set(CMAKE_C_STANDARD 11)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

# Matches engine-js MSE_DEBUG. Default off (shipped). Playground passes -DMSE_DEBUG=ON.
option(MSE_DEBUG "Verbose MSE protocol logs (append/seek/decoder)" OFF)
if(MSE_DEBUG)
  add_compile_definitions(MSE_DEBUG=1)
endif()

if(ANDROID)
  add_library(mseffmpegjni SHARED mse_ffmpeg.c mse_host.c mse_vt.c mse_host_jni.c)
  target_include_directories(mseffmpegjni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../quickjs/native)
  if(NOT DEFINED FFMPEG_ROOT)
    message(FATAL_ERROR "FFMPEG_ROOT must be ffmpeg-build out/android-\${ABI} (include/ + lib/)")
  endif()
  target_include_directories(mseffmpegjni PRIVATE ${FFMPEG_ROOT}/include)
  target_link_directories(mseffmpegjni PRIVATE ${FFMPEG_ROOT}/lib)
  find_library(log-lib log)
  # Static libav from ffmpeg-build; lame/shine are copied next to libav*.a.
  # start-group: libav archives have circular refs (avcodec↔avutil, lame after avcodec).
  target_link_libraries(mseffmpegjni PRIVATE
    android ${log-lib} mediandk OpenSLES
    -Wl,--start-group
    avformat avcodec swscale swresample avutil
    mp3lame shine
    -Wl,--end-group
    m z atomic)
  # -Bsymbolic: ffmpeg aarch64 asm uses ADRP against globals; lld only accepts that
  # in a DSO when those symbols cannot bind externally (ffmpeg SHFLAGS does the same).
  target_link_options(mseffmpegjni PRIVATE
    "-Wl,-Bsymbolic"
    "-Wl,-z,max-page-size=16384")
  target_compile_options(mseffmpegjni PRIVATE -O2 -Wall -Wextra)
  return()
endif()

find_package(PkgConfig REQUIRED)
pkg_check_modules(AV REQUIRED libavformat libavcodec libavutil libswscale libswresample)

if(APPLE)
    enable_language(OBJC)
endif()

add_library(mseffmpegjni SHARED mse_ffmpeg.c mse_host.c mse_vt.c mse_host_jni.c)
target_include_directories(mseffmpegjni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../quickjs/native)
target_include_directories(mseffmpegjni PRIVATE ${AV_INCLUDE_DIRS})
target_link_directories(mseffmpegjni PRIVATE ${AV_LIBRARY_DIRS})
target_link_libraries(mseffmpegjni PRIVATE ${AV_LIBRARIES})
if(APPLE)
  target_sources(mseffmpegjni PRIVATE mse_vt_present.m mse_vt_layer.m)
  set_source_files_properties(mse_vt_present.m PROPERTIES COMPILE_FLAGS "-fobjc-arc")
  find_library(JAWT_LIB jawt HINTS "$ENV{JAVA_HOME}/lib" "$ENV{JAVA_HOME}/lib/jli")
  target_link_options(mseffmpegjni PRIVATE "-Wl,-rpath,${AV_LIBRARY_DIRS}")
  target_link_libraries(mseffmpegjni PRIVATE
    "-framework AudioToolbox"
    "-framework CoreAudio"
    "-framework VideoToolbox"
    "-framework CoreMedia"
    "-framework CoreFoundation"
    "-framework CoreVideo"
    "-framework AVFoundation"
    "-framework QuartzCore"
  )
  if(JAWT_LIB)
    target_link_libraries(mseffmpegjni PRIVATE ${JAWT_LIB})
  endif()
endif()

if(NOT DEFINED ENV{JAVA_HOME})
  message(FATAL_ERROR "JAVA_HOME must be set to a JDK (with headers) to build the host JNI library")
endif()
target_include_directories(mseffmpegjni PRIVATE
  $ENV{JAVA_HOME}/include
  $ENV{JAVA_HOME}/include/darwin
  $ENV{JAVA_HOME}/include/linux
  $ENV{JAVA_HOME}/include/win32
)
target_compile_options(mseffmpegjni PRIVATE -O2 -Wall -Wextra)
