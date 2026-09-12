#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_HOME:?Set ANDROID_HOME}"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17}"
case "$(uname -s)" in
  Darwin) host_tag=darwin-x86_64 ;;
  Linux) host_tag=linux-x86_64 ;;
  *) echo 'Android JNI tests require macOS or Linux' >&2; exit 1 ;;
esac
cd "$repo_root/sdks/kotlin"
./gradlew --no-daemon :reactor-core:buildJniTests
output="$repo_root/sdks/kotlin/native-probe/build/native/jniLibs/arm64-v8a"
mkdir -p "$output"
compiler="$ANDROID_HOME/ndk/29.0.14206865/toolchains/llvm/prebuilt/$host_tag/bin/aarch64-linux-android26-clang++"
"$compiler" -std=c++17 -shared -fPIC -pthread -static-libstdc++ -Wall -Wextra -Werror \
  -Wl,-z,max-page-size=16384 \
  -I "$repo_root/sdks/kotlin/reactor-core/build/jni-test" \
  -I "$repo_root/crates/reactor-ffi/include" -I "$repo_root/sdks/kotlin/native/include" \
  "$repo_root/sdks/kotlin/native/src/client.cpp" "$repo_root/sdks/kotlin/native/src/abi.cpp" "$repo_root/sdks/kotlin/native/tests/fake.cpp" \
  -o "$output/libreactor_jni_test.so"
python3 "$repo_root/scripts/check-kotlin-native.py" --elf-only "$output/libreactor_jni_test.so"
./gradlew --no-daemon :native-probe:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=inc.reactor.sdk.internal.NativeBoundaryTest,inc.reactor.sdk.internal.LifecycleTest,inc.reactor.sdk.internal.MediaReceiveTest,inc.reactor.sdk.internal.MediaSendTest,inc.reactor.sdk.internal.CommandTest,inc.reactor.sdk.internal.UploadTest,inc.reactor.sdk.internal.ContentUploadTest
