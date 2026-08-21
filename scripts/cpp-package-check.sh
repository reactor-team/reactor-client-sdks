#!/usr/bin/env bash
# Install the C++ SDK package, move it, and build a consumer against it.
#
# What this catches and the SDK's own tests cannot: those link `reactor::sdk`
# from the build tree, where every path an export could have baked in still
# resolves. A release archive is unpacked elsewhere, on a machine that never
# built it — so the FFI is staged under a directory this deletes before the
# consumer configures, and the install tree is moved before it is used. A
# package that reaches back into the build machine fails here and nowhere else.
#
# libreactor_ffi is expected to exist already rather than built here: on Linux
# `build:ffi` compiles reactor-webrtc-sys's glue with the pinned conda clang++
# while everything after it runs under the platform one, and building it again
# from here would recompile that glue with the wrong compiler.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/ffi"
copied=0
for lib in target/release/libreactor_ffi.dylib target/release/libreactor_ffi.so; do
  if [ -f "$lib" ]; then
    cp "$lib" "$work/ffi/"
    copied=1
  fi
done
if [ "$copied" = "0" ]; then
  echo "cpp-package-check: no libreactor_ffi under target/release - run \`mise run build:ffi\`" >&2
  exit 1
fi

cmake -S sdks/cpp -B "$work/build" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DREACTOR_SDK_BUILD_TESTS=OFF \
  -DREACTOR_FFI_LIB_DIR="$work/ffi" \
  -DCMAKE_INSTALL_PREFIX="$work/staged"
cmake --build "$work/build"
cmake --install "$work/build"

# The build machine's library, gone - as it is for whoever unpacked an archive.
rm -rf "$work/ffi"
# And the tree itself, somewhere it has never been.
mv "$work/staged" "$work/unpacked"

cmake -S sdks/cpp/tests/package -B "$work/consumer" -G Ninja \
  -DCMAKE_PREFIX_PATH="$work/unpacked"
cmake --build "$work/consumer"
"$work/consumer/consumer"
