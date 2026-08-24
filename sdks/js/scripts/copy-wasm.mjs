#!/usr/bin/env node
// Copies the built `reactor-wasm` browser binding into `dist/wasm`, next to
// the rest of the built package, so `import("../wasm/reactor_wasm.js")` in
// src/internal/wasm.ts resolves from an npm install with no separate build
// step on the consumer's side. Run as part of `npm run build`, after tsup.
import { cpSync, existsSync, realpathSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/** Copies `wasmPkgDir` into `destDir`, refusing to leave `destDir` looking
 *  valid when it isn't. Exported so a test can drive it against temp
 *  directories instead of the real `crates/reactor-wasm/pkg`. */
export function copyWasm(wasmPkgDir, destDir) {
  const srcBinary = join(wasmPkgDir, 'reactor_wasm_bg.wasm');
  const destBinary = join(destDir, 'reactor_wasm_bg.wasm');

  if (!existsSync(wasmPkgDir)) {
    throw new Error(
      `${wasmPkgDir} does not exist. Build the browser binding first: \`mise run build:wasm\` (from the repo root).`,
    );
  }

  // Checked before the copy, not just after: cpSync only adds/overwrites
  // files that exist in the source, so a missing/empty source binary would
  // otherwise leave a stale, already-present dest binary looking valid
  // post-copy.
  if (!existsSync(srcBinary) || statSync(srcBinary).size === 0) {
    throw new Error(
      `${srcBinary} is missing or empty. Build the browser binding first: \`mise run build:wasm\` (from the repo root).`,
    );
  }

  cpSync(wasmPkgDir, destDir, { recursive: true });

  // `npm publish` runs `prepack` (which runs this), and a silently empty/missing
  // dist/wasm here means the published tarball is missing the binary — the
  // package installs fine and only fails for consumers, at their build time,
  // with an opaque "Module not found" (see the copy-wasm.mjs run that produced
  // exactly that, empty dist/wasm from an earlier incomplete build).
  if (!existsSync(destBinary) || statSync(destBinary).size === 0) {
    throw new Error(`${destBinary} is missing or empty after copy — refusing to leave a broken dist/wasm in place.`);
  }
}

// `import.meta.url` is always a `file://...` absolute URL, but
// `process.argv[1]` can be relative (`node scripts/copy-wasm.mjs`) — compared
// as strings that never matches, and this script silently no-ops. Resolving
// both through `realpathSync` before comparing makes it exact regardless of
// how the script was invoked.
const isMain = (() => {
  try {
    return realpathSync(fileURLToPath(import.meta.url)) === realpathSync(process.argv[1]);
  } catch {
    return false;
  }
})();

if (isMain) {
  const packageRoot = dirname(dirname(fileURLToPath(import.meta.url)));
  const wasmPkgDir = join(packageRoot, '..', '..', 'crates', 'reactor-wasm', 'pkg');
  const destDir = join(packageRoot, 'dist', 'wasm');

  try {
    copyWasm(wasmPkgDir, destDir);
    console.log(`copied ${wasmPkgDir} -> ${destDir}`);
  } catch (error) {
    console.error(`error: ${error.message}`);
    process.exit(1);
  }
}
