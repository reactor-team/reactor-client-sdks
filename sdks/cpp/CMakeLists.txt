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
  VERSION 0.1.1
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

# The device helpers, in a target of their own. On by default so they are built
# and tested, and separate so that linking `reactor::sdk` brings in no audio
# library at all — which is what keeps the mandatory path unable to open a
# microphone.
option(REACTOR_SDK_BUILD_AUDIO "Build the optional reactor::sdk_audio target" ON)

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

# Named rather than linked by path, and this is not cosmetic. `reactor_sdk` is a
# static archive, so it links nothing itself: the FFI stays in its *interface*
# and is written into the exported reactor-sdk-targets.cmake. As a bare path that
# export reads `/…/target/release/libreactor_ffi.…` — this machine's cargo output,
# which does not exist for whoever unpacks the archive, while the copy the package
# ships beside it goes unused. A target name defers the location to whoever
# defines it: here, the cargo build; in the installed config, the package's own
# lib/.
#
# UNKNOWN rather than SHARED because the file is a .dylib, a .so or an import
# library depending on the platform, and UNKNOWN is the one kind that takes all
# three by path.
add_library(reactor::ffi UNKNOWN IMPORTED)
set_target_properties(reactor::ffi PROPERTIES IMPORTED_LOCATION "${REACTOR_FFI_LIBRARY}")

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
target_link_libraries(reactor_sdk PRIVATE reactor::ffi)

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

# ── Audio devices (optional) ──────────────────────────────────────────────────
#
# Deliberately not part of reactor::sdk. The core is pinned to the synthetic audio
# module and cannot be talked out of it; real devices are an opt-in extra, and a
# consumer who never asks for them never links an audio library.

# Always defined, because the installed config tests it: an empty substitution
# would leave `if()` with no arguments there, which is a configure error in the
# consumer's build rather than in ours.
set(REACTOR_SDK_AUDIO_NEEDS_THREADS OFF)

if(REACTOR_SDK_BUILD_AUDIO)
  add_library(reactor_sdk_audio src/audio/devices.cpp)
  add_library(reactor::sdk_audio ALIAS reactor_sdk_audio)

  target_link_libraries(reactor_sdk_audio PUBLIC reactor::sdk)
  target_include_directories(
    reactor_sdk_audio
    PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src"
  )

  # Single-header, public domain, pinned by tag. Fetched rather than vendored so
  # the version is visible in one line and a bump is a one-line diff.
  FetchContent_Declare(
    miniaudio
    GIT_REPOSITORY https://github.com/mackron/miniaudio.git
    GIT_TAG 0.11.25
    GIT_SHALLOW TRUE
  )
  FetchContent_MakeAvailable(miniaudio)

  if(miniaudio_SOURCE_DIR)
    target_include_directories(reactor_sdk_audio PRIVATE "${miniaudio_SOURCE_DIR}")
    target_compile_definitions(reactor_sdk_audio PRIVATE REACTOR_SDK_HAVE_MINIAUDIO)
    if(APPLE)
      target_link_libraries(
        reactor_sdk_audio
        PRIVATE "-framework CoreAudio" "-framework AudioToolbox" "-framework CoreFoundation"
      )
    elseif(UNIX)
      find_package(Threads REQUIRED)
      target_link_libraries(reactor_sdk_audio PRIVATE Threads::Threads ${CMAKE_DL_LIBS} m)
      # PRIVATE buys nothing here: reactor_sdk_audio is a static archive, so this
      # lands in its exported link interface as $<LINK_ONLY:Threads::Threads> and a
      # consumer of the installed package has to be able to define that target.
      # Recorded for the config to find again — without it, `find_package` succeeds
      # and generation then fails on an imported target nobody created.
      set(REACTOR_SDK_AUDIO_NEEDS_THREADS ON)
    endif()
  else()
    message(WARNING
      "miniaudio was not available; reactor::sdk_audio will build without a backend and "
      "its Speaker/Microphone will refuse to start rather than play silence.")
  endif()

  # Warnings are errors for our own code. miniaudio's own translation unit is
  # third-party C that we do not get to fix, so the file that includes it opts out
  # of the strictest of them rather than the project opting out globally.
  target_compile_options(
    reactor_sdk_audio
    PRIVATE
      $<$<COMPILE_LANG_AND_ID:CXX,GNU,Clang,AppleClang>:-Wall;-Wextra;-Werror;-Wno-unused-function>
      $<$<COMPILE_LANG_AND_ID:CXX,MSVC>:/W4;/WX>
  )

  set_target_properties(
    reactor_sdk_audio PROPERTIES
    OUTPUT_NAME reactor_sdk_audio
    EXPORT_NAME sdk_audio
    POSITION_INDEPENDENT_CODE ON
  )
endif()

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

set(REACTOR_INSTALL_TARGETS reactor_sdk)
if(REACTOR_SDK_BUILD_AUDIO)
  list(APPEND REACTOR_INSTALL_TARGETS reactor_sdk_audio)
endif()

install(
  TARGETS ${REACTOR_INSTALL_TARGETS}
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

# Under its own name, because that is what the installed config looks for and the
# suffix differs per platform.
get_filename_component(REACTOR_FFI_LIB_NAME "${REACTOR_FFI_LIBRARY}" NAME)

# A Mach-O library carries its own load path, and cargo writes the absolute one
# from the machine that built it. Linking the package's copy is then only half an
# answer: the link succeeds and dyld still goes looking for
# `/…/target/release/deps/…` at launch, which is nowhere on the machine that
# unpacked the archive. Rewritten to @rpath on the way in, so it is found beside
# whatever linked it. ELF needs none of this — rustc writes a plain SONAME.
if(APPLE)
  install(CODE "
    set(_lib \"\$ENV{DESTDIR}\${CMAKE_INSTALL_PREFIX}/${CMAKE_INSTALL_LIBDIR}/${REACTOR_FFI_LIB_NAME}\")
    execute_process(
      COMMAND install_name_tool -id \"@rpath/${REACTOR_FFI_LIB_NAME}\" \"\${_lib}\"
      RESULT_VARIABLE _rc
    )
    if(NOT _rc EQUAL 0)
      message(FATAL_ERROR
        \"install_name_tool could not set the load path on \${_lib} (exit \${_rc}). \"
        \"Installing it unchanged would ship a library that resolves to this \"
        \"machine's build directory.\")
    endif()
  ")
endif()

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
