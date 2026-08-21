// The scaffold's own test: it proves the chain, not the SDK.
//
// There is no object model yet. What this asserts is that CMake generated the
// version header, the library compiled and linked, ctest found the binary, and
// Catch2 ran it — so the next twelve pull requests are reviewed against a
// pipeline that was already green.

#include <catch2/catch_test_macros.hpp>

#include "reactor/version.hpp"

TEST_CASE("the linked library reports the version CMake generated") {
  // Deliberately compared against the macro: they come from the same
  // configure_file, so a mismatch means the headers on the include path are not
  // the ones this library was built from.
  CHECK(reactor::version() == std::string_view{REACTOR_SDK_VERSION});
  CHECK_FALSE(reactor::version().empty());
}
