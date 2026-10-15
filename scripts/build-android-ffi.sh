#!/usr/bin/env bash
#
# Cross-build libreactor_ffi for Android and stage it, with the matching libwebrtc JAR, where the
# AAR build picks both up.
#
# Two artifacts come out of this, and they have to agree with each other:
#
#   * libreactor_ffi.so — the Rust core, one per ABI.
#   * libwebrtc.jar     — the Java half of WebRTC, whose org.webrtc/org.jni_zero classes are
#                         relocated to a package the native library names at run time. The
#                         relocation is chosen by a gn arg in reactor-webrtc, so a mismatch is
#                         invisible until WebRTC bootstrap aborts on a device (REA-6249).
#                         scripts/check-android-native.py is what catches it here instead.
#
# The JAR is not published anywhere: it is produced as a side effect of reactor-webrtc-sys's
# build, inside the Cargo target directory for the Android target. Taking it from there is what
# guarantees it is the JAR belonging to the libwebrtc this .so linked, rather than one that
# happened to be lying around.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STAGE="$REPO_ROOT/sdks/android/reactor-sdk-android/build/reactor-native"

# arm64-v8a only, and that is a platform constraint rather than a choice: reactor-webrtc
# publishes an android arm64 prebuilt and no other Android ABI. x86_64 joins this list when
# REA-6551 lands one, which is also what unblocks an emulator on an x86_64 CI runner.
ABIS=("arm64-v8a")

# A case statement rather than an associative array: macOS ships bash 3.2, where `declare -A` is
# a syntax error and every lookup reads as an unbound variable under `set -u`. This script has to
# run on a laptop as well as on a CI runner.
rust_target_for() {
  case "$1" in
    arm64-v8a) echo "aarch64-linux-android" ;;
    x86_64)    echo "x86_64-linux-android" ;;
    *) echo "error: unknown ABI '$1'" >&2; return 1 ;;
  esac
}

# The API level the native code is compiled against — the same minSdk the Gradle build declares
# in reactor-android-conventions.gradle.kts. Raising one without the other produces a library
# that dlopens on the build machine and not on a consumer's device.
API_LEVEL=26

# Accept artifacts staged by someone else — but verify them, and only when asked.
#
# The emulator job downloads what the cross-build job produced for the same commit, because the
# runner that can boot an arm64 image is not the one with an NDK host toolchain. Without this the
# mise task's `depends` would rebuild everything that artifact exists to avoid.
#
# Opt-in rather than "skip if the files are there", and that is the load-bearing half: a stale
# .so links, resolves, and corrupts the stack at the first call that gained a parameter. An
# implicit skip would make that the default for anyone with an old build lying around. The checks
# still run, so a prestaged artifact that is wrong is caught here rather than on a device.
if [ "${REACTOR_ANDROID_NATIVE_PRESTAGED:-}" = "1" ]; then
  echo "==> REACTOR_ANDROID_NATIVE_PRESTAGED=1 — verifying staged artifacts instead of building"
  [ -f "$STAGE/libs/libwebrtc.jar" ] || {
    echo "error: REACTOR_ANDROID_NATIVE_PRESTAGED=1 but $STAGE/libs/libwebrtc.jar is missing" >&2
    exit 1
  }
  for abi in "${ABIS[@]}"; do
    so="$STAGE/jniLibs/$abi/libreactor_ffi.so"
    [ -f "$so" ] || {
      echo "error: REACTOR_ANDROID_NATIVE_PRESTAGED=1 but $so is missing" >&2
      exit 1
    }
    python3 "$REPO_ROOT/scripts/check-android-native.py" "$so" "$STAGE/libs/libwebrtc.jar"
  done
  echo "==> Staged artifacts accepted from ${STAGE#"$REPO_ROOT"/}"
  exit 0
fi

if [ -z "${ANDROID_HOME:-}" ]; then
  echo "error: ANDROID_HOME is unset — run through mise (mise run build:android:native)" >&2
  exit 1
fi

# The NDK comes from sdks/android/sdk-packages.txt, installed by setup-android-sdk.sh. Read the
# version from that file rather than hardcoding it here: two places to bump is one too many, and
# this is the place that would be forgotten.
NDK_VERSION="$(sed 's/#.*//' "$REPO_ROOT/sdks/android/sdk-packages.txt" \
  | tr -d '[:blank:]' | grep '^ndk/' | head -1 | cut -d/ -f2)"
if [ -z "$NDK_VERSION" ]; then
  echo "error: no ndk/<version> pinned in sdks/android/sdk-packages.txt" >&2
  exit 1
fi

export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$NDK_VERSION"
if [ ! -d "$ANDROID_NDK_HOME" ]; then
  echo "error: NDK $NDK_VERSION is not installed. Run: mise run setup:android" >&2
  exit 1
fi

echo "==> NDK $NDK_VERSION, API $API_LEVEL, ABIs: ${ABIS[*]}"

mkdir -p "$STAGE/jniLibs" "$STAGE/libs"

for abi in "${ABIS[@]}"; do
  target="$(rust_target_for "$abi")"
  echo "==> Building libreactor_ffi for $abi ($target)"
  ( cd "$REPO_ROOT" && cargo ndk --target "$abi" --platform "$API_LEVEL" build -p reactor-ffi --release )

  so="$REPO_ROOT/target/$target/release/libreactor_ffi.so"
  [ -f "$so" ] || { echo "error: cargo produced no $so" >&2; exit 1; }

  mkdir -p "$STAGE/jniLibs/$abi"
  cp "$so" "$STAGE/jniLibs/$abi/"

  # The JAR is architecture-independent, but it is produced per target directory. Taken from the
  # first ABI built; every later one must agree, which is checked below rather than promised.
  # The loop used to repack over the same path on every pass, so with a second ABI the last one
  # would simply have won — harmless with arm64-v8a alone and wrong the moment REA-6551 lands.
  jar="$(find "$REPO_ROOT/target/$target/release/build" -name libwebrtc.jar -print -quit)"
  [ -n "$jar" ] || { echo "error: no libwebrtc.jar under target/$target — did reactor-webrtc-sys build?" >&2; exit 1; }

  # Keep only `inc/`, which is everything the relocation produced: inc/reactor/org/webrtc,
  # inc/reactor/org/jni_zero, and inc/reactor/J/N.class — the generated JNI registration class.
  #
  # The jar as built also carries a whole Kotlin stdlib (997 entries), androidx.annotation, and
  # Chromium's and IntelliJ's annotation packages. Shipping those inside the AAR puts them on
  # every consumer's compile classpath, where they collide with the same classes arriving
  # normally: `Duplicate class androidx.annotation.AnimRes … found in modules annotation-jvm and
  # libwebrtc.jar`, and two copies of kotlin/annotation/annotation.kotlin_builtins. That is a
  # build failure in an app that has done nothing wrong, and it is not ours to hand them.
  #
  # Found by the instrumented test's own build hitting it first.
  repack="$(mktemp -d)"
  ( cd "$repack" && unzip -q "$jar" 'inc/*' )
  [ -d "$repack/inc" ] || { echo "error: $jar contains no inc/ — has the JNI package prefix changed?" >&2; exit 1; }
  ( cd "$repack" && jar --create --file "$repack/libwebrtc.jar" inc )

  if [ -f "$STAGE/libs/libwebrtc.jar" ]; then
    # A second ABI must carry the same Java half. If it does not, the .so and the JAR in the AAR
    # came from different libwebrtc builds, and the mismatch is invisible until WebRTC bootstrap
    # aborts on a device (REA-6249). Compared by content, because a rebuilt jar is not
    # byte-identical.
    if ! unzip -l "$STAGE/libs/libwebrtc.jar" | awk '{print $1, $4}' | sort > "$repack/staged.list" \
       || ! unzip -l "$repack/libwebrtc.jar" | awk '{print $1, $4}' | sort > "$repack/new.list" \
       || ! diff -q "$repack/staged.list" "$repack/new.list" >/dev/null; then
      echo "error: $abi's libwebrtc.jar differs from the one already staged — the .so and the" >&2
      echo "       Java half would come from different libwebrtc builds." >&2
      diff "$repack/staged.list" "$repack/new.list" >&2 || true
      rm -rf "$repack"
      exit 1
    fi
  else
    cp "$repack/libwebrtc.jar" "$STAGE/libs/libwebrtc.jar"
  fi
  rm -rf "$repack"

  echo "==> Checking $abi artifacts"
  python3 "$REPO_ROOT/scripts/check-android-native.py" \
    "$STAGE/jniLibs/$abi/libreactor_ffi.so" "$STAGE/libs/libwebrtc.jar"
done

echo "==> Staged into ${STAGE#"$REPO_ROOT"/}"
