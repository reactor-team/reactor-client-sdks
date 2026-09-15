#!/usr/bin/env bash
# Release rehearsal only: writes a local Maven repository, never a registry.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
platform="${1:?Pass the desktop platform}"
cd "$repo_root"
cargo build --locked -p reactor-ffi --release
python3 scripts/kotlin-release-build.py
stage="$(mktemp -d)"
python3 scripts/kotlin-distribution.py "$platform" target/kotlin-release/lib "$stage/natives"
python3 scripts/check-kotlin-distribution.py "$platform" "$stage/natives/$platform"
cd sdks/kotlin
./gradlew --no-daemon :reactor-core:publishAllPublicationsToStagingRepository \
  :reactor-jvm:publishAllPublicationsToStagingRepository \
  :reactor-desktop:publishAllPublicationsToStagingRepository \
  "-PreactorNativeDirectory=$stage/natives" "-PreactorNativePlatform=$platform" \
  "-PreactorStagingRepository=$stage/maven"
cd "$repo_root"
python3 scripts/kotlin-distribution-test.py "$stage/maven" "$platform"
mkdir -p dist/kotlin
cp -R "$stage/maven" "dist/kotlin/$platform"
