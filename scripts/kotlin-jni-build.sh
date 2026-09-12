#!/usr/bin/env bash
# Build the development JNI bridge against an already-built real FFI library.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17}"
mode="${1:-host}"
cd "$repo_root/sdks/kotlin"
./gradlew --no-daemon :reactor-core:classes
output="$repo_root/target/kotlin-jni/$mode"
mkdir -p "$output"
python3 "$repo_root/scripts/kotlin-jni-headers.py" "$JAVA_HOME/bin/javap" \
  "$repo_root/sdks/kotlin/reactor-core/build/classes/kotlin/main" inc.reactor.sdk.internal.NativeAbi \
  "$repo_root/sdks/kotlin/reactor-core/build/classes/kotlin/main" inc.reactor.sdk.internal.NativeClient \
  > "$output/jni_generated.h"
case "$(uname -s)" in
  Darwin) host_tag=darwin-x86_64; platform=darwin; suffix=dylib ;;
  Linux) host_tag=linux-x86_64; platform=linux; suffix=so ;;
  *) echo 'Development JNI build requires macOS or Linux' >&2; exit 1 ;;
esac
flags=()
if [[ "$mode" == android ]]; then
  : "${ANDROID_HOME:?Set ANDROID_HOME}"
  compiler="$ANDROID_HOME/ndk/29.0.14206865/toolchains/llvm/prebuilt/$host_tag/bin/aarch64-linux-android26-clang++"
  ffi_dir="$repo_root/target/aarch64-linux-android/release"
  suffix=so
  flags+=(-static-libstdc++ "-Wl,-z,max-page-size=16384")
elif [[ "$mode" == host ]]; then
  compiler="${CXX:-c++}"
  ffi_dir="$repo_root/target/release"
  flags+=(-I "$JAVA_HOME/include" -I "$JAVA_HOME/include/$platform")
  if [[ "$platform" == darwin ]]; then
    flags+=("-Wl,-rpath,@loader_path")
  else
    flags+=("-Wl,-rpath,\$ORIGIN")
  fi
else
  echo "Usage: $0 {host|android}" >&2; exit 2
fi
cp "$ffi_dir/libreactor_ffi.$suffix" "$output/"
if [[ "$platform" == darwin && "$mode" == host ]]; then
  install_name_tool -id @rpath/libreactor_ffi.dylib "$output/libreactor_ffi.dylib"
  codesign --force --sign - "$output/libreactor_ffi.dylib"
fi
"$compiler" -std=c++17 -shared -fPIC -pthread -Wall -Wextra -Werror "${flags[@]}" \
  -I "$output" -I "$repo_root/crates/reactor-ffi/include" -I "$repo_root/sdks/kotlin/native/include" \
  "$repo_root/sdks/kotlin/native/src/abi.cpp" "$repo_root/sdks/kotlin/native/src/client.cpp" \
  -L "$output" -lreactor_ffi -o "$output/libreactor_jni.$suffix"
if [[ "$mode" == android ]]; then
  python3 "$repo_root/scripts/check-kotlin-native.py" --elf-only "$output/libreactor_jni.so"
  probe_jni_dir="$repo_root/sdks/kotlin/native-probe/build/native/jniLibs/arm64-v8a"
  mkdir -p "$probe_jni_dir"
  cp "$output/libreactor_jni.so" "$probe_jni_dir/"
fi
