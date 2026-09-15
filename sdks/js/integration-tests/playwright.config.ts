import { defineConfig } from '@playwright/test';

// Every scenario here talks to a real model over a real WebRTC connection
// (production `reactor/echo` by default — see harness/vite.config.ts). That
// makes this suite slower and less deterministic than a unit test on
// purpose: it is the one place the whole path — browser, wasm, WebRTC,
// coordinator, model — actually runs.
//
// No `retries` here (defaults to 0): a transient failure — a session-creation
// rate limit, the shared capacity pool, a session/SDP poll timing out — is
// retried by mise's own test:js:integration-tests task (see mise.toml),
// which wraps just the `npx playwright test` invocation in scripts/retry.sh
// — the same mechanism for every SDK, not a Playwright-specific config
// scoped to just this one. See the sdk-from-ffi skill's "Integration-test
// retries" section before adding a `retries` value back — a genuine
// regression still fails deterministically on every attempt either way.
export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  workers: 1, // shared model capacity; sessions are cheap but not free
  reporter: process.env.CI ? [['github'], ['list']] : 'list',
  use: {
    baseURL: 'http://localhost:4310',
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
  },
  webServer: {
    command: 'npx vite --config harness/vite.config.ts --port 4310 --strictPort',
    url: 'http://localhost:4310',
    reuseExistingServer: !process.env.CI,
    stdout: 'pipe',
    stderr: 'pipe',
    timeout: 30_000,
  },
});
