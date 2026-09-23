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

# Finding the JDK is not `dirname $(command -v javac)`.
#
# mise installs javac as a **shim** on some platforms, so that path resolves to the shim
# directory and not to a JDK — no include/jni.h, and this script used to exit before printing
# anything useful. $JAVA_HOME is the reliable answer where it is set (mise exports it), and the
# realpath of javac is the fallback.
if [ -n "${JAVA_HOME:-}" ] && [ -f "${JAVA_HOME}/include/jni.h" ]; then
  JAVA_HOME_DIR="$JAVA_HOME"
else
  JAVA_BIN="$(command -v javac || true)"
  if [ -z "$JAVA_BIN" ]; then
    echo "error: javac is not on PATH and JAVA_HOME is unset — run through mise" >&2
    exit 1
  fi
  JAVA_BIN="$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$JAVA_BIN")"
  JAVA_HOME_DIR="$(cd "$(dirname "$(dirname "$JAVA_BIN")")" && pwd)"
fi

if [ ! -f "$JAVA_HOME_DIR/include/jni.h" ]; then
  echo "error: no include/jni.h under '$JAVA_HOME_DIR'." >&2
  echo "       JAVA_HOME=${JAVA_HOME:-<unset>}; javac=$(command -v javac || echo '<none>')" >&2
  exit 1
fi
echo "==> JDK: $JAVA_HOME_DIR"

case "$(uname -s)" in
  Darwin) JNI_MD="darwin"; JVM_LIB="$JAVA_HOME_DIR/lib/server" ;;
  Linux)  JNI_MD="linux";  JVM_LIB="$JAVA_HOME_DIR/lib/server" ;;
  *) echo "error: the sanitizer harness runs on macOS and Linux" >&2; exit 1 ;;
esac

# Leak detection is off, on both platforms, and that is deliberate rather than a macOS
# limitation.
#
# This process embeds a JVM, which allocates a great deal it never frees before exit. With
# LeakSanitizer on, every one of those is reported, and the handful of bytes a forgotten
# reactor_free_string would leak is invisible in the noise — the gate would fail constantly for
# reasons no one here can fix. Suppressing JVM frames is a maintenance burden with its own way of
# going quietly wrong.
#
# What this harness is for is the class of bug that ends a process: use-after-free, double free,
# and freeing a pointer that was never allocated. ASan catches all three with leak detection off,
# on both platforms, and those are what this boundary actually produces.
#
# Leaks are answered by the endurance suite (A14), which watches RSS and handle counts trend over
# minutes — the right instrument for that question.

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
  ASAN_OPTIONS="detect_leaks=0:abort_on_error=0:print_stats=0" \
  "$OUT/asan-harness"
