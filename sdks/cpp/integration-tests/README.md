# C++ SDK integration tests

Catch2 tests driving `reactor::Reactor`'s actual public API — real FFI, real
WebRTC, against a real model in production (`reactor/echo` by default).
Nothing in this suite is mocked; that's the point. Unlike `sdks/cpp`'s unit
tests (`mise run test:cpp`, a faked `Ffi` table), this is the one place the
whole path — SDK, FFI, WebRTC, coordinator, model — actually runs end to end,
so it's what catches a regression the unit suite's fakes agree with by
construction.

It mirrors `sdks/python/integration-tests/` and `sdks/js/integration-tests/`
test for test where the object models line up, adapted to a synchronous,
future-based client — see each test file's own header comment for what
carried over and what didn't.

## Running it

```sh
export INTEGRATION_TESTS_REACTOR_API_KEY=...   # never pass this on a command line — export it
mise run test:cpp:integration-tests
```

A dedicated key, shared with `js-integration-tests`/`python-integration-tests`
— this one exists only to run these three suites, in CI and locally alike.

Or directly, from the repo root — useful for iterating on one file without
paying the full sequence each time:

```sh
cargo build -p reactor-ffi --release
cmake -S sdks/cpp -B sdks/cpp/build-integration-tests -G Ninja \
  -DREACTOR_SDK_BUILD_TESTS=OFF -DREACTOR_SDK_BUILD_EXAMPLES=OFF \
  -DREACTOR_SDK_BUILD_INTEGRATION_TESTS=ON
cmake --build sdks/cpp/build-integration-tests
sdks/cpp/build-integration-tests/integration-tests/reactor_sdk_integration_tests "[a Catch2 test-name filter]"
```

A separate build directory and its own opt-in CMake option
(`REACTOR_SDK_BUILD_INTEGRATION_TESTS`, off by default), not another file
under `tests/`: this suite needs a real key and costs real session time, on
the order of minutes rather than seconds — the same reason
`test:python:integration-tests` and `test:js:integration-tests` are not part
of `test:python`/`test:js`.

## What it tests, and how

Six files, one per concern, each named after the Python/JS file it mirrors:

| File | Mirrors | Covers |
|---|---|---|
| `lifecycle_and_commands_test.cpp` | `test_lifecycle_and_commands.py` | connect/status/disconnect, commands, `request_schema` |
| `tracks_and_frames_test.cpp` | `test_tracks_and_frames.py` | publish, push, receive, pause/resume, the frame trailer |
| `upload_and_conditioning_test.cpp` | `test_upload_and_conditioning.py` | `upload_file`, `upload_bytes`, a command that consumes a `FileRef` |
| `multi_connection_test.cpp` | `test_multi_connection.py` | session adoption, teardown asymmetry between creator and joiner |
| `recording_test.cpp` | `test_recording.py` | `request_clip`/`request_recording`, `Clip::download` |
| `concurrency_and_races_test.cpp` | `test_concurrency_and_races.py` | concurrent commands, abandoned futures, teardown while a call is in flight |
| `refusals_and_edge_cases_test.cpp` | *(none — new)* | the "Refuse; do not fail quietly" table and a few state-invariant races, probed directly against the real backend rather than the unit suite's fake FFI |

`fixtures.hpp`/`fixtures.cpp` (mirroring `conftest.py`/`harness/`) provide:

- **Session-creation pacing.** `reactor/echo`'s `sessions_per_minute` quota is
  enforced per API key across whatever suite is running against it, not per
  test or per client — confirmed while building the Python suite this one
  mirrors, and true here for the same reason. `paced_connect()` is a
  process-wide gate spacing every session-creating `connect()` at least 700ms
  apart; every test routes through it rather than calling `connect()`
  directly. `RateLimitedError` also gets one retry as a second line of
  defense — the pacing gate is the primary one.
- **A shared token for session adoption.** `mint_jwt()` calls
  `reactor_fetch_jwt` directly — reaching past the public header, the same way
  the unit suite already reaches past it for teardown tests — because the
  public C++ API has no other way to get at a token it did not mint for you,
  and adoption needs the *same* token handed to two clients.
- **Synthetic media fixtures.** `solid_bgra_frame`/`solid_rgb_png` build
  deterministic pixels rather than reading a webcam, matching the JS harness's
  and Python conftest's own synthetic fixtures.

`reactor/echo` only emits `main_video` once it has read a `webcam` frame, so
every test that needs output publishes `webcam` and pushes into it via a small
background-thread frame pump (there is no event loop here to interleave frame
production with the rest of a test the way `asyncio`/`Playwright` do it).

## Pointing this at a local runtime instead of production

Every knob is an env var, read the same way
`sdks/python/integration-tests/conftest.py` and
`sdks/js/integration-tests/harness/src/config.ts` read theirs:

```sh
REACTOR_LOCAL=true REACTOR_API_URL=http://localhost:8080 REACTOR_MODEL_NAME=my-model \
  mise run test:cpp:integration-tests
```

`REACTOR_LOCAL=true` skips token minting entirely — which is also this
suite's one permanent gap in local mode: local mode takes a different,
unauthenticated code path, so nothing here can exercise JWT minting or
auth-failure error paths (`UnauthorizedError`, ...) against a local runtime.
