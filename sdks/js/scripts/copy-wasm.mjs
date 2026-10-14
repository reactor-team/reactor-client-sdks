#!/usr/bin/env node
// Copies the built `reactor-wasm` browser binding into `dist/wasm`, next to
// the rest of the built package, so `import("./wasm/reactor_wasm.js")` in
// src/internal/wasm.ts resolves from an npm install with no separate build
// step on the consumer's side. Run as part of `npm run build`, after tsup.
//
// The `.wasm` binary itself is inlined into that module as base64 rather
// than shipped as a sibling file. wasm-bindgen's glue locates the binary with
// `new URL('reactor_wasm_bg.wasm', import.meta.url)`, which only works if
// the consumer's bundler recognizes that expression and relocates the file
// alongside the module — and not every one does in every mode (Vite's
// dev-server dependency optimizer before Vite 8 moves the module into
// `.vite/deps` and leaves the binary behind). An inlined binary travels with
// the module wherever a bundler puts it; the only thing the consumer's
// bundler still has to do is follow a relative dynamic `import()`, which every
// one does. The module is still only fetched on first `connect()`, so the
// binary's size is never paid on import.
import { cpSync, existsSync, readFileSync, realpathSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { Buffer } from 'node:buffer';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/** How wasm-bindgen's `--target web` glue locates the binary when its init
 *  function is called with no argument — the one expression `inlineWasm`
 *  replaces. */
export const WASM_URL_EXPRESSION = "new URL('reactor_wasm_bg.wasm', import.meta.url)";

/** Returns `glueSource` with `WASM_URL_EXPRESSION` replaced by the binary's
 *  own bytes, decoded from base64 at init time. Throws unless the expression
 *  appears exactly once: a wasm-bindgen upgrade that changes how the glue
 *  locates its binary has to fail the build, not ship a module that still
 *  fetches a file no longer in the package. */
export function inlineWasm(glueSource, wasmBytes) {
  const occurrences = glueSource.split(WASM_URL_EXPRESSION).length - 1;

  if (occurrences !== 1) {
    throw new Error(
      `expected exactly one \`${WASM_URL_EXPRESSION}\` in the wasm-bindgen glue, found ${occurrences} — ` +
        'its output changed; update scripts/copy-wasm.mjs to match.',
    );
  }

  // `Uint8Array.fromBase64` decodes natively where it exists; the loop is
  // the fallback for browsers without it. Both are a few milliseconds for
  // the whole binary — `Uint8Array.from(atob(...), fn)` is several times
  // slower, through one callback per byte.
  const decoder = `

// Inlined by @reactor-team/js-sdk's scripts/copy-wasm.mjs: the binary this
// module would otherwise fetch from a sibling reactor_wasm_bg.wasm file.
function __reactorWasmBytes() {
    const base64 = ${JSON.stringify(Buffer.from(wasmBytes).toString('base64'))};
    if (typeof Uint8Array.fromBase64 === 'function') return Uint8Array.fromBase64(base64);
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
}
`;

  return glueSource.replace(WASM_URL_EXPRESSION, () => '__reactorWasmBytes()') + decoder;
}

/** Copies `wasmPkgDir` into `destDir` with the binary inlined into the glue
 *  module, refusing to leave `destDir` looking valid when it isn't. Exported
 *  so a test can drive it against temp directories instead of the real
 *  `crates/reactor-wasm/pkg`. */
export function copyWasm(wasmPkgDir, destDir) {
  const srcBinary = join(wasmPkgDir, 'reactor_wasm_bg.wasm');
  const srcGlue = join(wasmPkgDir, 'reactor_wasm.js');
  const destBinary = join(destDir, 'reactor_wasm_bg.wasm');
  const destGlue = join(destDir, 'reactor_wasm.js');

  if (!existsSync(wasmPkgDir)) {
    throw new Error(
      `${wasmPkgDir} does not exist. Build the browser binding first: \`mise run build:wasm\` (from the repo root).`,
    );
  }

  // Checked before the copy, not just after: cpSync only adds/overwrites
  // files that exist in the source, so a missing/empty source binary would
  // otherwise leave a stale, already-present dest binary looking valid
  // post-copy.
  for (const file of [srcBinary, srcGlue]) {
    if (!existsSync(file) || statSync(file).size === 0) {
      throw new Error(
        `${file} is missing or empty. Build the browser binding first: \`mise run build:wasm\` (from the repo root).`,
      );
    }
  }

  // Inlined before anything is written to `destDir`, so a glue whose shape
  // changed fails the build without touching a previous good copy.
  const wasmBytes = readFileSync(srcBinary);
  const inlinedGlue = inlineWasm(readFileSync(srcGlue, 'utf8'), wasmBytes);

  cpSync(wasmPkgDir, destDir, { recursive: true });
  writeFileSync(destGlue, inlinedGlue);
  // Nothing references the separate binary anymore; shipping it too would
  // only double the package's size.
  rmSync(destBinary, { force: true });

  // `wasm-pack build` writes a `.gitignore` containing `*` into its pkg/
  // output (it's build output, so that's correct for git). Copied verbatim
  // into dist/wasm, that `.gitignore` poisons npm-packlist — nested ignore
  // files apply even inside a directory included via package.json's `files`
  // array — so `npm pack` (and pnpm's `file:` install, which packs the same
  // way) silently drops every wasm artifact from the tarball while leaving
  // them present on disk, which is exactly what the post-copy check below
  // can't catch.
  rmSync(join(destDir, '.gitignore'), { force: true });

  // `npm publish` runs `prepack` (which runs this), and a silently broken
  // dist/wasm here means the published tarball is missing the binding — the
  // package installs fine and only fails for consumers, at their build time,
  // with an opaque "Module not found" (see the copy-wasm.mjs run that produced
  // exactly that, empty dist/wasm from an earlier incomplete build).
  const written = existsSync(destGlue) ? readFileSync(destGlue, 'utf8') : '';

  if (written.includes(WASM_URL_EXPRESSION) || !written.includes('__reactorWasmBytes()')) {
    throw new Error(`${destGlue} is missing or not inlined after copy — refusing to leave a broken dist/wasm in place.`);
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
