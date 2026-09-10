# Swift SDK endurance/leak tests

REA-6088's Swift follow-up. Real `Reactor` clients — real FFI, real WebRTC —
against a real model in production (`reactor/echo` by default), the same
"nothing mocked" approach as `sdks/swift/IntegrationTests/` (see that suite's
own README for the fuller rationale, most of which applies unchanged here)
and as `sdks/python/endurance-tests/`/`sdks/cpp/endurance-tests/`, which this
suite mirrors file-for-file. The difference from IntegrationTests/ is what's
being checked: not whether one call behaves correctly, but whether
*repeating* it for a while leaves anything behind.

## Running it

```sh
export INTEGRATION_TESTS_REACTOR_API_KEY=...   # never pass this on a command line — export it
mise run test:swift:endurance-tests
```

**Manual-only for now.** Not wired into `ci.yml`, and not filtered in by
`swift.sh test`/`swift.sh integration-tests` — these runs are long,
inherently noisier than a correctness assertion (a growth *trend*, not a
single right-or-wrong call), and not meant to gate a PR or a release. The
`Endurance tests` `workflow_dispatch` workflow
(`.github/workflows/endurance-tests.yml`) is how this actually runs — pick
`swift` from the `sdk` input.

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

## Reading the printed table

Every scenario ends by printing one row per cycle — the same seven columns,
same wording, as `sdks/python/endurance-tests/README.md`'s own table (minus
`live_clients`/`orphaned_cbs`, see the scope-gap section below) and
`sdks/cpp/endurance-tests/README.md`'s. That table (with the same wording)
also lives as a comment directly above `ResourceSampler.printReport()` in
`Helpers.swift` — keep all three in sync if any changes.

## The signals, and what "leak" means for each

- **Process RSS, CPU time/%, threads, fds** — read via `task_info`/
  `getrusage`/`task_threads`/`/dev/fd` (see `Helpers.swift`'s own comments
  on each; no `psutil` equivalent is linked into this SDK, so this suite
  reads the platform's own accounting APIs directly, same as the C++ port
  reads `/proc`). All five signals are checked as a median-backed trend
  (mean or median of the run's last third vs. its first third, after
  dropping a 20% warm-up) — including `num_fds`, unlike the Python suite's
  real run, which found it exact. The C++ port (sharing this exact scenario
  shape against the same native layer) caught `num_fds` take one isolated
  step on a single cycle in real runs — either during a process's very
  first cycle or on a run's very last one — not a per-cycle leak, which
  would keep climbing sample over sample rather than step once; see
  `LifecycleChurnTests.swift`'s own comment on its `num_fds` check for the
  full account. `num_threads` gets the same trend treatment for the
  original Python-documented reason: native thread teardown isn't
  guaranteed synchronous with a client's `close()`.
- **The receive path** — both scenarios also subscribe to `main_video` via
  `onFrame`, not just publish/push frames: `LifecycleChurnTests.swift`
  registers once per client and lets `close()` tear the subscription down
  (guarded with `withExtendedLifetime` against Swift's ARC deallocating it
  early — see that file's own comment, written after the C++ port hit
  exactly this as a real bug); `SessionChurnTests.swift` registers and
  explicitly `.cancel()`s it every single iteration on the same long-lived
  session — the repeated subscribe/unsubscribe cycle a one-shot
  registration would never exercise.

## A known, deliberate scope gap

Unlike `sdks/python/endurance-tests/`, this suite has **no** equivalent of
`live_clients`/`orphaned_callbacks` — the Python binding's own
`_LIVE_CLIENTS`/`_ORPHANED_CALLBACKS` registries, kept because a
garbage-collected language can leave a native handle referenced (and
therefore alive) for an indeterminate time after a caller is "done" with
it. This Swift binding narrows that gap but doesn't close it the same way
the C++ port's deterministic RAII does: `Reactor.close()` is idempotent and
explicit, called manually at a known point in both scenarios here rather
than left to `deinit`, but a `Reactor`/`Subscription` is still a reference-
counted class — ARC decides when the *last* reference goes away, which
(unlike a C++ destructor) is not guaranteed to be at a specific lexical
point unless a caller forces it, exactly the reason
`LifecycleChurnTests.swift` needs `withExtendedLifetime` at all. What this
suite *can* rule out that Python's GC-based signals exist to catch: a
handle kept alive by something *inside this SDK itself* after `close()`
returns — `close()` is synchronous and unconditionally severs the native
handle (see `Reactor.close()`'s own doc), so nothing here can leave one
dangling past that call the way a lazily-collected Python object could.
What neither this suite nor the C++ port can catch: a leak entirely inside
`reactor-ffi`/`reactor-core` (Rust) that a caller's own correct usage would
never surface — the same "native-layer leak, out of scope for a
binding-level suite" gap the Python README's own final section documents.

## Reused from `IntegrationTests`

`Helpers.swift`/`*ChurnTests.swift` depend on the `IntegrationTests` target
directly (SwiftPM allows a test target to depend on another one) rather
than re-deriving connection setup, pacing, or media fixtures —
`IntegrationConfig.makeReactor`, `pacedConnect`, `withConnectedReactor`,
`MediaFixtures.solidBGRAFrame` all come from there unchanged. See that
suite's own README for what those do and why.
