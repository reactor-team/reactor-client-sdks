#!/usr/bin/env bash
set -euo pipefail
: "${REACTOR_MODEL:?REACTOR_MODEL must be an owner-qualified production model}"
: "${REACTOR_TOKEN:?REACTOR_TOKEN must be a short-lived token or integration credential}"
: "${REACTOR_NATIVE_DIR:?REACTOR_NATIVE_DIR must point to the staged host natives}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root/sdks/kotlin"
./gradlew --no-daemon :reactor-core:testLiveIntegration \
  -Dreactor.jni.real.directory="$REACTOR_NATIVE_DIR"
