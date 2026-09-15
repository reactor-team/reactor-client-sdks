# Catch2 v3, pinned by tag. Fetched rather than expected on the system: a test
# framework a contributor has to install by hand is one CI and a laptop end up
# disagreeing about.
include(FetchContent)

FetchContent_Declare(
  Catch2
  GIT_REPOSITORY https://github.com/catchorg/Catch2.git
  GIT_TAG v3.11.0
  GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(Catch2)

set(REACTOR_TEST_SOURCES
  command_test.cpp
  errors_test.cpp
  ffi_test.cpp
  handlers_test.cpp
  reactor_test.cpp
  recording_test.cpp
  stats_test.cpp
  track_test.cpp
  smoke_test.cpp
  # The endurance-tests suite's reporting/trend modules, pulled in by source
  # rather than a separate library target: both are deliberately free of any
  # FFI/native-library dependency (see ../endurance-tests/report.hpp's own
  # top comment), so they need nothing ../endurance-tests/ itself needs
  # (REACTOR_SDK_BUILD_ENDURANCE_TESTS, a real API key) to be unit-tested —
  # same reasoning as sdks/python/tests/test_endurance_report.py and
  # test_trends.py living in the fast unit suite rather than beside the
  # endurance suite they describe.
  ../endurance-tests/report.cpp
  ../endurance-tests/trends.cpp
  report_test.cpp
  trends_test.cpp
)

# The device helpers are an optional target, so their tests come and go with it.
if(REACTOR_SDK_BUILD_AUDIO)
  list(APPEND REACTOR_TEST_SOURCES audio_test.cpp)
endif()

add_executable(reactor_sdk_tests ${REACTOR_TEST_SOURCES})

if(REACTOR_SDK_BUILD_AUDIO)
  target_link_libraries(reactor_sdk_tests PRIVATE reactor::sdk_audio)
endif()

# The tests reach past the public headers on purpose: the boundary — string
# ownership, the symbol table, teardown — is where a binding's bugs live, and
# none of it is reachable through <reactor/…>.
target_include_directories(
  reactor_sdk_tests
  PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/../src"
    "${REACTOR_FFI_INCLUDE_DIR}"
    # So report_test.cpp/trends_test.cpp can `#include "report.hpp"` /
    # `#include "trends.hpp"` the same way ../endurance-tests/*.cpp itself
    # does, rather than a relative "../endurance-tests/report.hpp" in every
    # test file.
    "${CMAKE_CURRENT_SOURCE_DIR}/../endurance-tests"
)

target_link_libraries(reactor_sdk_tests PRIVATE reactor::sdk Catch2::Catch2WithMain)

target_compile_options(
  reactor_sdk_tests
  PRIVATE
    $<$<COMPILE_LANG_AND_ID:CXX,GNU,Clang,AppleClang>:-Wall;-Wextra;-Werror>
    $<$<COMPILE_LANG_AND_ID:CXX,MSVC>:/W4;/WX>
)

list(APPEND CMAKE_MODULE_PATH "${Catch2_SOURCE_DIR}/extras")
include(Catch)
catch_discover_tests(reactor_sdk_tests)
