import { configDefaults, defineConfig } from 'vitest/config';

// `integration-tests/` is its own Playwright project (`@playwright/test`, run
// via `mise run test:js:integration-tests`), not part of this package's
// vitest suite — without this, vitest's default glob picks up its *.spec.ts
// files too and fails trying to run Playwright's `test()` under vitest's own
// test runner.
export default defineConfig({
  test: {
    exclude: [...configDefaults.exclude, 'integration-tests/**'],
  },
});
