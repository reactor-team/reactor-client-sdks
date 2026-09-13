# Python SDK endurance/leak tests

REA-6088. Real `reactor_sdk.Reactor` clients — real `libreactor_ffi`, real
WebRTC — against a real model in production (`reactor/echo` by default), the
same "nothing mocked" approach as `sdks/python/integration-tests/` (see that
suite's own README for the fuller rationale, most of which applies unchanged
here). The difference is what's being checked: not whether one call behaves
correctly, but whether *repeating* it for a while leaves anything behind.

## Running it

```sh
export INTEGRATION_TESTS_REACTOR_API_KEY=...   # never pass this on a command line — export it
mise run test:python:endurance-tests
```

Or directly, from `sdks/python` (after `cargo build -p reactor-ffi --release`
from the repo root):

```sh
ENDURANCE_DURATION_SECONDS=3600 uv run --group dev pytest endurance-tests/tests -v -s
```

**Manual-only for now.** Not wired into `ci.yml`, and not a dependency of
`test`/`test:python` — these runs are long, inherently noisier than a
correctness assertion (a growth *trend*, not a single right-or-wrong call),
and not meant to gate a PR or a release. Scheduling this on a nightly cadence
is a deliberate next step, not built here. It *is* wired into its own
`endurance-tests.yml` GitHub Actions workflow, run by hand from the Actions
tab (`workflow_dispatch`) — see "Live output, reports, and artifacts" below
for what that run leaves behind.

By default this prints a compact status block every 30s instead of a row per
cycle — set `ENDURANCE_VERBOSE=1` for the old detailed per-cycle table
instead. See the next section.

## Duration, not iteration count

Every scenario loops against a shared wall-clock deadline —
`ENDURANCE_DURATION_SECONDS` (default `300`, five minutes: short enough to
"just work" as a sanity check without configuration) — rather than a fixed
number of cycles. Hunting a real, slow leak means overriding this directly
and letting it run much longer (an hour or more); the same code handles both,
and will handle a future scheduled run too, without changes.

## What each scenario covers

- **`test_lifecycle_churn.py`** — a brand-new `Reactor` per cycle: connect,
  publish, push frames, send a command, disconnect, close. This is the one
  that exercises the *whole* native-handle lifecycle
  (`_create_handle`/`_destroy_handle` in `reactor_sdk/client.py`), not just
  per-operation paths.
- **`test_session_churn.py`** — one long-lived session, many
  publish/push_frame/command/unpublish cycles against it. No reconnect
  overhead, so it packs far more iterations into the same window — the
  scenario for per-operation leaks (frame buffers, `Track` objects, pending
  command completions) a coarser connect/close cycle wouldn't surface as
  clearly.

## Live output, reports, and artifacts

A multi-hour run printing one row per cycle (the original behavior) makes a
GitHub Actions log unreadable long before it's useful — so what prints live,
what gets kept for later, and what summarizes the result are now three
separate things:

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

  Iterations     1,842
  Errors         0

  Resources
    RSS           284 MB → 291 MB   (+7 MB)
    CPU           avg 18.4%
    Threads       14 → 14
    File desc.    23 → 23
    Pending       0

  Status         🟢 Healthy
  ──────────────────────────────────────────────
  ```

  "Healthy" here only means no errors have surfaced yet during the run — the
  pass/fail *verdict* against each metric's threshold is only known once the
  run ends (see below); this block is answering "is it still running and
  does it look OK so far", not pre-empting the final result.

- **`ENDURANCE_VERBOSE=1`: the detailed per-cycle table, live.** The debug
  path — for a short manual run where you want to watch every cycle as it
  happens, not a periodic summary. See "Reading the per-cycle table" below
  for what the columns mean. (This replaces the old behavior of buffering
  every row and dumping the whole table once at the end — same information,
  printed as it happens instead of after the fact.)

- **Every scenario's full sample history, always, regardless of the above:**
  written to `sdks/python/endurance-results/` when the scenario ends (pass,
  fail, *or* error — see the `finally` block in each `tests/*.py` file),
  three files per scenario:

  ```text
  endurance-results/
    session-churn.json           # every sample, every metric's start/end/
                                  # threshold, tracemalloc diagnostics — the
                                  # source of truth for post-run debugging
    session-churn-report.md      # human-readable: table + a plain-language
                                  # conclusion, meant for the GitHub Actions
                                  # Job Summary or pasting into a PR/Slack
    session-churn-summary.txt    # the same report, no Markdown — for
                                  # downloading and opening in a terminal/editor
    lifecycle-churn.json
    lifecycle-churn-report.md
    lifecycle-churn-summary.txt
  ```

  All three come from the same in-memory result (`report.py`'s `RunResult`),
  so `.md` and `.txt` can never say something different from what's in the
  `.json` — there's exactly one place the numbers are computed.

- **The GitHub Actions workflow publishes `*-report.md` to the Job
  Summary** (`$GITHUB_STEP_SUMMARY`) and uploads all of
  `endurance-results/` as the `endurance-test-results` artifact — both
  steps run with `if: always()`, specifically so a failed run still leaves
  a readable summary and a downloadable artifact instead of just a wall of
  pytest traceback. Opening a failed workflow run should answer "what
  failed and by how much" without reading the job log line by line.

- **A run's status is one of three, not just pass/fail** — `PASS` (every
  metric stayed within its threshold), `FAIL` (a metric's threshold check in
  `helpers.py` — `assert_no_sustained_growth`/`assert_always_zero`/
  `assert_never_grows` — failed), or `ERROR` (the run never got far enough
  to evaluate a metric at all: an unhandled exception, a live-service error
  like a rate limit or a fixture failure, an `ENDURANCE_DURATION_SECONDS`
  too short to collect enough samples). The report's conclusion is written
  differently for each — an `ERROR` explicitly says it isn't a leak
  verdict, since no metric ran to completion to judge.

## Reading the per-cycle table

Under `ENDURANCE_VERBOSE=1` (or in the raw JSON's `samples`), every cycle is
one row. In plain language, left to right:

| column            | what it is                                                                                          | what "bad" looks like                          |
| ----------------- | ---------------------------------------------------------------------------------------------------- | ----------------------------------------------- |
| `cycle`           | which iteration of the loop this row is (0, 1, 2, ...)                                                 | —                                                |
| `elapsed_s`       | seconds since this test started                                                                        | —                                                |
| `ram_mb`          | physical RAM the whole process is using right now — not just the SDK, everything in this one process   | keeps climbing, never plateaus                  |
| `cpu_s_per_cycle` | CPU time *this one cycle* burned (not a running total)                                                | keeps getting bigger cycle to cycle             |
| `cpu_percent`     | % of one CPU core busy since the previous row (like Activity Monitor/htop's own number) — can read over 100% if more than one native thread is genuinely busy at once, that's normal | keeps getting bigger cycle to cycle |
| `live_clients`    | how many `Reactor` clients still have an open native connection right now                              | higher than expected (0 in lifecycle-churn, 1 in session-churn) |
| `orphaned_cbs`    | frame/event callbacks the SDK couldn't confirm were safe to free when a client closed                  | anything above 0, ever                          |
| `num_threads`     | OS-level threads this process currently has (mostly the native Rust runtime's)                         | keeps climbing (some early wobble is normal)    |
| `num_fds`         | open file descriptors — sockets, mainly, since every WebRTC connection needs some                      | keeps climbing                                  |

This table (with the same wording) also lives as a comment directly above
`ResourceSampler.print_live_row()` in `helpers.py` — keep both in sync if
either changes.

## The signals, what they mean, and the value we expect

Every signal below is checked against a concrete threshold in `tests/*.py`
(the same numbers show up as each metric's `threshold` in the JSON/report) —
not just "should plateau" in the abstract:

| signal | how it's checked | expected value | why that number |
| --- | --- | --- | --- |
| RSS (`ram_mb`) | trend: mean of the run's last third vs. its *middle* third, after a 20% warm-up (see below) | < 15% growth, and only counted if the absolute change is also ≥ 5 MB | allocator/page-cache noise is real sample-to-sample; the 5 MB floor stops a tiny near-zero baseline from turning an insignificant wobble into a huge-looking ratio |
| CPU time (`cpu_s_per_cycle`) | same trend check, on `cpu_deltas()` (per-interval, not cumulative) | < 50% growth, floor 0.05s | a wider tolerance than RSS: legitimate cycle-to-cycle jitter (GC pauses, network scheduling) is larger here than for memory |
| CPU % (`cpu_percent`) | same trend check | < 50% growth, floor 5.0 (percentage points) | catches a leak that shows up as a growing *share* of CPU busy-ness even when `cpu_s_per_cycle` itself doesn't trend — see below for why the two can disagree |
| `num_threads` | trend (median, not mean) | < 15% growth, floor 4 threads | thread teardown isn't guaranteed synchronous with `close()`, so a real run can oscillate (e.g. 25→31→25) with no sustained direction; median absorbs one double-counted cycle without hiding a real trend |
| `num_fds` | **exact**: never above its starting value (lifecycle-churn) or trend (session-churn) | 0 growth past baseline (lifecycle-churn); < 15% growth, floor 3 (session-churn) | fd teardown *is* synchronous with `disconnect()`/`close()` returning, so lifecycle-churn — a fresh client every cycle — can hold it to an exact bound; session-churn's one long-lived session can legitimately open a few more during warm-up (a connection pool, a worker thread), so it gets the same trend treatment as RSS/CPU instead |
| `live_clients` | **exact**: always 0 (lifecycle-churn) or never above baseline (session-churn) | 0 after every lifecycle-churn cycle; flat at 1 for session-churn's whole run | a count, not a noisy measurement — a leak on cycle 3 that clears by cycle 40 is still a real bug, so this isn't a trend check |
| `orphaned_cbs` | **exact**: always 0 | 0, every cycle, both scenarios | comes only from clients that already closed — `assert_never_grows` would only flag growth *past* the first sample, letting a leak already present at cycle 0 pass silently forever |
| `Track._adapters` / `Reactor._pending_completions` (session-churn only) | **exact**: 0 between iterations | 0 | every `send_command` is awaited to completion and every `on_frame` has a matching `off_frame` before the next iteration starts — anything left over is a leaked awaitable or handler, not noise |
| tracemalloc top single-traceback growth | diagnostic, hard floor | < 5 MB | `tracemalloc` diffs are known-noisy from one-time caches and string interning; this is a "definitely real" floor, not a tight auto-threshold, since a human reads the top-10 breakdown rather than a nightly job trusting a narrow number |

## The signals, in plain language

- **Process RSS** (`ram_mb` above, via `psutil`) — should plateau, not grow
  without bound. Checked as a trend rather than a single before/after number
  (allocator and OS page-cache behavior is noisy sample-to-sample, so only a
  *sustained* climb counts) — specifically the run's last third against its
  *middle* third, not its first, after dropping a 20% warm-up. That
  distinction matters: a real CI run showed RSS flat, then a one-time ramp
  to a new plateau somewhere in the middle of the window, then flat again —
  a native buffer/pool growing once to its steady-state size, not an
  unbounded leak. Comparing against the first third instead means exactly
  *when* that one-time ramp happens to land decides pass/fail (the same
  total jump read as a comfortable pass in one run and a razor-thin fail in
  another, purely from timing) — comparing the last third to the middle
  third instead asks the more direct question, "is this still climbing after
  the fact", which a one-time step that's already plateaued answers "no"
  regardless of when it happened. See `assert_no_sustained_growth`'s own
  docstring in `helpers.py` for the full reasoning, including the trade-off
  (a real leak still only in its early, slow-accelerating phase near the end
  of a short run could likewise read as "already flat" here — preferred
  anyway over failing a PR on a one-time step that already stopped).
- **CPU time and CPU %** (`cpu_s_per_cycle`/`cpu_percent` above, both from
  `psutil`) — same trend check as RSS, wider tolerance, on two different
  questions: `cpu_s_per_cycle` is how much actual CPU work a cycle did,
  `cpu_percent` is how busy the CPU was *relative to how long the cycle
  took*. A cycle that's mostly waiting on the network can do identical CPU
  work in more wall-clock time and show a lower percentage even with the same
  `cpu_s_per_cycle` — checking both catches a leak that shows up in one but
  not the other. Neither is itself what "leak" means (they're rates, not
  accumulated state), but a steadily rising cost per cycle is still worth
  surfacing.
- **Threads and file descriptors** (`num_threads`/`num_fds` above, `psutil`) —
  a leaked native thread or socket in WebRTC-adjacent code doesn't always show
  up as a dramatic RSS jump before something else (a thread or fd ceiling)
  fails first. `num_fds` proved a rock-solid exact count in a real run (never
  varied cycle to cycle), so it gets the same strict "never past its starting
  value" treatment as `orphaned_cbs` below. `num_threads` did *not* — native
  thread teardown isn't guaranteed synchronous with `close()`/`gc.collect()`
  the way an fd close or a Python object's collection is, so it's checked as a
  trend like RSS/CPU instead.
- **Native/handle-object counts** (`live_clients`/`orphaned_cbs` above) —
  `reactor_sdk.client._LIVE_CLIENTS` (a `weakref.WeakSet` the SDK already
  keeps so the interpreter can force-close stragglers at exit) and
  `_ORPHANED_CALLBACKS` (callback trampolines the SDK couldn't confirm were
  safe to free — its own comment says growth there "means handlers are
  blocking or clients are being closed from inside a handler"). Unlike
  RSS/CPU these are exact integers, not noisy measurements, so they get a
  plain assertion instead of a trend: `_LIVE_CLIENTS` must be **exactly 0**
  after every single lifecycle-churn cycle (a leak on cycle 3 that happens to
  clear by cycle 40 is still a real bug), and `_ORPHANED_CALLBACKS` must
  **never grow** past its starting value in either scenario.
- **The receive path** — both scenarios also subscribe to `main_video` via
  `on_frame`/`off_frame` (`Track._adapters`), not just publish/push frames:
  `test_lifecycle_churn.py` registers once per client and lets `close()` tear
  it down; `test_session_churn.py` registers and unregisters every single
  iteration on the same long-lived session, asserting `Track._adapters` is
  empty afterward every time — the leak this would have caught otherwise
  (a subscribe/unsubscribe cycle leaking a handler) is invisible to every
  other signal above. `test_session_churn.py` also asserts
  `Reactor._pending_completions` is empty between iterations — every
  `send_command` above is awaited to completion, so nothing should still be
  sitting there.
- **Managed-runtime (Python heap) memory** — `tracemalloc`, snapshotted at
  ~30% of the run and again at the end, diffed with `compare_to(..., 'lineno')`.
  Printed in full (top 10 allocation sites by growth) as a diagnostic always;
  only hard-asserted against a generous absolute floor (5 MB single-traceback
  growth) rather than a tight threshold — `tracemalloc` diffs are known-noisy
  from one-time caches and string interning, and since this suite is
  manual-only for now, a human reads the report rather than a nightly job
  trusting a tight auto-threshold to not cry wolf.

## A known, deliberate scope gap

"Native handle" leak detection above is entirely at the Python-object layer
(`_LIVE_CLIENTS`, `_ORPHANED_CALLBACKS`) — nothing here adds a counter inside
`reactor-core`/`reactor-ffi` (Rust) itself. That would sharpen detection of a
leak inside the native layer specifically, as opposed to a Python-side
reference keeping a handle alive past when it should — but it means an FFI
ABI change shared by every binding, which is out of scope for this
Python-first cut. Worth reconsidering if these two signals ever prove
insufficient to catch a real leak.

## Reused from `integration-tests/`

`helpers.py` imports `new_reactor`, `paced_connect`, `solid_rgb_frame`, and
the `reactor`/`reactor_factory` fixtures straight from
`../integration-tests/conftest.py` (by explicit file path, not a
`sys.path`-based `import conftest` — both directories have a file literally
named `conftest.py`, and pytest resolving the bare name `conftest` to
whichever loads first is exactly what bit an earlier draft of this suite)
rather than re-deriving connection setup, pacing, or media fixtures. See that
suite's own README for what those do and why.

`conftest.py` here stays minimal on purpose — just the `reactor`/
`reactor_factory` fixture re-exports pytest needs to find in a file actually
named `conftest.py`. Everything `tests/*.py` imports directly (duration
handling, `ResourceSampler`, the trend/count assertions) lives in
`helpers.py` instead, imported as `from helpers import ...` — never
`from conftest import ...`, for the same collision reason above.
