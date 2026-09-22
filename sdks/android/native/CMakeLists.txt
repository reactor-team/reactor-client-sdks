cmake_minimum_required(VERSION 3.22)
project(reactor_jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Where scripts/build-android-ffi.sh staged the per-ABI libraries. The Gradle build passes the
# root and CMake appends ANDROID_ABI, because CMake is configured once per ABI and only it knows
# which one this pass is for.
if(NOT DEFINED REACTOR_FFI_STAGE)
  message(FATAL_ERROR "REACTOR_FFI_STAGE is not set — run `mise run build:android:native` first")
endif()

set(REACTOR_FFI_LIB "${REACTOR_FFI_STAGE}/${ANDROID_ABI}/libreactor_ffi.so")
if(NOT EXISTS "${REACTOR_FFI_LIB}")
  message(FATAL_ERROR "No libreactor_ffi.so at ${REACTOR_FFI_LIB} — run `mise run build:android:native`")
endif()

add_library(reactor_jni SHARED src/abi.cpp src/client.cpp)

target_include_directories(reactor_jni PRIVATE
  include
  # The canonical ABI header. Included directly rather than copied, which is what makes a
  # signature change a compile error here instead of a stack smash at run time.
  "${CMAKE_CURRENT_SOURCE_DIR}/../../../crates/reactor-ffi/include")

target_link_libraries(reactor_jni PRIVATE "${REACTOR_FFI_LIB}" log)

target_compile_options(reactor_jni PRIVATE -Wall -Wextra -Werror)

# 16 KB pages are required for Android 15 (API 35) targets: a 4 KB-aligned library does not load
# at all on such a device, which is why the instrumented tests run against a 16 KB system image.
#
# Stated explicitly even though the pinned NDK appears to align this way already — removing this
# line and rebuilding still produced an aligned library, so it is insurance against a toolchain
# whose default changes rather than the thing currently doing the work.
# scripts/check-android-native.py is what actually enforces it, on every packaged library.
target_link_options(reactor_jni PRIVATE "-Wl,-z,max-page-size=16384")
