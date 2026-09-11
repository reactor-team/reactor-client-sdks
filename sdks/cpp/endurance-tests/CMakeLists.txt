# The C++ SDK endurance/leak suite: real FFI, real WebRTC, against a real
# model in production, run for minutes to hours rather than seconds. Mirrors
# ../integration-tests/CMakeLists.txt file-for-file — see its own comments for
# why this fetches its own Catch2 rather than sharing tests/'s copy, and why
# it is a separate opt-in build (REACTOR_SDK_BUILD_ENDURANCE_TESTS, off by
# default) rather than folded into an existing suite.
include(FetchContent)

FetchContent_Declare(
  Catch2
  GIT_REPOSITORY https://github.com/catchorg/Catch2.git
  GIT_TAG v3.11.0
  GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(Catch2)

add_executable(
  reactor_sdk_endurance_tests
  helpers.cpp
  test_lifecycle_churn.cpp
  test_session_churn.cpp
)

# Reuses ../integration-tests/'s fixtures (new_reactor, paced_connect,
# solid_bgra_frame, ReactorFactory) rather than re-deriving connection setup
# and pacing — same reasoning as sdks/python/endurance-tests/helpers.py
# importing straight from ../integration-tests/conftest.py.
target_sources(reactor_sdk_endurance_tests PRIVATE ../integration-tests/fixtures.cpp)
target_include_directories(reactor_sdk_endurance_tests PRIVATE ../integration-tests)

# fixtures.cpp's mint_jwt() reaches past the public header, straight to
# reactor_fetch_jwt() — same reason ../integration-tests/CMakeLists.txt
# itself adds this same include dir for the same file.
target_include_directories(
  reactor_sdk_endurance_tests
  PRIVATE "${REACTOR_FFI_INCLUDE_DIR}"
)

target_link_libraries(
  reactor_sdk_endurance_tests
  PRIVATE reactor::sdk Catch2::Catch2WithMain
)

target_compile_options(
  reactor_sdk_endurance_tests
  PRIVATE
    $<$<COMPILE_LANG_AND_ID:CXX,GNU,Clang,AppleClang>:-Wall;-Wextra;-Werror>
    $<$<COMPILE_LANG_AND_ID:CXX,MSVC>:/W4;/WX>
)
