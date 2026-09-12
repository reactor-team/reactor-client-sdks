#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17}"
output="$repo_root/sdks/kotlin/reactor-core/build/${JNI_TEST_OUTPUT:-jni-test}"
flags=(-fPIC)
if [[ "${JNI_ASAN:-0}" == 1 ]]; then
  flags+=(-fsanitize=address -fno-omit-frame-pointer -g)
fi
mkdir -p "$output"
case "$(uname -s)" in
  Darwin) platform=darwin; library=libreactor_jni_test.dylib ;;
  Linux) platform=linux; library=libreactor_jni_test.so ;;
  *) echo 'JNI boundary tests currently require macOS or Linux' >&2; exit 1 ;;
esac
python3 "$repo_root/scripts/kotlin-jni-headers.py" "$JAVA_HOME/bin/javap" \
  "$repo_root/sdks/kotlin/reactor-core/build/classes/kotlin/main" inc.reactor.sdk.internal.NativeAbi \
  "$repo_root/sdks/kotlin/reactor-core/build/classes/kotlin/main" inc.reactor.sdk.internal.NativeClient \
  "$repo_root/sdks/kotlin/reactor-core/build/classes/kotlin/test" inc.reactor.sdk.internal.NativeBoundaryTest \
  "$repo_root/sdks/kotlin/reactor-core/build/classes/kotlin/test" inc.reactor.sdk.internal.LifecycleTest \
  "$repo_root/sdks/kotlin/reactor-core/build/classes/kotlin/test" inc.reactor.sdk.internal.MediaReceiveTest \
  "$repo_root/sdks/kotlin/reactor-core/build/classes/kotlin/test" inc.reactor.sdk.internal.MediaSendTest \
  "$repo_root/sdks/kotlin/reactor-core/build/classes/kotlin/test" inc.reactor.sdk.internal.CommandTest \
  > "$output/jni_generated.h"
"${CXX:-c++}" "${flags[@]}" -std=c++17 -shared -fPIC -pthread -Wall -Wextra -Werror \
  -I "$output" -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/$platform" \
  -I "$repo_root/crates/reactor-ffi/include" -I "$repo_root/sdks/kotlin/native/include" \
  "$repo_root/sdks/kotlin/native/src/client.cpp" "$repo_root/sdks/kotlin/native/src/abi.cpp" "$repo_root/sdks/kotlin/native/tests/fake.cpp" \
  -o "$output/$library"
