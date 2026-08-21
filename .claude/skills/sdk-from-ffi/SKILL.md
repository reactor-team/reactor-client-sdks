---
name: sdk-from-ffi
description: >
  Build a new Reactor client SDK in another language on top of `libreactor_ffi` — Node,
  Go, Swift, Kotlin, C#, Ruby — or review a PR that adds or changes one. Use this when
  the user asks to "write the Node/Go/Swift SDK", "bind reactor-ffi from <language>",
  "port the Python SDK to <language>", "add a new SDK under sdks/", or asks how the FFI
  boundary, callback threading, string ownership, or wheel-style packaging is supposed to
  work for a binding. Also use it when a binding crashes on teardown, receives frames
  that never arrive, or silently sends nothing — those are the failure modes this
  methodology exists to prevent. Also use it for the seven example scenarios every SDK
  ships, which are parity requirements rather than documentation.
---

# Building an SDK on `reactor-ffi`

The Python SDK is the first binding, and everything below is what it cost to arrive at.
Read it before writing the second one: none of these are style preferences, and most were
found by shipping the mistake first.

The shape is always the same. A Rust core (`crates/reactor-core`) does the protocol and
WebRTC; `crates/reactor-ffi` exposes it as a small set of C functions —
`scripts/check-abi-parity.py` reports the current count, so take it from there rather than
from a number written down somewhere; your SDK is a binding over those plus an object model
that hides them. **You are writing the object model, not a
transport.** If a decision can be made in the core instead, make it there — every binding
inherits the fix.

---

## Start here, in this order

1. **Build the library.** `cargo build -p reactor-ffi --release` → `target/release/`
   (`libreactor_ffi.dylib` / `.so` / `reactor_ffi.dll`).
2. **Read the header, all of it**:
   [`crates/reactor-ffi/include/reactor_ffi.h`](../../../crates/reactor-ffi/include/reactor_ffi.h).
   It is the contract — threading, teardown, string ownership, per-function nullability.
   It is also the only one of the three ABI copies written for a reader. Do not paraphrase
   it into your binding's docs; link to it.
3. **Read [`sdks/python/reactor_sdk/`](../../../sdks/python/reactor_sdk/)** for what the
   object model came out as: `client.py` (handle lifetime, callbacks, event dispatch),
   `track.py` (the object users actually hold), `errors.py`, `_ffi.py` (the ctypes
   declarations, i.e. the same job you are about to do).
4. **Then** write your binding.

---

## The ABI is hand-copied three times, and it drifts

The exported surface exists in three independent places:

| Copy | Who compiles against it | What a mismatch costs |
|---|---|---|
| `crates/reactor-ffi/src/lib.rs` | nobody — it *is* the ABI | — |
| `crates/reactor-ffi/include/reactor_ffi.h` | Go, C++, Kotlin, Swift | a missing declaration means the function does not exist for you |
| `sdks/python/reactor_sdk/_ffi.py` | Python | nothing is checked; a wrong signature is undefined behaviour |

[`scripts/check-abi-parity.py`](../../../scripts/check-abi-parity.py) runs in CI and
compares them **by function name only**. Arity and types are not checked and cannot be, so
this is the failure mode to design against:

> A function that gained a parameter still links, still resolves, and corrupts the stack
> at the call. It does not fail at load. It looks like a hang, or like the operation
> silently doing nothing — not like a version error.

This has bitten twice. Both times the library on disk was older than the crates. Concretely:

- **Add your language's declarations to the parity script** when you add an SDK, so the
  same guard covers you.
- **Rebuild the native library after pulling changes under `crates/`.** Put it in your
  SDK's README under Development, because whoever hits it will not guess.
- **Prefer failing loudly at load.** If your language can check a version symbol or an
  arity at binding time, do it; that is a guard Python could not have cheaply and paid for.

---

## Five invariants every binding must hold

The header states these; this is what each one looks like when you get it wrong.

**1. Copy callback data before returning.** Strings and buffers are valid only for the
duration of the callback. Keeping the pointer gives you a use-after-free that reproduces
under load and not in tests.

**2. Keep your callback trampolines alive until `reactor_destroy` returns 0.** The library
holds raw pointers to them. `reactor_destroy` blocks until no callback is running and
returns 0 for "quiesced, safe to release"; **-1 means a callback is still in flight and you
must keep the pointers alive anyway** — leak them deliberately. Python keeps a module-level
list of orphaned trampolines and never empties it: a small permanent leak beats a jump into
freed memory. See `_ORPHANED_CALLBACKS` in `client.py`.

**3. Know which strings you own.** Three cases, and the header states which for every
function — read it there rather than inferring from a name:

- **Static, never free.** `reactor_status` returns a `const char *` literal.
- **Yours, must free.** `reactor_session_id`, `reactor_tracks`, `reactor_paused_tracks`, and
  the error object `reactor_unpublish_track` returns on failure. Pass each to
  `reactor_free_string`.
- **Borrowed, never free.** Every string handed *to* a callback — a completion's
  `result_json` and `error_json`, `on_error`'s `error_json`, a message's `msg_json`. The FFI
  frees them once the callback returns, so copy what you keep.

The asymmetry is deliberate and easy to get backwards in all three directions: freeing the
static one corrupts the heap, freeing a borrowed one is a double free, and not freeing the
owned ones leaks on every property read.

**4. Do not let a callback keep the client alive.** Callbacks capture the client *weakly*
in Python, because a handler parked in a capture thread would otherwise hold the session —
and the native handle — open for the life of that thread. Whatever your language's
equivalent is (weak ref, handle table, id), use it.

**5. Marshal control events to your host's loop; deliver media inline.** These differ on
purpose:

- Control events (status, error, message, track, session id) end up calling into the host's
  concurrency primitives, which are usually not thread-safe from a foreign thread. Python
  hands them to the event loop with `call_soon_threadsafe`.
- Media (`on_frame`, `on_audio`) runs **inline on the FFI's delivery thread**, deliberately.
  Blocking there is the backpressure: the FFI keeps only the newest video frame while your
  handler runs. Hand frames to a queue instead and you trade a bounded drop for unbounded
  latency and memory.

---

## The object model, which every SDK should agree on

Users move between our SDKs. Same concepts, same names, idiomatic spelling.

**`Reactor`** — the client. Connection, commands, recordings, uploads, and access to tracks.
No media methods on it: see below.

**`Track`** — a named media slot the *model* declares, with a `kind` (video/audio) and a
`direction` (sendonly/recvonly). One type for all four combinations, because the operations
are the same operations. It carries `push_frame`, `on_frame`, `on_raw_frame`, `publish`,
`unpublish`, `pause`, `resume`, `published`, `paused`, `mid`.

**A list of tracks with filters** — `tracks.with_kind(...)`, `.with_direction(...)`,
`.one()`, chainable in either order, so a caller can say which track they mean without
hardcoding a name.

**Errors** — one flat list of codes shared with the core (`crates/reactor-core/src/error.rs`),
16 typed classes over it, each carrying `code`, `message`, `recoverable`, `status`,
`operation`, `retry_after_ms`. Recoverability is **derived from the code**, never stored
per-site, so two SDKs cannot disagree about whether a timeout is worth retrying. The same
object is what an `on_error` event delivers and what a failed call raises.

Three things Python removed after shipping them, so do not add them:

- Client-wide media events (`on("frame", ...)`) — one handler fed every recvonly track
  cannot tell them apart, which is the whole reason `Track` exists.
- Name-based twins of track methods (`push_video_frame(name, ...)`, `pause_track(name)`) —
  a second way to say the same thing, and the one that cannot check what it was asked.
- A `component` field on errors — which tier failed is not something a caller can act on.

---

## Refuse; do not fail quietly

This is the through-line of the whole Python SDK, and the single most valuable thing to
copy. The native layer is permissive: pushing into a track that does not exist, or that
points the other way, or that was never published, reaches the FFI, finds nothing to do,
and returns. The caller sees a loop pushing at 30fps and a model receiving nothing.

Every one of these must raise in your binding, with the fix in the message:

| Situation | What the FFI does | What your SDK must do |
|---|---|---|
| Track name the session never declared | nothing | raise, listing the declared names |
| `push_frame` on a recvonly track | nothing | raise, naming the direction |
| `on_frame` on a sendonly track | never fires | raise |
| `push_frame` before `publish()` | drops the frame | raise `InvalidStateError` |
| Raw frame bytes whose length ≠ `width * height * 4` | reads out of bounds | raise, naming both numbers |
| Handler registered on a removed event | never fires | raise at registration |
| Frame arriving with no matching track | — | drop, and log it |

Publishing state is not recorded by the session — `publish_track` is a request and
`unpublish_track` a notification, and neither leaves anything to query — so your binding
keeps it locally. **Clear it whenever the status leaves `ready`**: a reconnect resumes
recvonly tracks and nothing else, so a slot published before one is not published after it,
and remembering otherwise reintroduces exactly the silent failure above. Only clear it on a
*successful* unpublish, or a failed one becomes unretryable.

---

## Audio devices

The core is pinned to the **synthetic** audio module (`reactor_create_with_adm`, not
`reactor_create`), and your binding must pin it too. The platform module opens the real
microphone, and `reactor_create` takes its mode from an environment variable — a library
whose audience is scripts and servers must never let an env var put a live mic on the wire
because the model happened to declare a sendonly audio track.

Real devices belong in an **optional** helper module, off the mandatory import path:
`reactor_sdk.audio_devices` with `Speaker` / `Microphone`, installed via
`pip install "reactor-sdk[audio]"`. Mirror that split: the core binding has no media
dependencies, and the device helpers carry theirs.

---

## The seven scenarios, which are the real test suite

Every SDK ships the same seven examples, and they are parity requirements rather than
documentation: an example missing from a binding is a code path that binding has never
run. Every bug in this list was found by one of them and by no unit test, because a unit
test agrees with the fixture you wrote for it.

[`sdks/python/examples/`](../../../sdks/python/examples/) is the reference. Port the set,
keep the numbering, and keep each one teaching exactly one thing:

| # | Teaches | What it caught |
|---|---|---|
| 01 | Connect, send the model's first command, read the reply, count frames | Nothing arrives until the model's own minimum is met, and that minimum is per model |
| 02 | Upload a file, pass the `FileRef` into a command | — |
| 03 | Pause and resume a track | Nothing is generated while paused, which is only visible as a frozen frame |
| 04 | Publish a track and push tagged frames into it | A publish request nobody can decode is a request nobody answers |
| 05 | Two clients on one session, the second adopting it by id | A creator that leaves without disconnecting orphans the session |
| 06 | Request a clip and download it | Auth, readiness, and the container — three separate shipped bugs |
| 07 | Read the per-frame trailer: frame id, sender timestamp, `user_data` | A tag is dropped unless the far end declared that it reads tags |

What the set costs to learn the hard way:

- **A frame count proves something arrived, not that it was the right something.** Give the
  examples an opt-in window (`REACTOR_SHOW=1` over pygame/SDL in Python) and put the frame
  drawing in the one file they share. Everything else stays in the example, spelled out:
  a reader who has to open two files to understand one example is reading one file too many.
- **Ask for tracks by name** — `reactor.track("main_video")` — the way an app that knows its
  model does. Listing and filtering by kind or direction is for discovery, not for use.
- **A model name is `owner/name`.** A bare name resolves under `reactor/`, so it works by
  luck of ownership and answers 403 for anyone else's model. Write the owner in the example.
- **Publishing is what puts a sender behind the slot.** Pushing before it must raise, and
  a publish does not survive the session leaving ready.
- **Clip readiness is in media time, not wall clock.** The manifest appears once the
  recording passes the end of the chunk holding the window, and a snap clip's window ends
  at *now*, so its boundary chunk is always the open one — waiting before asking moves the
  target. The runtime's `predicted_ready_at_ms` is a wall clock plus media seconds, so it
  is only right for a model generating at real time; a model at a tenth of that reaches the
  boundary ten times later. Bound the wait on the session still being alive, not on a
  number: a clip becomes ready because the model keeps generating, so once the session is
  gone a 202 is a 202 forever.
- **The playlist is fragmented MP4, and the init segment is a comment line.**
  `#EXT-X-MAP:URI="…"` carries the `ftyp`/`moov`; a parser that skips `#` lines drops the
  one part that makes the rest readable and writes a file no player opens. Fetch it first,
  write it first.
- **A clip's segments can be presigned on another host.** The playlist needs the bearer
  token; a presigned URL *rejects* one rather than ignoring it. Send auth same-origin only.
- **Validate against published models in production. That is the bar.** It is the only
  place the whole path exists: the coordinator serving `/clips`, segments presigned onto
  another host, the codecs the fleet negotiates, the model contracts as deployed rather
  than as declared in a manifest. A binding that has only ever met a local runtime has
  not met auth, presigned URLs, or a session it does not own. Run the seven against a real
  model, with a real key, before claiming parity.
- **A local runtime is the fallback, for the two cases production cannot serve.** One: no
  published model has the shape a scenario needs — an input track, batched emissions, a
  track name you want to vary. Two: isolating a suspected binding bug from a platform one,
  where a local run is the control. `python -m reactor_runtime.serve` in a directory with a
  `reactor.yaml` runs a model from source. Treat a green local run as evidence about your
  binding, never as a passed scenario.
- **A failed production run is not automatically your bug.** Billing enforcement can close
  a session mid-run, and every symptom then wears a disguise: a clip that never becomes
  ready, a track that reports itself unpublished, a peer connection that only says
  `Disconnected`. Read the session's own reason before changing code.
- **A model may be on a runtime your binding cannot talk to.** The current runtime speaks
  `reactor_wire.v1` protobuf on the control channel; an older one parses that channel as
  JSON and drops what it cannot decode, so a request times out with nothing logged
  anywhere. Before blaming your binding, check which runtime the model runs.
- **Fixtures that you invented agree with you.** The Python clip tests passed for weeks
  against a playlist shape nothing serves. Copy the fixture from the code that builds the
  real manifest and say in the fixture where it came from.

---

## Testing

A binding lands with unit tests, and the seven scenarios do not substitute for them: those
need a live session, so they cannot gate a pull request, and a binding whose only proof
runs by hand is one whose next change breaks quietly. Two things the scenarios cannot
reach at all, so cover them here: every row of the refuse-do-not-fail-quietly table
raising the documented error rather than the language's default, and teardown in its
awkward shapes — destroy with callbacks still registered, destroy twice, destroy while a
frame is in flight.

- **Fake the library, not your own code.** Python's tests hand `get_lib()` a fake exposing
  the handful of symbols under test, with real C buffers for the string getters so the
  free path is exercised too.
- **Never hand a fabricated handle to `reactor_destroy`.** Tests that make a client look
  connected assign the handle directly; that integer looks exactly like a live pointer to
  the finaliser, and dereferencing it is a segfault — in an unrelated test, or after the
  run passes, depending on when GC happens. See `sdks/python/tests/conftest.py`, which
  guards this suite-wide and explains why per-test patching does not work.
- **Drive the real callback path** where you can, rather than a stand-in for it. A test that
  calls your dispatch helper directly proves the helper works and not that anything is
  wired to it.
- **Test that a removal actually removed.** When you delete an event or a method, assert
  both that registering raises *and* that nothing fires the old name.

---

## CI carries the binding, one job per language

Wire the SDK into CI as its own job, scoped to its own paths. One job per binding, so a
change to one does not build another: nobody editing the Python SDK should pay for a C++
toolchain, and a red C++ build on a Python-only PR teaches the team to ignore red.

- **Trigger on the binding's paths plus the shared ones.** `sdks/<lang>/**` for the
  binding, and `crates/**` plus the workflow file itself, because a core or ABI change is
  every binding's business. Skipping a binding whose FFI just changed is how a drifted
  declaration reaches a release.
- **Keep one aggregating job as the only required check.** `ci-complete` already exists
  for this: it `needs` every job, runs `if: always()`, and fails if any of them failed or
  was cancelled. That is what makes path-scoping safe — a required check that is skipped
  never reports, and the pull request waits forever. Add your job to its `needs` and to the
  result loop, or it is not actually gating anything.
- **Put the commands in `mise.toml`, not in the workflow.** `lint:<lang>` and `test:<lang>`
  tasks, aggregated into `lint` and `test`, so the workflow calls one thing and a
  contributor runs exactly what CI runs.
- **Expect the native library to be the slow part.** CI builds `libreactor_ffi` before the
  binding's tests can load it; cache it keyed on the toolchain lock, since the Rust cache
  holds C++ objects and reusing objects built by a different compiler is an ABI mismatch
  Cargo's fingerprint cannot see.

---

## Packaging and release

The constraint that shapes everything: **the package is useless without the native library**,
and building that needs a Rust toolchain plus a libwebrtc download.

- **One artifact per platform, with the library bundled. No source distribution.** Five
  platforms match what `reactor-webrtc` ships: linux x86_64/aarch64 (glibc 2.34+), macOS
  arm64 (11+) and x86_64 (13+), Windows x86_64.
- **Know how your ecosystem fails on an unsupported platform.** pip does not error — it
  walks back to an older release that installs anywhere and leaves the user on a different
  API, silently. Document the floor pin (`reactor-sdk>=1.0`) and put the platform table in
  the README.
- **Resolve the library in a fixed order**, and document it: an env var override
  (`REACTOR_FFI_LIB`), then next to the installed package, then a build in an enclosing
  checkout. That is what makes "run the installed SDK against a local build" possible.
- **The version is the release switch.** Bumping it in the manifest and merging to main is
  what publishes; everything else on main is a no-op.
- **Gate publishing on a variable your CI can actually see.** A kill switch read in a
  job-level `if` cannot see an environment-scoped variable — the environment is resolved
  after the condition decides whether the job runs — and that silently skipped a release
  that had already tagged and built. Check it in a step.

---

## Ship it as a stack, not as one SDK-shaped PR

A binding is thousands of lines and a review of thousands of lines is not a review. The
bugs that matter here are one-liners in the boring parts — a string freed twice, a callback
context released before teardown, a request whose reply nobody awaits — and those get found
in a diff someone can hold in their head. Every serious finding on this repo's SDK work
came from a reviewer reading a small diff, and none from reading a large one.

So slice it. Each PR does one thing, is green on its own, and leaves `main` working if the
rest of the stack never lands. A rough order, each of these a PR:

1. **Scaffold**: package manifest, directory layout, the `lint:<lang>` / `test:<lang>` tasks
   and the CI job — with nothing in it yet. Landing the toolchain before the code means the
   next nine PRs are reviewed by a green pipeline rather than by hope.
2. **FFI declarations** plus `check-abi-parity.py` taught about them. No object model.
3. **Client lifetime**: create, connect, disconnect, destroy, status and error events.
4. **Receiving**: tracks by name, frame callbacks, the trailer.
5. **Sending**: publish, push, unpublish, and the refusals that go with them.
6. **Commands and messages**, including the request/response correlation.
7. **Recording**: request a clip, download it, assemble it.
8. **Audio devices**, if the language has a story for them — optional, off the mandatory
   import path.
9. **The seven examples**, once there is enough SDK to run them.
10. **The version bump**, alone, because it is the release switch.

- **Each PR carries its own Linear ticket and its own tests.** A PR that adds a code path
  without the test that pins it is a PR that lands untested — the stack is not a promise
  to test later.
- **Do not stack a refactor under a feature.** Rename in its own PR, or the diff that
  matters disappears into the one that does not.
- **Rebase the stack when its base merges**, and re-run the affected scenarios: a binding
  rebased onto a changed FFI compiles and then fails at the call. `gt` handles the
  mechanics; the `pr-stack` skill covers getting a stack green and merged.
- **A stack is not an excuse to defer the parity bar.** The seven scenarios and the unit
  tests are conditions for the *last* PR of the stack, not for a follow-up nobody files.

---

## Before opening the PR

- [ ] `check-abi-parity.py` knows about your binding's declarations.
- [ ] Every heap string from the FFI is freed; the static one is not.
- [ ] Callback context outlives `reactor_destroy`, including the `-1` path.
- [ ] Control events reach the host loop; media stays inline.
- [ ] Every row of the refuse-do-not-fail-quietly table raises.
- [ ] Synthetic ADM is pinned and cannot be overridden by the environment.
- [ ] Device helpers are optional and off the mandatory import path.
- [ ] Teardown in examples is in a `finally` — a creator that goes away without
      disconnecting orphans the session, and the next run cannot start until it clears.
- [ ] README documents the platform table, the library resolution order, and rebuilding
      after `crates/` changes.
- [ ] The SDK's own `lint:<lang>` / `test:<lang>` tasks are in `mise.toml`, and CI runs
      them in a job scoped to `sdks/<lang>/**` plus `crates/**`, listed in `ci-complete`.
- [ ] A change to another SDK does not build yours, and a change to `crates/` does.
- [ ] This is one slice of a stack, not the whole binding: it does one thing, it is green
      on its own, and `main` still works if nothing after it lands.
- [ ] All seven examples exist, numbered as in `sdks/python/examples/`, and each has been
      run against a published model in production. A local runtime does not discharge this.

Repo conventions — Linear ticket, branch naming, DCO, commit messages — are in
[`CONTRIBUTING.md`](../../../CONTRIBUTING.md). Driving a stack to merge is the
`pr-stack` skill; opening a single PR well is `pr-workflow`.
