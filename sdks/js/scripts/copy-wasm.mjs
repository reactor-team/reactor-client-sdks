#!/usr/bin/env node
// Copies the built `reactor-wasm` browser binding into `dist/wasm`, next to
// the rest of the built package, so `import("../wasm/reactor_wasm.js")` in
// src/internal/wasm.ts resolves from an npm install with no separate build
// step on the consumer's side. Run as part of `npm run build`, after tsup.
import { cpSync, existsSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const packageRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const wasmPkgDir = join(packageRoot, '..', '..', 'crates', 'reactor-wasm', 'pkg');
const destDir = join(packageRoot, 'dist', 'wasm');
const destBinary = join(destDir, 'reactor_wasm_bg.wasm');

if (!existsSync(wasmPkgDir)) {
  console.error(
    `error: ${wasmPkgDir} does not exist.\n` +
      'Build the browser binding first: `mise run build:wasm` (from the repo root).',
  );
  process.exit(1);
}

cpSync(wasmPkgDir, destDir, { recursive: true });

// `npm publish` runs `prepack` (which runs this), and a silently empty/missing
// dist/wasm here means the published tarball is missing the binary — the
// package installs fine and only fails for consumers, at their build time,
// with an opaque "Module not found" (see the copy-wasm.mjs run that produced
// exactly that, empty dist/wasm from an earlier incomplete build).
if (!existsSync(destBinary) || statSync(destBinary).size === 0) {
  console.error(`error: ${destBinary} is missing or empty after copy — refusing to leave a broken dist/wasm in place.`);
  process.exit(1);
}

console.log(`copied ${wasmPkgDir} -> ${destDir}`);
