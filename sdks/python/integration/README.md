# Python SDK integration tests

`reactor_sdk.Reactor`'s actual public API — real `libreactor_ffi`, real WebRTC —
against a real model in production (`reactor/echo` by default). Nothing in this
chain is mocked; that's the point. Unlike `sdks/python`'s unit tests
(`mise run test:python`, a faked `get_lib()` per the `sdk-from-ffi` skill's
"Testing" section), this is the one place the whole path — SDK, FFI, WebRTC,
coordinator, model — actually runs end to end, so it's what catches a
regression the unit suite's fixtures agree with by construction. Mirrors
`sdks/js/integration/`; see that suite's own README for the fuller design
rationale, most of which applies unchanged here.

## Running it

```sh
export INTEGRATION_TESTS_REACTOR_API_KEY=...   # never pass this on a command line — export it
mise run test:python:integration
```

Or directly — useful for iterating on one test without paying the full task's
build step each time. `reactor_sdk` talks to `libreactor_ffi` through ctypes, so
the native library has to actually be built first, from the repo root:

```sh
cargo build -p reactor-ffi --release
```

Then, from `sdks/python`:

```sh
uv run --group dev pytest integration/tests -v
```

A dedicated key, the same `INTEGRATION_TESTS_REACTOR_API_KEY` the JS suite uses —
this one exists only to run these suites, in CI and locally alike. Unlike the JS
harness, nothing here mints a JWT up front: the Python SDK exchanges an API key
for a token itself, inside `connect()` (`_auth.py`'s `fetch_jwt`, called from
`client.py`'s `_resolve_token`) — there's no browser boundary to keep the key
away from, so it's just handed to `Reactor(api_key=...)` (see `conftest.py`'s
`new_reactor`).

## What it tests, and how

No `harness/` — nothing to serve. Each spec under `tests/` drives
`reactor_sdk.Reactor` directly, the same way `sdks/python/examples/*.py` do by
hand: connect, `send_command`, publish/pause/resume/unpublish a track, upload a
file, request a clip — and asserts on real return values, real events, and real
pixels read back out of `on_frame`. `conftest.py`'s `solid_rgb_frame` /
`solid_rgb_png` are deterministic, synthetic media (not a webcam/mic) so pixel
assertions on `reactor/echo`'s effects are exact rather than dependent on
whatever a fake device happens to generate — the same reasoning as the JS
suite's synthetic canvas/audio-tone fixtures.

`reactor/echo`'s own command surface (`set_effect`, `set_intensity`,
`set_overlay_image`) is what most of the pixel assertions ride on — see
`~/dev/reactor-models/echo/echo_model.py` for what it actually does with each.
Two things this suite covers that the JS one structurally can't:
`test_tracks_and_frames.py`'s frame-trailer assertions (native-only — a browser
gets a `MediaStreamTrack` with no per-frame hook) and `send_command`'s typed
data replies (example 08's territory, not yet a browser-side equivalent).

`test_concurrency_and_races.py` is not parity with anything in `sdks/js/
integration/` — it exists because the JS SDK needed `AwaitQueue` (PR #137) to
survive connect/disconnect racing in-flight calls, and PR #136 fixed the
equivalent race one layer down, in `reactor-core`/`reactor-wasm`/`reactor-ffi`,
which is the layer Python's `Reactor` calls into directly. It did find one gap
of its own, in `close()`'s teardown rather than in connect/disconnect — fixed
in #139, see "Known, currently-failing issue" below for what's still open.

## Pointing this at a local runtime instead of production

Every knob is an env var, read by `conftest.py` — the same names
`sdks/js/integration/harness/src/config.ts` uses:

```sh
REACTOR_LOCAL=true REACTOR_API_URL=http://localhost:8080 REACTOR_MODEL_NAME=my-model \
  uv run --group dev pytest integration/tests -v
```

`REACTOR_LOCAL=true` skips auth entirely (`Reactor(local=True)`'s own docs:
"relaxes TLS verification and skips auth") — the same permanent gap the JS
suite documents: nothing here can exercise the API-key-to-JWT exchange or an
auth-failure error path (`UnauthorizedError`, ...) against a local runtime.

## Known, currently-failing issue

Found by running this suite against prod for real. Two other issues this
suite found along the way are already fixed, not just documented — session
adoption needing a shared token (see `test_multi_connection.py`'s module
docstring) and `Reactor.close()` abandoning in-flight operations (fixed in
#139; see `test_concurrency_and_races.py`'s module docstring). This one is
external and left as a real, failing assertion rather than skipped, so it
stays visible until it's fixed upstream — matching the JS suite's own
convention.

**`reactor/echo` session-state leak (external, not this repo).** See
`sdks/js/integration/README.md`'s own section on this — production has
carried per-session model state across sessions that should be isolated. If a
shared prod worker is in that state, `test_tracks_and_frames.py`'s and
`test_upload_and_conditioning.py`'s effect/overlay assertions can fail
regardless of what this suite itself did. A flaky-looking failure there may
be this, not a new regression, until proven otherwise.
