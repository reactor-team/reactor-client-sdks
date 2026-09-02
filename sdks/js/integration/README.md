# JS SDK integration tests

Playwright driving `@reactor-team/js-sdk`'s actual public API — a real browser, the
real compiled wasm binding, a real WebRTC connection, against a real model in
production (`reactor/echo` by default). Nothing in this chain is mocked; that's the
point. Unlike `sdks/js`'s unit tests (`mise run test:js`, mocked `ReactorClient`),
this is the one place the whole path — SDK, wasm, WebRTC, coordinator, model —
actually runs end to end, so it's what catches a regression the unit suite's
fixtures agree with by construction.

## Running it

```sh
export INTEGRATION_TESTS_REACTOR_API_KEY=...   # never pass this on a command line — export it
mise run test:js:integration
```

Or directly:

```sh
npm install
npx playwright install --with-deps chromium
npx playwright test
```

A dedicated key, not the `REACTOR_API_KEY` the examples use — this one exists only
to run this suite, in CI and locally alike. `harness/vite.config.ts` mints a
session-scoped JWT from it the same way `sdks/js/examples/*/vite.config.ts` do from
theirs — the key itself never reaches the browser.

## What it tests, and how

`harness/` is not a demo — nothing in it is meant to be read by a person. It's a
small Vite app that constructs one or more `Reactor` instances and exposes them
(and a few deterministic media fixtures — solid-color canvas/audio tracks, so
pixel assertions don't depend on Chromium's fake-device pattern) on
`window.__harness`. The specs under `tests/` drive that surface directly through
`page.evaluate()` — connect, sendCommand, publish/pause/resume/unpublish a track,
upload a file, request a clip — and assert on real return values, real events, and
real pixels sampled back out of the received video track. This exercises the SDK's
public methods themselves, not a UI built on top of them.

`reactor/echo`'s own command surface (`set_effect`, `set_intensity`,
`set_overlay_image`) is what most of the assertions ride on — see
`~/dev/reactor-runtime/examples/echo/echo.py` for what it actually does with each.

## Pointing this at a local runtime instead of production

Every knob is an env var, read by both `harness/vite.config.ts` (server-side token
minting) and the harness itself (`harness/src/config.ts`):

```sh
REACTOR_LOCAL=true REACTOR_API_URL=http://localhost:8080 REACTOR_MODEL_NAME=my-model \
  npx playwright test
```

`REACTOR_LOCAL=true` skips token minting entirely (`ReactorOptions.jwt`'s own docs:
"omit for an unauthenticated local runtime") — which is also this suite's one
permanent gap in local mode: `local: true` takes a different, unauthenticated code
path, so nothing here can exercise JWT minting, `connect(jwt)`, or auth-failure
error paths (`UnauthorizedError`, ...) against a local runtime. If this suite ever
moves its bulk (effects, tracks, commands, clips) to a local runtime for
per-PR speed, keep a small auth-specific slice pointed at production — there's no
other way to reach that code path.

## Known, currently-failing issue (external, not this repo)

`reactor/echo` in production carries per-session model state (`effect`,
`intensity`, `_overlay`) across sessions that should be isolated from each other.
Reproduced by uploading a solid-color overlay image at full strength in one
session, then observing a brand-new session — different session id, different
published color — come back showing that same overlay, with no client-side way to
clear it (`echo.py` has no "clear overlay" command). `echo.py`'s own
`session_started` hook does reset this state correctly, so the leak is in the
coordinator/runtime's session-to-worker lifecycle (private infra, outside both this
repo and `reactor-runtime`) rather than in the model or the SDK.

Once a shared worker gets into this state, every session that lands on it fails
`tracks-and-upload.spec.ts`'s effect/overlay assertions regardless of what that
session itself did. Left as real (failing) assertions rather than skipped, so this
stays visible in CI until it's fixed upstream — a flaky-looking failure there is
this, not a new regression, until proven otherwise.
