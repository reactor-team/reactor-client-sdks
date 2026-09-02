import { defineConfig } from '@playwright/test';

// Every scenario here talks to a real model over a real WebRTC connection
// (production `reactor/echo` by default — see harness/vite.config.ts). That
// makes this suite slower and less deterministic than a unit test on
// purpose: it is the one place the whole path — browser, wasm, WebRTC,
// coordinator, model — actually runs. A single retry absorbs an occasional
// real network hiccup without hiding a genuine regression, which would fail
// on the retry too.
export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  retries: process.env.CI ? 1 : 0,
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
