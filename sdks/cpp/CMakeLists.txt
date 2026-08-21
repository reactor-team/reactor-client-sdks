# The Reactor C++ SDK: an object model over libreactor_ffi.
#
# Consumers see one target, `reactor::sdk`. It carries the include directories,
# the C++17 requirement and the native library, so linking it is the whole
# integration:
#
#   find_package(reactor-sdk REQUIRED)
#   target_link_libraries(app PRIVATE reactor::sdk)
#
# Building from this repo needs a release build of the FFI first:
#
#   cargo build -p reactor-ffi --release
#   cmake -B build -G Ninja sdks/cpp && cmake --build build
#
# Point it at a library somewhere else with -DREACTOR_FFI_LIB_DIR=…, which is
# what makes "run against a local build" possible for a consumer who installed
# the SDK from a release archive.

cmake_minimum_required(VERSION 3.24)

project(
  reactor-sdk
  VERSION 0.1.0
  DESCRIPTION "Reactor client SDK for C++"
  HOMEPAGE_URL "https://github.com/reactor-team/reactor-client-sdks"
  LANGUAGES CXX
)

# C++17 for everything in this build tree, dependencies included, not just for
# our own targets. Catch2 only compiles its std::string_view stringifier when it
# is itself built at C++17, so a CHECK comparing two string_views fails to link
# against a C++14 Catch2 — with an undefined-symbol error that names a template
# and not the standard.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# ── Options ───────────────────────────────────────────────────────────────────

# Tests and examples are for whoever is working on the SDK, not for whoever is
# consuming it — a project that pulls this in with FetchContent should not build
# either. PROJECT_IS_TOP_LEVEL is exactly that distinction.
option(REACTOR_SDK_BUILD_TESTS "Build the C++ SDK unit tests" ${PROJECT_IS_TOP_LEVEL})
option(REACTOR_SDK_BUILD_EXAMPLES "Build the C++ SDK examples" ${PROJECT_IS_TOP_LEVEL})

set(REACTOR_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." CACHE PATH
    "Root of the reactor-client-sdks checkout, where crates/ and target/ live")

set(REACTOR_FFI_LIB_DIR "${REACTOR_REPO_ROOT}/target/release" CACHE PATH
    "Directory holding libreactor_ffi (a cargo --release build by default)")

set(REACTOR_FFI_INCLUDE_DIR "${REACTOR_REPO_ROOT}/crates/reactor-ffi/include" CACHE PATH
    "Directory holding reactor_ffi.h")

# ── The native library ────────────────────────────────────────────────────────

# Found rather than assumed, so a missing build says so here instead of at link
# time in whatever consumer noticed first. Rebuild it after pulling changes under
# crates/: a signature that moved in the FFI but not in your build resolves at
# the call and corrupts the stack there, which looks like a hang rather than a
# version error. reactor_abi_version() turns that into a message, but only if the
# library on disk is the one you think it is.
find_library(
  REACTOR_FFI_LIBRARY
  NAMES reactor_ffi
  HINTS "${REACTOR_FFI_LIB_DIR}"
  NO_DEFAULT_PATH
  DOC "The libreactor_ffi shared library"
)

if(NOT REACTOR_FFI_LIBRARY)
  # Not REQUIRED, because REQUIRED's message is about a variable and this one is
  # about what to run. Building it is a separate task on purpose: it compiles
  # libwebrtc glue that needs its own pinned compiler, which is the wrong one for
  # this project.
  message(FATAL_ERROR
    "libreactor_ffi not found under ${REACTOR_FFI_LIB_DIR}.\n"
    "Build it with `mise run build:ffi` (cargo build -p reactor-ffi --release), "
    "or point this at an existing copy with -DREACTOR_FFI_LIB_DIR=…")
endif()

if(NOT EXISTS "${REACTOR_FFI_INCLUDE_DIR}/reactor_ffi.h")
  message(FATAL_ERROR
    "reactor_ffi.h not found under ${REACTOR_FFI_INCLUDE_DIR}. "
    "The SDK compiles against the canonical header rather than a copy of it — "
    "set -DREACTOR_FFI_INCLUDE_DIR to where it lives.")
endif()

# ── Dependencies ──────────────────────────────────────────────────────────────

include(FetchContent)

# JSON is the FFI's own currency: capabilities, errors, command arguments and
# replies all cross the boundary as JSON text. nlohmann/json is the de facto
# standard for reading it in C++, and it is exposed as `reactor::Json` so there
# is one seam to change if that ever stops being true.
#
# A system or vendored copy wins when there is one — a consumer who already
# pins nlohmann should not end up with two of it in one binary.
find_package(nlohmann_json 3.11 QUIET)
if(NOT nlohmann_json_FOUND)
  # JSON_Install ON so the fetched copy gets its own export set and install
  # rules. Without it, exporting `reactor::sdk` fails: our target requires a
  # target that is in no export set, and a consumer of the installed package
  # would have nothing to satisfy `find_dependency(nlohmann_json)` with. The
  # archive therefore carries nlohmann too — self-contained, and still yielding
  # to a consumer's own copy when they have one earlier on CMAKE_PREFIX_PATH.
  set(JSON_Install ON CACHE BOOL "" FORCE)
  set(JSON_BuildTests OFF CACHE BOOL "" FORCE)
  FetchContent_Declare(
    nlohmann_json
    URL https://github.com/nlohmann/json/releases/download/v3.12.0/json.tar.xz
    URL_HASH SHA256=42f6e95cad6ec532fd372391373363b62a14af6d771056dbfc86160e6dfff7aa
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
  )
  FetchContent_MakeAvailable(nlohmann_json)
endif()

# ── The library ───────────────────────────────────────────────────────────────

configure_file(
  "${CMAKE_CURRENT_SOURCE_DIR}/include/reactor/version.hpp.in"
  "${CMAKE_CURRENT_BINARY_DIR}/generated/reactor/version.hpp"
  @ONLY
)

add_library(
  reactor_sdk
  src/detail/dispatcher.cpp
  src/detail/ffi.cpp
  src/detail/log.cpp
  src/errors.cpp
  src/reactor.cpp
  src/recording.cpp
  src/track.cpp
  src/version.cpp
)
add_library(reactor::sdk ALIAS reactor_sdk)

target_compile_features(reactor_sdk PUBLIC cxx_std_17)

target_include_directories(
  reactor_sdk
  PUBLIC
    "$<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>"
    "$<BUILD_INTERFACE:${CMAKE_CURRENT_BINARY_DIR}/generated>"
    "$<INSTALL_INTERFACE:include>"
  PRIVATE
    # The FFI header is an implementation detail: a consumer includes
    # <reactor/…>, never <reactor_ffi.h>, so the C ABI does not leak into
    # anyone's translation units.
    "${REACTOR_FFI_INCLUDE_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
)

target_link_libraries(reactor_sdk PUBLIC nlohmann_json::nlohmann_json)
target_link_libraries(reactor_sdk PRIVATE "${REACTOR_FFI_LIBRARY}")

# Warnings are errors here for the same reason RUSTFLAGS says so for the crates:
# this is a library, and a warning in it becomes a warning in every consumer's
# build that they cannot fix.
target_compile_options(
  reactor_sdk
  PRIVATE
    $<$<COMPILE_LANG_AND_ID:CXX,GNU,Clang,AppleClang>:-Wall;-Wextra;-Wpedantic;-Werror>
    $<$<COMPILE_LANG_AND_ID:CXX,MSVC>:/W4;/WX>
)

set_target_properties(
  reactor_sdk PROPERTIES
  OUTPUT_NAME reactor_sdk
  # So the installed package spells the target the same way this build tree
  # does: the export namespace is `reactor::`, and without this the name a
  # consumer has to write is `reactor::reactor_sdk` while every code sample in
  # the repo says `reactor::sdk`.
  EXPORT_NAME sdk
  POSITION_INDEPENDENT_CODE ON
  # So an executable finds the bundled libreactor_ffi sitting beside it without
  # the consumer setting anything at run time.
  BUILD_RPATH "${REACTOR_FFI_LIB_DIR}"
  INSTALL_RPATH "$<IF:$<PLATFORM_ID:Darwin>,@loader_path,$ORIGIN>"
)

# ── Tests ─────────────────────────────────────────────────────────────────────

if(REACTOR_SDK_BUILD_TESTS)
  enable_testing()
  add_subdirectory(tests)
endif()

if(REACTOR_SDK_BUILD_EXAMPLES)
  add_subdirectory(examples)
endif()

# ── Install ───────────────────────────────────────────────────────────────────
#
# What a consumer gets: the headers, the static library, the native library
# beside it, and a config package so `find_package(reactor-sdk)` works. The
# release archives (one per platform) are this install tree, tarred.

include(GNUInstallDirs)
include(CMakePackageConfigHelpers)

install(
  TARGETS reactor_sdk
  EXPORT reactor-sdk-targets
  ARCHIVE DESTINATION "${CMAKE_INSTALL_LIBDIR}"
  LIBRARY DESTINATION "${CMAKE_INSTALL_LIBDIR}"
  RUNTIME DESTINATION "${CMAKE_INSTALL_BINDIR}"
)

install(
  DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/include/reactor"
  DESTINATION "${CMAKE_INSTALL_INCLUDEDIR}"
  FILES_MATCHING PATTERN "*.hpp"
)

# The generated one lives in the build tree, so it is installed separately.
install(
  FILES "${CMAKE_CURRENT_BINARY_DIR}/generated/reactor/version.hpp"
  DESTINATION "${CMAKE_INSTALL_INCLUDEDIR}/reactor"
)

# The native library travels with the package. Without it the package is inert:
# building libreactor_ffi needs a Rust toolchain and a libwebrtc download, which
# is exactly what a consumer of a C++ SDK should not have to own.
install(FILES "${REACTOR_FFI_LIBRARY}" DESTINATION "${CMAKE_INSTALL_LIBDIR}")

install(
  EXPORT reactor-sdk-targets
  FILE reactor-sdk-targets.cmake
  NAMESPACE reactor::
  DESTINATION "${CMAKE_INSTALL_LIBDIR}/cmake/reactor-sdk"
)

configure_package_config_file(
  "${CMAKE_CURRENT_SOURCE_DIR}/cmake/reactor-sdk-config.cmake.in"
  "${CMAKE_CURRENT_BINARY_DIR}/reactor-sdk-config.cmake"
  INSTALL_DESTINATION "${CMAKE_INSTALL_LIBDIR}/cmake/reactor-sdk"
)

# SameMinorVersion: the API is additive within a minor, so a consumer asking for
# 1.2 is satisfied by 1.2.7 and not by 1.3.0.
write_basic_package_version_file(
  "${CMAKE_CURRENT_BINARY_DIR}/reactor-sdk-config-version.cmake"
  COMPATIBILITY SameMinorVersion
)

install(
  FILES
    "${CMAKE_CURRENT_BINARY_DIR}/reactor-sdk-config.cmake"
    "${CMAKE_CURRENT_BINARY_DIR}/reactor-sdk-config-version.cmake"
  DESTINATION "${CMAKE_INSTALL_LIBDIR}/cmake/reactor-sdk"
)
