/**
 * Lazy, idempotent access to the `reactor-wasm` module.
 *
 * Nothing imports `reactor_wasm.js` at module scope: pulling in wasm on
 * import would make constructing a `Reactor` that never connects pay for a
 * network fetch and a WebAssembly compile it never needed. The module is
 * fetched and instantiated on first use and cached module-wide, so every
 * `Reactor` in a page shares one `init()` — a second `new Reactor(...)`, or a
 * reconnect, never re-runs it.
 */
import type { ReactorWasmModule } from './reactor-wasm.types';

let modulePromise: Promise<ReactorWasmModule> | null = null;

/**
 * `wasm-pack --target web` emits a plain ES module next to a `.wasm` file and
 * does its own `fetch()` + `instantiateStreaming()` — this loader only needs
 * to reach that module and call its default export once. tsup bundles this
 * whole package into one flat `dist/index.{js,cjs}`, so at runtime this file
 * lives at the *root* of `dist/`, not under `dist/internal/` — the path is
 * relative to that, to line up with where `scripts/copy-wasm.mjs` copies
 * `crates/reactor-wasm/pkg` (`dist/wasm/`).
 *
 * Known limitation: this is a plain relative specifier, so it resolves
 * against wherever *this* module ends up at runtime. That's correct for
 * `node_modules/@reactor-team/js-sdk/dist/index.js` and for a dev server
 * (Vite, webpack-dev-server, ...) that serves packages as individual
 * modules — including this SDK's own `demo/`. A consumer whose production
 * bundler inlines this package's code into one file changes that base path
 * and would need to keep `dist/wasm/` reachable at the equivalent location
 * relative to their own bundle, or this loader revisited to resolve against
 * `import.meta.url` instead.
 */
const WASM_MODULE_SPECIFIER = './wasm/reactor_wasm.js';

export function loadReactorWasm(): Promise<ReactorWasmModule> {
  if (!modulePromise) {
    modulePromise = importWasmModule().catch((cause) => {
      // Don't cache a failed load — a transient fetch failure shouldn't
      // permanently poison every later `Reactor` in the page.
      modulePromise = null;
      throw new Error(
        'reactor-wasm failed to load. Run `mise run build:wasm` (or, from a ' +
          'published install, reinstall the package) and try again.',
        { cause },
      );
    });
  }
  return modulePromise;
}

async function importWasmModule(): Promise<ReactorWasmModule> {
  const module = (await import(
    /* @vite-ignore */ WASM_MODULE_SPECIFIER
  )) as ReactorWasmModule;

  await module.default();
  return module;
}
