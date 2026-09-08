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
is a deliberate next step, not built here.

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

## The four signals, and what "leak" means for each

- **Process RSS** (`psutil`) — should plateau, not grow without bound.
  Checked as a trend (mean of the run's last third vs. its first third, after
  dropping a 20% warm-up) rather than a single before/after number: allocator
  and OS page-cache behavior is noisy sample-to-sample, so only a *sustained*
  climb counts.
- **CPU time** (`psutil`, user+system) — same trend check, wider tolerance.
  CPU isn't itself what "leak" means (it's a rate, not accumulated state),
  but a steadily rising cost per cycle is still worth surfacing.
- **Native/handle-object counts** — `reactor_sdk.client._LIVE_CLIENTS` (a
  `weakref.WeakSet` the SDK already keeps so the interpreter can force-close
  stragglers at exit) and `_ORPHANED_CALLBACKS` (callback trampolines the SDK
  couldn't confirm were safe to free — its own comment says growth there
  "means handlers are blocking or clients are being closed from inside a
  handler"). Unlike RSS/CPU these are exact integers, not noisy
  measurements, so they get a plain assertion instead of a trend: `_LIVE_CLIENTS`
  must be **exactly 0** after every single lifecycle-churn cycle (a leak on
  cycle 3 that happens to clear by cycle 40 is still a real bug), and
  `_ORPHANED_CALLBACKS` must **never grow** past its starting value in either
  scenario. `test_session_churn.py` also asserts `Reactor._pending_completions`
  is empty between iterations — every `send_command` above is awaited to
  completion, so nothing should still be sitting there.
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
