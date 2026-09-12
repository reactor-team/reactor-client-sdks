#!/usr/bin/env bash
# K02 diagnostic build, not a publishing path. See sdks/kotlin/docs/0002-native-probe.md.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
if [[ "${1:-}" == host ]]; then
  exec cargo build --locked -p reactor-ffi --release
fi
mode="${1:-}"
if [[ "$mode" != android && "$mode" != android-test ]]; then
  echo "Usage: $0 {host|android|android-test}" >&2
  exit 2
fi
: "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK installation}"
ndk="$ANDROID_HOME/ndk/29.0.14206865"
case "$(uname -s)" in
  Darwin) host_tag=darwin-x86_64 ;;
  Linux) host_tag=linux-x86_64 ;;
  *) echo "Android cross-build requires a macOS or Linux host" >&2; exit 1 ;;
esac
compiler_bin="$ndk/toolchains/llvm/prebuilt/$host_tag/bin"
[[ -x "$compiler_bin/aarch64-linux-android26-clang++" ]] || {
  echo 'Install NDK 29.0.14206865 with sdkmanager first' >&2; exit 1;
}
native_dir="$repo_root/target/kotlin-native"
mkdir -p "$native_dir/prebuilt"
archive="$native_dir/reactor-webrtc-p7-android-arm64-release.tar.zst"
url=https://github.com/reactor-team/reactor-webrtc/releases/download/webrtc-7907-a5ddff60-p7/reactor-webrtc-android-arm64-release.tar.zst
if [[ ! -f "$archive" ]]; then
  curl --fail --location --retry 3 "$url" -o "$archive.part"
  mv "$archive.part" "$archive"
fi
python3 - "$archive" <<'PY'
import hashlib
import sys
from pathlib import Path
expected = "f9fee15aa6ebaee94ef081caead78317e7957a12c0fb54f43685e3920cb77973"
if hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest() != expected:
    raise SystemExit("WebRTC archive checksum mismatch; remove the cached archive and retry")
PY
tar -xf "$archive" -C "$native_dir/prebuilt"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$compiler_bin/aarch64-linux-android26-clang"
export CC_aarch64_linux_android="$compiler_bin/aarch64-linux-android26-clang"
export CXX_aarch64_linux_android="$compiler_bin/aarch64-linux-android26-clang++"
export AR_aarch64_linux_android="$compiler_bin/llvm-ar"
export REACTOR_WEBRTC_LIB_DIR="$native_dir/prebuilt"
cargo build --locked -p reactor-ffi --release --target aarch64-linux-android
library="${CARGO_TARGET_DIR:-$repo_root/target}/aarch64-linux-android/release/libreactor_ffi.so"
"$compiler_bin/llvm-readelf" -dW "$library" | sed -n '/NEEDED/p'
python3 scripts/check-kotlin-native.py "$library" "$native_dir/prebuilt/lib/libwebrtc.jar"

if [[ "$mode" == android-test ]]; then
  probe_dir="$repo_root/sdks/kotlin/native-probe/build/native"
  mkdir -p "$probe_dir/jniLibs/arm64-v8a"
  cp "$library" "$probe_dir/jniLibs/arm64-v8a/"
  cp "$native_dir/prebuilt/lib/libwebrtc.jar" "$probe_dir/libwebrtc.jar"
  "$compiler_bin/aarch64-linux-android26-clang" -shared -fPIC -Wall -Wextra -Werror \
    -Wl,-z,max-page-size=16384 -I "$repo_root/crates/reactor-ffi/include" \
    "$repo_root/sdks/kotlin/native-probe/src/main/c/probe.c" \
    -L "$(dirname "$library")" -lreactor_ffi \
    -o "$probe_dir/jniLibs/arm64-v8a/libreactor_probe.so"
  python3 scripts/check-kotlin-native.py --elf-only \
    "$probe_dir/jniLibs/arm64-v8a/libreactor_probe.so"
  cd "$repo_root/sdks/kotlin"
  ./gradlew --no-daemon :native-probe:connectedDebugAndroidTest
fi
