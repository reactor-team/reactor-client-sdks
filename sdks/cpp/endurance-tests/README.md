# C++ SDK endurance/leak tests

Real `reactor::Reactor` clients — real `libreactor_ffi`, real WebRTC —
against a real model in production (`reactor/echo` by default), the same
"nothing mocked" approach as `sdks/cpp/integration-tests/` (see that suite's
own README for the fuller rationale, most of which applies unchanged here)
and as `sdks/python/endurance-tests/`, which this suite mirrors file-for-file
where this binding has an equivalent (see "A known, deliberate scope gap"
below for where it doesn't). The difference from integration-tests/ is what's
being checked: not whether one call behaves correctly, but whether
*repeating* it for a while leaves anything behind.

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
and not meant to gate a PR or a release. It *is* wired into its own
`endurance-tests.yml` GitHub Actions workflow, run by hand from the Actions
tab (`workflow_dispatch`, pick `cpp` from the `sdk` input) — see "Live
output, reports, and artifacts" below for what that run leaves behind.

By default this prints a compact status block every 30s instead of a row per
cycle — set `ENDURANCE_VERBOSE=1` for the old detailed per-cycle table
instead. See the next sections.

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
- **`test_publish_churn.cpp`** — one long-lived session, many publish/
  unpublish cycles on a single sendonly slot, with no frames and no command
  in between. Isolates the publish/unpublish path itself from session-churn's
  broader mix, so a leak specific to that pair reads as its own signal
  instead of being folded into a trend several different operations are
  contributing to.
- **`test_pause_resume_churn.cpp`** — one long-lived session, many pause/
  resume cycles on a recvonly track. Same isolation reasoning as
  publish-churn, applied to `Track::pause()`/`resume()` instead. This is the
  scenario whose first real CI run (in the Python suite, which this mirrors)
  found a genuine, linear, no-plateau RSS climb that publish-churn — same
  run, same process — did not show at all.
- **`test_video_publish_steady.cpp`** / **`test_audio_publish_steady.cpp`** —
  publish once (video or audio) and hold it, continuously streaming for the
  whole run — no pause, no unpublish, no reconnect. The opposite shape from
  every scenario above: those are all *churn* (repeatedly doing and undoing
  something to surface a leak in that operation); these two are steady-state,
  the shape a real long call actually takes, so a leak tied to elapsed
  streaming time or frame/chunk count rather than to churn count still gets
  caught. Kept as two separate scenarios, not one parameterized over both —
  audio and video share nothing below `push_frame()` (separate adapters,
  separate encoder/decoder threads in the native runtime), so a leak in one
  is not evidence about the other.

**Adding a new scenario**: write a loop that does the one thing you want to
isolate, then call `trends::standard_resource_metrics(sampler.samples())` for
the RSS/CPU/thread/fd checks every scenario shares, and
`report::finish_and_check(...)` at the end for the report-writing and
raise-on-`FAIL` boilerplate — see any `test_*.cpp` file for the shape.
Neither call needs its own new scenario-specific invariant (a scenario that
has one, like publish-churn's `track.published()` check, still checks that
itself, in its own loop) — they only exist to keep the generic resource
accounting from being copied into every new file.

## Live output, reports, and artifacts

A multi-hour run printing one row per cycle (the original behavior) makes a
GitHub Actions log unreadable long before it's useful — so what prints live,
what gets kept for later, and what summarizes the result are now three
separate things, the same split as the Python suite:

- **Live output (default): a compact status block, not a row per cycle.**
  Reprinted roughly every 30s (`ENDURANCE_LIVE_INTERVAL_SECONDS`, default
  `30`):

  ```text
  ──────────────────────────────────────────────
  🚀 Endurance Test — session-churn
  ──────────────────────────────────────────────

  Duration       2h 00m
  Elapsed        1h 23m
  Progress       69%

  Iterations     1,842

  Resources
    RSS           284 MB → 291 MB   (+7 MB)
    CPU           avg 18.4%
    Threads       14 → 14
    File desc.    23 → 23

  Status         🟢 Healthy
  ──────────────────────────────────────────────
  ```

  "Healthy" here only means no errors have surfaced yet during the run — the
  pass/fail *verdict* against each metric's threshold is only known once the
  run ends (see below).

- **`ENDURANCE_VERBOSE=1`: the detailed per-cycle table, live.** The debug
  path — for a short manual run where you want to watch every cycle as it
  happens. See "Reading the per-cycle table" below for what the columns
  mean.

- **Every scenario's full sample history, always, regardless of the above:**
  written to `sdks/cpp/endurance-results/` when the scenario ends (pass,
  fail, *or* error), three files per scenario:

  ```text
  endurance-results/
    session-churn.json
    session-churn-report.md
    session-churn-summary.txt
    lifecycle-churn.json
    lifecycle-churn-report.md
    lifecycle-churn-summary.txt
    publish-churn.json
    publish-churn-report.md
    publish-churn-summary.txt
    pause-resume-churn.json
    pause-resume-churn-report.md
    pause-resume-churn-summary.txt
    video-publish-steady.json
    video-publish-steady-report.md
    video-publish-steady-summary.txt
    audio-publish-steady.json
    audio-publish-steady-report.md
    audio-publish-steady-summary.txt
  ```

  All three come from the same in-memory result (`report.hpp`'s
  `RunResult`), so `.md` and `.txt` can never say something different from
  what's in the `.json` — there's exactly one place the numbers are
  computed.

- **The GitHub Actions workflow publishes `*-report.md` to the Job
  Summary** and uploads all of `endurance-results/` as the
  `endurance-test-results-cpp` artifact — both steps run with `if: always()`.

- **A run's status is one of three, not just pass/fail** — `PASS` (every
  metric stayed within its threshold), `FAIL` (a metric's threshold check in
  `trends.cpp` failed), or `ERROR` (the run never got far enough to evaluate
  a metric at all: an unhandled exception, a live-service error, an
  `ENDURANCE_DURATION_SECONDS` too short to collect enough samples). The
  report's conclusion is written differently for each — an `ERROR`
  explicitly says it isn't a leak verdict, since no metric ran to completion
  to judge.

## Reading the Results table

Each `*-report.md`/`-summary.txt`'s "Results" table has five columns:
`Start | Mid | End | Δ (Mid→End) | Status`. `Start`/`Mid`/`End` are the mean
(or median — see `assert_no_sustained_growth`) of the first, middle, and
last third of the run, after dropping warm-up — not raw first/last samples.

`Start`→`End` alone can look like growth on a perfectly healthy run: a
buffer or connection pool reaching its steady-state size shows up as a
one-time step early on, which moves `End` up relative to `Start` without
being a leak. The pass/fail verdict is actually decided by `Mid`→`End` (the
`Δ (Mid→End)` column) — did it keep climbing in the back half, or did it
plateau — which is the same comparison `assert_no_sustained_growth` makes
internally (see its own doc-comment in `trends.hpp`). Reading `Δ (Mid→End)`
≈ 0 is what "no leak" looks like in this table, even when `Start`→`End`
shows a real jump.

## Reading the per-cycle table

Under `ENDURANCE_VERBOSE=1`, every cycle is one row:

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
`ResourceSampler`'s column guide in `helpers.hpp` — keep both in sync if
either changes.

## The signals, what they mean, and the value we expect

- **Process RSS, CPU time/%, threads, fds** — read from `/proc/self/{status,
  stat,fd}` (see `helpers.cpp`'s own comment on why: this SDK links nothing
  equivalent to Python's `psutil`, so this suite reads `/proc` directly,
  which is fine given it only ever runs on Linux — see "A known, deliberate
  scope gap" below). All five (`rss`, `cpu_s_per_cycle`, `cpu_percent`,
  `num_threads`, `num_fds`) are checked as a **trend**: the run's last third
  against its *middle* third (not its first), after dropping a 20% warm-up.

  That's deliberately last-vs-*middle*, not last-vs-first: a real CI run
  showed a metric flat, then a one-time ramp to a new plateau roughly in the
  middle of the window, then flat again — a native buffer/pool growing once
  to its steady-state size, not an unbounded leak. Comparing against the
  *first* third instead means exactly where that one ramp happens to land
  decides pass/fail — the same total jump read as a comfortable pass in one
  run and a razor-thin fail in another, purely from timing. Comparing last
  to middle asks the more direct question, "is this still climbing after the
  fact", which a one-time step that's already plateaued answers "no"
  regardless of when it happened. See `assert_no_sustained_growth`'s own
  doc-comment in `trends.hpp` for the full reasoning, including the
  trade-off (a real leak still only in its early, slow-accelerating phase
  near the end of a short run could likewise read as "already flat" —
  preferred anyway over failing a run on a one-time step that already
  stopped).

  `num_threads` and `num_fds` are **both** checked with the median-backed
  variant of that same trend, rather than an exact "never past its starting
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
- **The receive path** — `test_lifecycle_churn.cpp` and `test_session_churn.cpp`
  both also subscribe to `main_video` via `on_frame`, not just publish/push
  frames: lifecycle-churn registers once per client and lets its destruction
  tear the subscription down (`Subscription`'s own RAII); session-churn
  registers and explicitly `remove()`s it every single iteration on the same
  long-lived session — the repeated subscribe/unsubscribe cycle a one-shot
  registration would never exercise. `test_pause_resume_churn.cpp` exercises
  the same recvonly track's `pause()`/`resume()` pair instead.
- **`track.published()` / `track.paused()`** — publish-churn and
  pause-resume-churn each check their own operation's local, SDK-kept flag
  reads back correctly after every single cycle (see `Track::published()`/
  `Track::paused()`'s own docs: read fresh from the session, not cached) —
  an exact, per-cycle check, not a trend, since a leftover `true` even on one
  cycle out of hundreds is a real bug, not noise.

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

This binding also has no `tracemalloc` equivalent — that diagnostic is
specific to Python's own managed heap (string interning, one-time caches),
and this suite's process-wide RSS reading already covers the analogous "is
the whole process's memory footprint growing" question for a native binding
with no separate managed heap to diff.

## Reused from `integration-tests/`

`helpers.cpp`/`test_*.cpp` link `../integration-tests/fixtures.cpp` directly
(see this directory's `CMakeLists.txt`) rather than re-deriving connection
setup, pacing, or media fixtures — `new_reactor`, `paced_connect`,
`solid_bgra_frame`, `ReactorFactory`, and `ConnectedReactor` all come from
there unchanged. See that suite's own README for what those do and why.

## Module layout

Mirrors the Python suite's own split, adapted to where C++ needs the
boundary drawn (see `report.hpp`'s own top comment for why `Sample` lives
there rather than in `trends.hpp`, unlike Python's `trends.py`):

- **`report.hpp`/`.cpp`** — `Sample`, `MetricResult`, `RunResult`, the
  JSON/Markdown/text renderers, `LiveReporter`, and the `finish_run`/
  `finish_and_check` end-of-scenario boilerplate. No FFI or native-library
  dependency at all, so `../tests/report_test.cpp` (part of the SDK's normal
  fast unit suite, `mise run test:cpp`) exercises all of it with no live
  service and no built `libreactor_ffi` beyond what linking the SDK's own
  archive already needs.
- **`trends.hpp`/`.cpp`** — `cpu_deltas`, `assert_no_sustained_growth`,
  `assert_always_zero`, `assert_never_grows`, and
  `standard_resource_metrics`. Also FFI-free, for the same reason — see
  `../tests/trends_test.cpp`.
- **`helpers.hpp`/`.cpp`** — `ResourceSampler` (the `/proc` reader),
  `connect_with_retries`, `pump_until_frame_received`, and
  `sine_wave_chunk`. This is the one file that needs `reactor::sdk`.
