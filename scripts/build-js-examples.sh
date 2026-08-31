#!/usr/bin/env bash
# Smoke-build every JS SDK example against the SDK's current dist/.
#
# Each example is its own Vite app pulling `@reactor-team/js-sdk` in via
# `file:../..`, so `npm install` re-links whatever `mise run build:js` last
# produced and `vite build` bundles against it. This is what `lint:js` /
# `test:js` don't cover: a renamed or removed export breaks an example's
# import at bundle time even though the SDK's own unit tests (mocked
# ReactorClient) and eslint (per-file, no cross-package resolution) stay
# green. No REACTOR_API_KEY needed - this never connects, it only builds.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
examples_dir="$repo_root/sdks/js/examples"

for example in "$examples_dir"/*/; do
  [ -f "$example/package.json" ] || continue
  name="$(basename "$example")"
  echo "== $name =="
  (cd "$example" && npm install && npm run build)
done
