#!/usr/bin/env bash
# Same entry points locally and in CI. Never silently skip a missing toolchain.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root/sdks/kotlin"
case "${1:-}" in
  lint) shift; exec ./gradlew --no-daemon ktlintCheck :reactor-android:lint :reactor-android-media:lint "$@" ;;
  format) shift; exec ./gradlew --no-daemon ktlintFormat "$@" ;;
  build) shift; exec ./gradlew --no-daemon :reactor-core:assemble :reactor-jvm:assemble :reactor-desktop:assemble :reactor-android:assembleRelease :reactor-android-media:assembleRelease :reactor-android:assembleDebugAndroidTest "$@" ;;
  test) shift; exec ./gradlew --no-daemon :reactor-core:test :reactor-jvm:test :reactor-desktop:test :reactor-android:testDebugUnitTest :reactor-android-media:testDebugUnitTest "$@" ;;
  android-test) shift; exec ./gradlew --no-daemon :reactor-android:connectedDebugAndroidTest :reactor-android-media:connectedDebugAndroidTest "$@" ;;
  *) echo "Usage: $0 {lint|format|build|test|android-test} [Gradle options]" >&2; exit 2 ;;
esac
