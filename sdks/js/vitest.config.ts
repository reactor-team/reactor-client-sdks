import { fileURLToPath } from 'node:url';
import { configDefaults, defineConfig } from 'vitest/config';

// `integration-tests/` is its own Playwright project (`@playwright/test`, run
// via `mise run test:js:integration-tests`), not part of this package's
// vitest suite — without this, vitest's default glob picks up its *.spec.ts
// files too and fails trying to run Playwright's `test()` under vitest's own
// test runner.
export default defineConfig({
  resolve: {
    // src/internal/wasm.ts imports the built binding by its dist-relative
    // path, which doesn't exist in a source checkout — a stub stands in so
    // the loader itself can be unit-tested.
    alias: { './wasm/reactor_wasm.js': fileURLToPath(new URL('src/internal/reactor-wasm.stub.ts', import.meta.url)) },
  },
  test: {
    exclude: [...configDefaults.exclude, 'integration-tests/**'],
  },
});
