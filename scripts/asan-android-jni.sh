#!/usr/bin/env bash
#
# Run the Android JNI bridge's lifetime checks under AddressSanitizer, on the host.
#
# Why a host build at all, when there is an instrumented suite on a device: the device suite runs
# against the *real* library, and a callback writing through a freed pointer is invisible in a
# passing run. A sanitizer is the only thing that turns that into a failure. And the real library
# cannot be asked for `reactor_destroy` returning -1 — the single case where leaking the global
# references is correct — so that path needs a library that lies on request.
#
# The harness creates its own JVM in-process and calls the JNI entry points directly, so there is
# no System.loadLibrary and no LD_PRELOAD of a sanitizer runtime into someone else's `java`
# process. It is one instrumented binary that happens to contain a JVM.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NATIVE="$REPO_ROOT/sdks/android/native"
OUT="$REPO_ROOT/target/android-jni-asan"

JAVA_BIN="$(command -v javac)"
if [ -z "$JAVA_BIN" ]; then
  echo "error: javac is not on PATH — run through mise (mise run test:android:asan)" >&2
  exit 1
fi
JAVA_HOME_DIR="$(cd "$(dirname "$(dirname "$JAVA_BIN")")" && pwd)"
if [ ! -f "$JAVA_HOME_DIR/include/jni.h" ]; then
  echo "error: no jni.h under $JAVA_HOME_DIR — is javac from a JDK?" >&2
  exit 1
fi

# LeakSanitizer is Linux-only, and upstream ASan *aborts* on macOS when asked for it rather than
# warning — so it is switched on per platform. macOS still catches use-after-free, double free
# and freeing a non-heap pointer, which are the failures this boundary actually produces; the
# leak half of the answer comes from the Linux CI run.
case "$(uname -s)" in
  Darwin) JNI_MD="darwin"; JVM_LIB="$JAVA_HOME_DIR/lib/server"; DETECT_LEAKS=0 ;;
  Linux)  JNI_MD="linux";  JVM_LIB="$JAVA_HOME_DIR/lib/server"; DETECT_LEAKS=1 ;;
  *) echo "error: the sanitizer harness runs on macOS and Linux" >&2; exit 1 ;;
esac

mkdir -p "$OUT/classes"

echo "==> Compiling the listener"
javac -d "$OUT/classes" "$NATIVE/tests/AsanListener.java"

echo "==> Building the harness with AddressSanitizer"
# -fno-omit-frame-pointer so a report names the frame that did it rather than an address.
# The bridge is compiled from source here rather than linked from the AAR: the AAR's copy is an
# arm64 Android library, and this runs on the host.
"${CXX:-c++}" -std=c++17 -g -O1 -fno-omit-frame-pointer \
  -fsanitize=address \
  -Wall -Wextra -Werror \
  -I "$NATIVE/include" \
  -I "$REPO_ROOT/crates/reactor-ffi/include" \
  -I "$JAVA_HOME_DIR/include" -I "$JAVA_HOME_DIR/include/$JNI_MD" \
  -o "$OUT/asan-harness" \
  "$NATIVE/tests/asan_harness.cpp" \
  "$NATIVE/src/client.cpp" \
  "$NATIVE/src/abi.cpp" \
  "$NATIVE/tests/fake_ffi.cpp" \
  -L "$JVM_LIB" -ljvm -Wl,-rpath,"$JVM_LIB"

echo "==> Running"
REACTOR_ASAN_CLASSPATH="$OUT/classes" \
  ASAN_OPTIONS="detect_leaks=${DETECT_LEAKS}:abort_on_error=0:print_stats=0" \
  "$OUT/asan-harness"
