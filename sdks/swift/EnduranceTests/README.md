# Swift SDK endurance/leak tests

Real `Reactor` clients — real FFI, real WebRTC — against a real model in
production (`reactor/echo` by default), the same "nothing mocked" approach as
`sdks/swift/IntegrationTests/` (see that suite's own README for the fuller
rationale, most of which applies unchanged here) and as
`sdks/python/endurance-tests/`/`sdks/cpp/endurance-tests/`, which this suite
mirrors file-for-file. The difference from IntegrationTests/ is what's being
checked: not whether one call behaves correctly, but whether *repeating* it
for a while leaves anything behind.

## Running it

```sh
export INTEGRATION_TESTS_REACTOR_API_KEY=...   # never pass this on a command line — export it
mise run test:swift:endurance-tests
```

Not filtered in by `swift.sh test`/`swift.sh integration-tests` — these runs
are long, inherently noisier than a correctness assertion (a growth *trend*,
not a single right-or-wrong call), and not meant to gate a PR or a release.
The `Endurance tests` `workflow_dispatch` workflow
(`.github/workflows/endurance-tests.yml`) is how this actually runs — pick
`swift` from the `sdk` input — see "Live output, reports, and artifacts"
below for what that run leaves behind.

By default this prints a compact status block every 30s instead of a row per
cycle — set `ENDURANCE_VERBOSE=1` for the old detailed per-cycle table
instead. See the next section.

**macOS only, deliberately** — unlike `IntegrationTests`, this has no iOS
Simulator counterpart. The leak this suite hunts lives in the native
FFI/WebRTC layer every binding shares, not in anything specific to iOS
packaging or device APIs; running the same check twice through two build
paths would cost real CI time for no signal an iOS Simulator run would add
that a macOS one doesn't already cover.

## Duration, not iteration count

Every scenario loops against a shared wall-clock deadline —
`ENDURANCE_DURATION_SECONDS` (default `300`, five minutes) — rather than a
fixed number of cycles. Same reasoning as the Python/C++ suites' identical
knob: one number, shared by every scenario, handles a quick manual sanity
check today and an hours-long leak hunt (or a future scheduled run) without
any code changes.

## What each scenario covers

- **`LifecycleChurnTests.swift`** — a brand-new `Reactor` per cycle: connect,
  publish, push frames, send a command, disconnect, close. This is the one
  that exercises the *whole* native-handle lifecycle — construction through
  the FFI, then `close()` releasing it — not just per-operation paths.
- **`SessionChurnTests.swift`** — one long-lived session, many
  publish/pushFrame/command/unpublish cycles against it. No reconnect
  overhead, so it packs far more iterations into the same window — the
  scenario for per-operation leaks (frame buffers, `Track`/`Subscription`
  objects) a coarser connect/close cycle wouldn't surface as clearly.
- **`PublishChurnTests.swift`** — one long-lived session, many
  publish/unpublish cycles on a single sendonly slot, with no frames and no
  command in between. Isolates the publish/unpublish path itself from
  session-churn's broader mix, so a leak specific to that pair reads as its
  own signal rather than being folded into a trend several different
  operations are contributing to.
- **`PauseResumeChurnTests.swift`** — one long-lived session, many
  pause/resume cycles on a recvonly track. Same isolation reasoning as
  publish-churn, applied to `Track.pause()`/`resume()` instead. This is the
  scenario that caught a real leak in Python's own first CI run against
  production — a genuine, linear, no-plateau RSS climb that
  publish-churn (same run, same process) did not show at all — which is
  exactly the case for keeping these as narrow, single-operation scenarios
  rather than one broad mix.
- **`VideoPublishSteadyTests.swift`** / **`AudioPublishSteadyTests.swift`** —
  publish once (video or audio) and hold it, continuously streaming for the
  whole run — no pause, no unpublish, no reconnect. The opposite shape from
  every scenario above: those are all *churn* (repeatedly doing and undoing
  something to surface a leak in that operation); these two are steady-state,
  the shape a real long call actually takes, so a leak tied to elapsed
  streaming time or frame/chunk count rather than to churn count still gets
  caught. Kept as two separate scenarios, not one parameterized over both —
  audio and video share nothing below `pushFrame()` (separate adapters,
  separate encoder/decoder threads in the native runtime), so a leak in one
  is not evidence about the other.

**Adding a new scenario**: write a loop that does the one thing you want to
isolate, then call `standardResourceMetrics(samples:fdsExact:)` (in
`EnduranceReporting/Trends.swift`) for the RSS/CPU/thread/fd checks every
scenario shares, and `finishAndCheck(...)` (in
`EnduranceReporting/Report.swift`) in your end-of-scenario cleanup for the
report-writing and raise-on-`FAIL` boilerplate — see any file in this
directory for the shape. Neither call needs its own new scenario-specific
invariant (a scenario that has one, like publish-churn's `track.published`
check or pause-resume-churn's `pausedTracks` check, still checks that itself,
in its own loop) — they only exist to keep the generic resource accounting
from being copied into every new file.

## Live output, reports, and artifacts

A multi-hour run printing one row per cycle makes a GitHub Actions log
unreadable long before it's useful — so what prints live, what gets kept for
later, and what summarizes the result are three separate things, mirroring
`sdks/python/endurance-tests/`'s identical split:

- **Live output (default): a compact status block, not a row per cycle.**
  Reprinted roughly every 30s (`ENDURANCE_LIVE_INTERVAL_SECONDS`, default
  `30`) — enough that opening the job while it's running always shows
  something recent, without scrolling a huge log for an hour-plus run:

  ```text
  ──────────────────────────────────────────────
  🚀 Endurance Test — session-churn
  ──────────────────────────────────────────────

  Duration       2h 00m
  Elapsed        1h 23m
  Progress       69%

  Iterations     1842

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
  run ends (see below); this block is answering "is it still running and
  does it look OK so far", not pre-empting the final result.

- **`ENDURANCE_VERBOSE=1`: gates a detailed path for debugging a short run**
  — the compact `LiveReporter` block above stays silent in this mode; see
  the C++/Python ports for the equivalent detailed per-cycle table, which
  this binding does not additionally print (the compact block plus the
  written JSON's full `samples` array already cover the same information).

- **Every scenario's full sample history, always, regardless of the above:**
  written to `sdks/swift/endurance-results/` when the scenario ends (pass,
  fail, *or* error), three files per scenario:

  ```text
  endurance-results/
    session-churn.json           # every sample, every metric's start/end/
                                  # threshold — the source of truth for
                                  # post-run debugging
    session-churn-report.md      # human-readable: table + a plain-language
                                  # conclusion, meant for the GitHub Actions
                                  # Job Summary or pasting into a PR/Slack
    session-churn-summary.txt    # the same report, no Markdown — for
                                  # downloading and opening in a
                                  # terminal/editor
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

  All three come from the same in-memory result (`EnduranceReporting`'s
  `RunResult`), so `.md`/`.txt` can never say something different from
  what's in the `.json` — there's exactly one place the numbers are
  computed.

- **The GitHub Actions workflow publishes `*-report.md` to the Job
  Summary** (`$GITHUB_STEP_SUMMARY`) and uploads all of
  `endurance-results/` as the `endurance-test-results-swift` artifact — both
  steps run with `if: always()`, specifically so a failed run still leaves a
  readable summary and a downloadable artifact instead of just a wall of
  test-runner traceback.

- **A run's status is one of three, not just pass/fail** — `PASS` (every
  metric stayed within its threshold), `FAIL` (a metric's threshold check in
  `EnduranceReporting/Trends.swift` — `assertNoSustainedGrowth`/
  `assertAlwaysZero`/`assertNeverGrows` — failed), or `ERROR` (the run never
  got far enough to evaluate a metric at all: an unhandled error, a
  live-service error like a rate limit, an `ENDURANCE_DURATION_SECONDS` too
  short to collect enough samples). Swift has no equivalent of Python's
  `sys.exc_info()` introspection inside a `finally` block, so unlike
  Python's `finish_run` the `runError` that decides ERROR-vs-not is passed
  in explicitly by the scenario (which already catches its own loop's error
  into a `pending` local to keep the report-writing path unconditional —
  see `Report.swift`'s own doc comments on `finishRun`/`finishAndCheck`).
  The report's conclusion is written differently for each — an `ERROR`
  explicitly says it isn't a leak verdict, since no metric ran to completion
  to judge.

## Reading the Results table

Each `*-report.md`/`-summary.txt`'s "Results" table has five columns:
`Start | Mid | End | Δ (Mid→End) | Status`. `Start`/`Mid`/`End` are the mean
(or median — see `assertNoSustainedGrowth`) of the first, middle, and last
third of the run, after dropping warm-up — not raw first/last samples.

`Start`→`End` alone can look like growth on a perfectly healthy run: a buffer
or connection pool reaching its steady-state size shows up as a one-time step
early on, which moves `End` up relative to `Start` without being a leak. The
pass/fail verdict is actually decided by `Mid`→`End` (the `Δ (Mid→End)`
column) — did it keep climbing in the back half, or did it plateau — which
is the same comparison `assertNoSustainedGrowth` makes internally (see its
doc comment in `Trends.swift`). Reading `Δ (Mid→End)` ≈ 0 is what "no leak"
looks like in this table, even when `Start`→`End` shows a real jump. `Mid`
(and `Δ (Mid→End)`) render as `—` for the exact always-zero/never-grows
checks (`num_fds` on lifecycle-churn, `Errors`), which have no middle-third
concept.

## The `Timeline`

Under each report's `## Timeline` section (also in the JSON's `checkpoints`
array): five evenly-spaced snapshots across the run (0/25/50/75/100%) of
RSS/threads/fds/CPU%. The Results table above only compares the mean of the
first third to the mean of the last third — the right check for "did this
end up worse than it started", but it collapses the actual shape of a metric
over time into two numbers. A metric that ramped once and plateaued (a
buffer/pool growing once to its steady-state size, then holding) reads
identically there to one that climbed steadily the whole run; the Timeline
is what shows the difference at a glance.

## The signals, what they mean, and the value we expect

| signal | how it's checked | expected value | why that number |
| --- | --- | --- | --- |
| RSS | trend: mean of the run's last third vs. its *middle* third, after a 20% warm-up | < 15% growth, and only counted if the absolute change is also ≥ 5 MB | allocator/page-cache noise is real sample-to-sample; the 5 MB floor stops a tiny near-zero baseline from turning an insignificant wobble into a huge-looking ratio |
| CPU time (`cpu_s_per_cycle`) | same trend check, on per-interval deltas (never cumulative) | < 50% growth, floor 0.05s | a wider tolerance than RSS: legitimate cycle-to-cycle jitter is larger here than for memory |
| CPU % (`cpu_percent`) | same trend check | < 50% growth, floor 5.0 (percentage points) | catches a leak that shows up as a growing *share* of CPU busy-ness even when `cpu_s_per_cycle` itself doesn't trend |
| `num_threads` | trend (median, not mean) | < 15% growth, floor 4 threads | thread teardown isn't guaranteed synchronous with `close()`, so a real run can oscillate with no sustained direction; median absorbs one double-counted cycle without hiding a real trend |
| `num_fds` | **exact**: never above its starting value (lifecycle-churn) or trend (every other scenario) | 0 growth past baseline (lifecycle-churn); < 15% growth, floor 3 (elsewhere) | fd teardown *is* synchronous with `disconnect()`/`close()` returning on this binding's lifecycle-churn cycle, so it can hold to an exact bound there; a long-lived session can legitimately open a few more during warm-up, so the other scenarios get the same trend treatment as RSS/CPU instead |
| `errors` (lifecycle-churn only) | reported, not asserted on | 0 expected, not enforced | counts a `disconnect()` that failed on an otherwise-normal cycle — the one thing this scenario retries-and-swallows rather than failing the whole run over; every other scenario leaves this unset (see `RunResult.errors`'s own doc) since nothing in their loops does this |
| scenario-specific invariants (`track.published` on publish-churn, `pausedTracks` on pause-resume-churn) | **exact**, checked in the scenario's own loop | always false/empty | these are counts, not noisy measurements — a leak on one iteration that clears by the next is still a real bug, so a plain break-on-first-violation check, not a trend |

## A known, deliberate scope gap

Unlike `sdks/python/endurance-tests/`, this suite has **no** equivalent of
`live_clients`/`orphaned_callbacks` — the Python binding's own
`_LIVE_CLIENTS`/`_ORPHANED_CALLBACKS` registries, kept because a
garbage-collected language can leave a native handle referenced (and
therefore alive) for an indeterminate time after a caller is "done" with it.
This Swift binding narrows that gap but doesn't close it the same way the
C++ port's deterministic RAII does: `Reactor.close()` is idempotent and
explicit, called manually at a known point in every scenario here rather
than left to `deinit`, but a `Reactor`/`Subscription` is still a
reference-counted class — ARC decides when the *last* reference goes away,
which (unlike a C++ destructor) is not guaranteed to be at a specific
lexical point unless a caller forces it, exactly the reason
`LifecycleChurnTests.swift` needs `withExtendedLifetime` at all. What this
suite *can* rule out that Python's GC-based signals exist to catch: a handle
kept alive by something *inside this SDK itself* after `close()` returns —
`close()` is synchronous and unconditionally severs the native handle (see
`Reactor.close()`'s own doc), so nothing here can leave one dangling past
that call the way a lazily-collected Python object could. What neither this
suite nor the C++ port can catch: a leak entirely inside
`reactor-ffi`/`reactor-core` (Rust) that a caller's own correct usage would
never surface.

A second, narrower gap in the same spirit: `SessionChurnTests.swift` does
not check a per-iteration internal-bookkeeping invariant the way Python's
own `test_session_churn.py` checks `Reactor._pending_completions`/
`Track._adapters` between iterations — those are Python-object-internal
counters a dynamic language's own test can reach into directly. This suite
deliberately stays black-box against the public API (`import Reactor`, no
`@testable import`, the same posture `IntegrationTests` takes), so there is
no equivalent internal counter to check here without reaching for
`@testable import Reactor` — a departure from how this suite is otherwise
written that isn't taken lightly. The RSS/thread/fd trend checks still cover
the same class of bug indirectly: a leaked completion or frame handler shows
up as a thread or fd that never gets released, just with less precision
about *which* internal structure is holding it.

## Reused from `TestSupport`

`Helpers.swift`/`*Tests.swift` depend on `TestSupport`
(`sdks/swift/TestSupport/`) rather than re-deriving connection setup, pacing,
or media fixtures — `IntegrationConfig.makeReactor`, `pacedConnect`,
`withConnectedReactor`, `MediaFixtures.solidBGRAFrame`/`sineWaveSamples` all
come from there unchanged, the same target `IntegrationTests` itself depends
on for the same fixtures. A plain library target, not a test target:
`xcodebuild`'s package-graph resolution (unlike plain `swift build`/`swift
test`) refuses a *test* target depending on another *test* target, which is
what this suite depending on `IntegrationTests` directly did at first,
briefly breaking the `swift-integration-tests-ios-simulator` CI job — see
`TestSupport/Fixtures.swift`'s own header comment for the full account. See
`IntegrationTests`' own README for what these fixtures do and why.

## Reused from `EnduranceReporting`

Duration handling, `ResourceSampler`, the trend/count assertions
(`assertNoSustainedGrowth`/`assertAlwaysZero`/`assertNeverGrows`,
`standardResourceMetrics`), and the whole reporting layer (`MetricResult`,
`RunResult`, `LiveReporter`, JSON/Markdown/text rendering,
`finishRun`/`finishAndCheck`) live in the sibling `EnduranceReporting`
target (`sdks/swift/EnduranceReporting/`), not in this directory. That
target has **no** dependency on `Reactor`/`CReactorFFI` — unlike
`Helpers.swift`, which needs a real `Reactor`/`Track` for connect resilience
and frame pumping — so it can have its own standalone unit tests
(`sdks/swift/EnduranceReportingTests/`) that need no live service or native
library, mirroring `sdks/python/endurance-tests/trends.py`+`report.py` being
importable without a built `reactor_sdk`. Mirrors
`sdks/python/endurance-tests/helpers.py`'s own split between what needs the
FFI and what doesn't (see that module's own docstring for the identical
reasoning), one level higher up: two SwiftPM targets rather than two Python
modules in one package.
