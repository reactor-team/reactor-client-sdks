// Links the installed package and runs. Both halves matter: the link proves the
// exported target names a library that exists here, and running proves the
// native library it points at can actually be loaded — on macOS those are two
// different paths, and a package can get the first right and the second wrong.
#include <cstdio>
#include <reactor/version.hpp>
#include <string>

int main() {
  const std::string version{reactor::version()};
  if (version.empty()) {
    std::fputs("the installed SDK reports no version\n", stderr);
    return 1;
  }
  std::printf("linked and ran against the installed reactor-sdk %s\n", version.c_str());
  return 0;
}
