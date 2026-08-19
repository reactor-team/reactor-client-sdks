#!/usr/bin/env node
// Copies the built `reactor-wasm` browser binding into `dist/wasm`, next to
// the rest of the built package, so `import("../wasm/reactor_wasm.js")` in
// src/internal/wasm.ts resolves from an npm install with no separate build
// step on the consumer's side. Run as part of `npm run build`, after tsup.
import { cpSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const packageRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const wasmPkgDir = join(packageRoot, "..", "..", "crates", "reactor-wasm", "pkg");
const destDir = join(packageRoot, "dist", "wasm");

if (!existsSync(wasmPkgDir)) {
  console.error(
    `error: ${wasmPkgDir} does not exist.\n` +
      "Build the browser binding first: `mise run build:wasm` (from the repo root).",
  );
  process.exit(1);
}

cpSync(wasmPkgDir, destDir, { recursive: true });
console.log(`copied ${wasmPkgDir} -> ${destDir}`);
