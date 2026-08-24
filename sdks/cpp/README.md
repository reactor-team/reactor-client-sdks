# Reactor C++ SDK

A C++17 client for [Reactor](https://reactor.inc), built on `libreactor_ffi` —
the same Rust core the [Python SDK](../python) binds.

> **Status: under construction.** This directory currently holds the build
> setup and nothing else. The object model arrives over the pull requests
> tracked by [REA-5380](https://linear.app/reactor-team/issue/REA-5380);
> `reactor::Reactor`, `reactor::Track` and the seven examples are not here yet.
> The full README — platform table, quickstart, the API — lands with the release.

## Using it

One target carries everything: the include directories, the C++17 requirement
and the native library.

```cmake
find_package(reactor-sdk REQUIRED)
target_link_libraries(app PRIVATE reactor::sdk)
```

## Building it from this repo

The SDK links a native library this repository builds, and there is no copy of
it in a fresh checkout:

```bash
mise run build:ffi   # cargo build -p reactor-ffi --release
mise run build:cpp   # cmake + ninja
mise run test:cpp    # ctest
mise run lint:cpp    # clang-format --dry-run + clang-tidy
```

Building the native library is a separate step, not a dependency of
`build:cpp`. On Linux it compiles reactor-webrtc-sys's C++ glue with the
pinned conda clang++, and that toolchain brings a sysroot whose glibc cannot
resolve the symbols the resulting `.so` needs — so the SDK itself has to be
built with the platform compiler. Two compilers, two commands.

To build against a library somewhere else — a release archive's `lib/`, or
another checkout — point CMake at it:

```bash
cmake -S sdks/cpp -B sdks/cpp/build -G Ninja -DREACTOR_FFI_LIB_DIR=/path/to/lib
```

**Rebuild the native library after pulling changes under `crates/`.** A
signature that moved in the FFI but not in your build still links and then
corrupts the stack at the call, which looks like a hang rather than a version
error. `reactor_abi_version()` turns that into a message — but only if the
library on disk is the one you think it is.

## Layout

| Path | |
|---|---|
| `include/reactor/` | the public headers; a consumer includes `<reactor/…>` and never `<reactor_ffi.h>` |
| `src/` | the implementation, including the FFI boundary |
| `tests/` | Catch2 unit tests, run by `ctest` |
| `.clang-format`, `.clang-tidy` | what `lint:cpp` enforces |

## Contributing

See the repo-wide [`CONTRIBUTING.md`](../../CONTRIBUTING.md) for the toolchain,
DCO and commit conventions.
