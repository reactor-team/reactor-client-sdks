# C++ SDK endurance/leak tests

REA-6088's C++ follow-up. Real `reactor::Reactor` clients — real
`libreactor_ffi`, real WebRTC — against a real model in production
(`reactor/echo` by default), the same "nothing mocked" approach as
`sdks/cpp/integration-tests/` (see that suite's own README for the fuller
rationale, most of which applies unchanged here) and as
`sdks/python/endurance-tests/`, which this suite mirrors file-for-file. The
difference from integration-tests/ is what's being checked: not whether one
call behaves correctly, but whether *repeating* it for a while leaves
anything behind.

## Running it

```sh
export INTEGRATION_TESTS_REACTOR_API_KEY=...   # never pass this on a command line — export it
cargo build -p reactor-ffi --release           # from the repo root — build:ffi's own job
mise run test:cpp:endurance-tests
```

Or directly, from the repo root — useful for iterating without paying the
full configure step each time:

```sh
cargo build -p reactor-ffi --release
cmake -S sdks/cpp -B sdks/cpp/build-endurance-tests -G Ninja \
  -DREACTOR_SDK_BUILD_TESTS=OFF -DREACTOR_SDK_BUILD_EXAMPLES=OFF \
  -DREACTOR_SDK_BUILD_ENDURANCE_TESTS=ON -DREACTOR_SDK_BUILD_AUDIO=OFF
cmake --build sdks/cpp/build-endurance-tests
ENDURANCE_DURATION_SECONDS=3600 sdks/cpp/build-endurance-tests/endurance-tests/reactor_sdk_endurance_tests
```

**Manual-only for now.** Not wired into `ci.yml`, and not a dependency of
`test`/`test:cpp` — these runs are long, inherently noisier than a
correctness assertion (a growth *trend*, not a single right-or-wrong call),
and not meant to gate a PR or a release. The `Endurance tests`
`workflow_dispatch` workflow (`.github/workflows/endurance-tests.yml`) is how
this actually runs — pick `cpp` from the `sdk` input.

A separate build directory and its own opt-in CMake option
(`REACTOR_SDK_BUILD_ENDURANCE_TESTS`, off by default), not another file under
`tests/` or `integration-tests/`: this suite needs a real key and costs real
session time, on the order of minutes to hours — same reasoning as
`REACTOR_SDK_BUILD_INTEGRATION_TESTS`.

## Duration, not iteration count

Every scenario loops against a shared wall-clock deadline —
`ENDURANCE_DURATION_SECONDS` (default `300`, five minutes) — rather than a
fixed number of cycles. Same reasoning as the Python suite's identical knob:
one number, shared by every scenario, handles a quick manual sanity check
today and an hours-long leak hunt (or a future scheduled run) without any
code changes.

## What each scenario covers

- **`test_lifecycle_churn.cpp`** — a brand-new `reactor::Reactor` per cycle:
  connect, publish, push frames, send a command, disconnect, destroy. This is
  the one that exercises the *whole* native-handle lifecycle — construction
  through the FFI, then destruction releasing it — not just per-operation
  paths.
- **`test_session_churn.cpp`** — one long-lived session, many
  publish/push_frame/command/unpublish cycles against it. No reconnect
  overhead, so it packs far more iterations into the same window — the
  scenario for per-operation leaks (frame buffers, `Track` objects) a coarser
  connect/destroy cycle wouldn't surface as clearly.

## Reading the printed table

Every scenario ends by printing one row per cycle:

| column            | what it is                                                                                          | what "bad" looks like                          |
| ----------------- | ---------------------------------------------------------------------------------------------------- | ----------------------------------------------- |
| `cycle`           | which iteration of the loop this row is (0, 1, 2, ...)                                                 | —                                                |
| `elapsed_s`       | seconds since this test started                                                                        | —                                                |
| `ram_mb`          | physical RAM the whole process is using right now — not just the SDK, everything in this one process   | keeps climbing, never plateaus                  |
| `cpu_s_per_cycle` | CPU time *this one cycle* burned (not a running total)                                                | keeps getting bigger cycle to cycle             |
| `cpu_percent`     | % of one CPU core busy since the previous row — can read over 100% if more than one native thread is genuinely busy at once, that's normal | keeps getting bigger cycle to cycle |
| `num_threads`     | OS-level threads this process currently has (mostly the native Rust runtime's)                         | keeps climbing (some early wobble is normal)    |
| `num_fds`         | open file descriptors — sockets, mainly, since every WebRTC connection needs some                      | keeps climbing                                  |

This table (with the same wording) also lives as a comment directly above
`ResourceSampler::print_report()` in `helpers.hpp` — keep both in sync if
either changes.

## The signals, and what "leak" means for each

- **Process RSS, CPU time/%, threads, fds** — read from `/proc/self/{status,
  stat,fd}` (see `helpers.cpp`'s own comment on why: this SDK links nothing
  equivalent to Python's `psutil`, so this suite reads `/proc` directly,
  which is fine given it only ever runs on Linux — see "A known, deliberate
  scope gap" below). RSS/CPU-time/CPU-percent are checked exactly the way
  `sdks/python/endurance-tests/README.md` explains for the same three
  signals: a trend (mean of the run's last third vs. its first third, after
  dropping a 20% warm-up). `num_threads` and `num_fds` are **both** checked
  as a median-backed trend rather than an exact "never past its starting
  value" count — unlike the Python suite, where `num_fds` proved exact in a
  real run. Two real runs of this binding each caught `num_fds` (and
  `num_threads` alongside it) take one isolated step on a single cycle —
  once between cycle 0 and cycle 1 (some process-wide resource finishing its
  warm-up on this process's very first cycle) and separately on a run's very
  last cycle (that sample catching the *previous* cycle's own socket
  teardown still in flight) — neither a per-cycle leak, which would keep
  climbing sample over sample rather than step once. An exact check has no
  tolerance for either; the trend check does, without hiding a real,
  sustained leak. See `test_lifecycle_churn.cpp`'s own comment on its
  `num_fds` check for the full account.
- **The receive path** — both scenarios also subscribe to `main_video` via
  `on_frame`, not just publish/push frames: `test_lifecycle_churn.cpp`
  registers once per client and lets its destruction tear the subscription
  down (`Subscription`'s own RAII); `test_session_churn.cpp` registers and
  explicitly `remove()`s it every single iteration on the same long-lived
  session — the repeated subscribe/unsubscribe cycle that a one-shot
  registration would never exercise.

## A known, deliberate scope gap

Unlike `sdks/python/endurance-tests/`, this suite has **no** equivalent of
`live_clients`/`orphaned_callbacks` — the Python binding's own
`_LIVE_CLIENTS`/`_ORPHANED_CALLBACKS` registries, kept because a garbage-
collected language can leave a native handle referenced (and therefore alive)
for an indeterminate time after a caller is "done" with it. This C++ binding
has no such gap: a `Reactor`/`Track`/`Subscription` is destroyed exactly when
its owner's scope ends, deterministically, by construction — there is no
analogous "did the destructor actually run yet" question for this suite to
answer, and reaching into `detail::ClientImpl` to fabricate one would be
testing the language, not the SDK. What this suite *can't* catch that the
Python one can: a leak entirely inside `reactor-ffi`/`reactor-core` (Rust)
that a C++ caller's own correct RAII usage would never surface — the same
"native-layer leak, out of scope for a binding-level suite" gap the Python
README's own final section already documents, now doubly true here since
this binding's language guarantees rule out the *other* kind of leak by
construction.

`test_session_churn.py`'s own `Reactor._pending_completions`/
`Track._adapters` exact-emptiness checks (reaching into Python-private
internals) have no C++ equivalent for the same reason — nothing here is
reachable to check, and nothing here needs to be: a `std::future` either
gets `.get()`'d (as every call in this suite does) or its destructor blocks
until the operation completes, so there is no "still pending after the loop
moved on" state possible in the first place.

## Reused from `integration-tests/`

`helpers.cpp`/`test_*.cpp` link `../integration-tests/fixtures.cpp` directly
(see this directory's `CMakeLists.txt`) rather than re-deriving connection
setup, pacing, or media fixtures — `new_reactor`, `paced_connect`,
`solid_bgra_frame`, `ReactorFactory`, and `ConnectedReactor` all come from
there unchanged. See that suite's own README for what those do and why.
