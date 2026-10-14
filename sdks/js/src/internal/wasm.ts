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
 * `wasm-pack --target web` emits a plain ES module that instantiates the
 * binding — this loader only needs to reach that module and call its default
 * export once. tsup bundles this whole package into one flat
 * `dist/index.{js,cjs}`, so at runtime this file lives at the *root* of
 * `dist/`, not under `dist/internal/` — the path is relative to that, to line
 * up with where `scripts/copy-wasm.mjs` copies `crates/reactor-wasm/pkg`
 * (`dist/wasm/`).
 *
 * That module carries the `.wasm` binary inlined (see copy-wasm.mjs), so a
 * consumer's bundler only has to follow this one relative `import()` — which
 * every bundler does, and turns into a separate chunk fetched on first use.
 * Nothing else has to stay reachable at a fixed location next to it.
 *
 * The specifier is inlined directly in the `import()` call below, not
 * hoisted to a `const`, and not marked `@vite-ignore` — bundlers only resolve
 * a literal argument statically. Routed through an intermediate binding,
 * webpack can't trace it back to a literal and throws "Critical dependency:
 * the request of a dependency is an expression" at runtime; marked ignored,
 * Vite leaves it as a bare relative path that breaks as soon as Vite moves
 * this file (its dependency optimizer, or a production build).
 */

export function loadReactorWasm(): Promise<ReactorWasmModule> {
  if (!modulePromise) {
    modulePromise = importWasmModule().catch((cause) => {
      // Don't cache a failed load — a transient fetch failure shouldn't
      // permanently poison every later `Reactor` in the page.
      modulePromise = null;
      throw new Error(`reactor-wasm failed to load: ${describeCause(cause)}`, { cause });
    });
  }
  return modulePromise;
}

async function importWasmModule(): Promise<ReactorWasmModule> {
  // @ts-expect-error — no declaration file to resolve at typecheck time; the
  // module only exists after scripts/copy-wasm.mjs copies it into dist/wasm,
  // post-build. Cast to ReactorWasmModule below instead.
  const module = (await import('./wasm/reactor_wasm.js')) as ReactorWasmModule;

  await module.default();
  return module;
}

/** The underlying failure, named in the message itself rather than only in
 *  `cause` — plenty of error reporting (and `String(error)`) never looks at
 *  `cause`, and "failed to load" alone gives nothing to act on. */
function describeCause(cause: unknown): string {
  if (cause instanceof Error) {
    return cause.message ? `${cause.name}: ${cause.message}` : cause.name;
  }
  return String(cause);
}
