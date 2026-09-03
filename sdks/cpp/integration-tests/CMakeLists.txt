# The C++ SDK integration suite: real FFI, real WebRTC, against a real model in
# production. Not part of `reactor_sdk_tests` — see README.md for why this is a
# separate binary with its own opt-in build flag rather than another file under
# tests/.
#
# Catch2 v3, pinned by tag — the same version and the same fetch-rather-than-
# expect reasoning as tests/CMakeLists.txt. Declaring and fetching it again here
# (instead of sharing the `tests` subdirectory's copy) is what keeps this
# directory buildable on its own, with `-DREACTOR_SDK_BUILD_TESTS=OFF`, the way
# CI's `cpp-integration-tests` job runs it.
include(FetchContent)

FetchContent_Declare(
  Catch2
  GIT_REPOSITORY https://github.com/catchorg/Catch2.git
  GIT_TAG v3.11.0
  GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(Catch2)

add_executable(
  reactor_sdk_integration_tests
  fixtures.cpp
  lifecycle_and_commands_test.cpp
  tracks_and_frames_test.cpp
  upload_and_conditioning_test.cpp
  multi_connection_test.cpp
  recording_test.cpp
  concurrency_and_races_test.cpp
  refusals_and_edge_cases_test.cpp
  audio_test.cpp
)

# Reaches past the public headers on purpose, same as tests/CMakeLists.txt:
# fixtures.cpp's mint_jwt() calls reactor_fetch_jwt() directly, because session
# adoption needs the token itself and the public API never hands one back.
target_include_directories(
  reactor_sdk_integration_tests
  PRIVATE "${REACTOR_FFI_INCLUDE_DIR}"
)

target_link_libraries(
  reactor_sdk_integration_tests
  PRIVATE reactor::sdk reactor::ffi Catch2::Catch2WithMain
)

target_compile_options(
  reactor_sdk_integration_tests
  PRIVATE
    $<$<COMPILE_LANG_AND_ID:CXX,GNU,Clang,AppleClang>:-Wall;-Wextra;-Werror>
    $<$<COMPILE_LANG_AND_ID:CXX,MSVC>:/W4;/WX>
)
