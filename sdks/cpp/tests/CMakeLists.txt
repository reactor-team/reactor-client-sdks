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

add_executable(
  reactor_sdk_tests
  errors_test.cpp
  ffi_test.cpp
  smoke_test.cpp
)

# The tests reach past the public headers on purpose: the boundary — string
# ownership, the symbol table, teardown — is where a binding's bugs live, and
# none of it is reachable through <reactor/…>.
target_include_directories(
  reactor_sdk_tests
  PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/../src"
    "${REACTOR_FFI_INCLUDE_DIR}"
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
