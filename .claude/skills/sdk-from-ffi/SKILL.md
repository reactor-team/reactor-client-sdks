---
name: sdk-from-ffi
description: >
  Build a new Reactor client SDK in another language on top of `libreactor_ffi` — Node,
  Go, Swift, Kotlin, C#, Ruby — or review a PR that adds or changes one. Use this when
  the user asks to "write the Node/Go/Swift SDK", "bind reactor-ffi from another language",
  "port the Python SDK to another language", "add a new SDK under sdks/", or asks how the FFI
  boundary, callback threading, string ownership, or wheel-style packaging is supposed to
  work for a binding. Also use it when a binding crashes on teardown, receives frames
  that never arrive, or silently sends nothing — those are the failure modes this
  methodology exists to prevent. Also when a handler that throws takes the process with
  it, a future never resolves after the client is destroyed, or a callback writes through
  a pointer teardown already freed. Also use it for the seven example scenarios every SDK
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
- **Do the same for `scripts/check-error-codes-parity.py`.** It's the sibling guard for
  the error-code hierarchy (below): every code in `crates/reactor-core/src/error.rs`
  needs a declared class in your binding too, and the script only knows to check a file
  once you add it to its `SDKS` table — one line, same shape as the existing entries.
- **If your binding gets its own page in the `docs` repo's `sdk-reference/`**, its error-code
  table is generated too (REA-5844) — `.github/scripts/sdk-docs-sync/render_error_codes.py`
  over there reads this repo's `error.rs` and `sdks/js/src/errors.ts` and needs nothing from
  you for the base table. It does need two things on the docs side: your own
  `sdk-docs-sync` instantiation (see that repo's `AGENTS.md`, "SDK Docs Sync" — a new `.conf`
  plus a thin workflow copying the existing shape), and, only if one of the handful of codes
  in `ACTION_HINTS` has a fix worth naming for your language (e.g. "call `reconnect()`" —
  something that names an actual method or package, so it can't live in `error.rs` itself),
  an entry under your language's key in that same dict. Nothing else changes there.
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

Two calls are outside that promise entirely — see *Teardown settles what it cannot cancel*
below, which is where a binding gets this wrong even after reading this invariant.

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
- Media (`Track.on_frame` for both video and audio) runs **inline on the FFI's delivery
  thread**, deliberately. Blocking there is the backpressure: the FFI keeps only the newest video frame while your
  handler runs. Hand frames to a queue instead and you trade a bounded drop for unbounded
  latency and memory.

---

## Lifetime, teardown, and the threads in between

The five above are what the header tells you. These are what the second binding cost on top
of it: every one was a review finding on the C++ SDK, and four of them end the process
rather than fail a call.

They are named in C++ terms because that is where they were found, and the shapes translate
without much effort. A panic escaping a goroutine ends a Go process as surely as an uncaught
exception ends a C++ one; a finaliser, a `defer`, a `Symbol.dispose` or a GC callback runs
on whichever thread dropped the last reference, which is the thread you were not thinking
about; and every language has something that can only be resolved once, so every language
can leave a caller holding it forever. Read each one for the shape, not the syntax.

### Teardown settles what it cannot cancel

`reactor_destroy` bounds *most* callbacks — it blocks until none is running, and after a 0
none will start. Two calls sit outside that promise, and the header says so for both:
`reactor_fetch_jwt` takes no handle, and `reactor_download_clip` is documented as outliving
the handle it was given. Their completions can arrive after the client is gone.

An operation of that kind must not live in whatever your teardown *frees*:

- freeing it while a detached task still points at it is a use-after-free. AddressSanitizer
  named ours on the download's progress callback, reading the freed object through
  `std::function::operator bool`;
- but leaving it out of teardown means the caller's future is never resolved, and they wait
  for the life of the process. That was the same PR's other finding.

One answer covers both halves: the client **owns** the operation as shared state, and the
FFI is handed a *ticket* carrying nothing but a weak reference to it. Teardown settles the
caller and drops its reference; a late callback locks the weak reference, finds nothing, and
returns having touched nothing. The ticket is the callback's own to free, so it is always
safe to read. Deregister on the way through, so teardown never finds an entry whose payload
has already moved on.

Say what actually happened in that error. A download whose client was destroyed is still
downloading — telling the caller the file may yet arrive is worth more than "aborted".

### Your event thread must survive its own handlers

Two ways a handler ends the process. Both have a test that reproduces them.

**A handler may drop the last reference to its client.** "On disconnected, throw the client
away" is an ordinary thing to write. Dispatched work holds a strong reference while it runs,
so that release lands the destructor on *your event thread*, mid-callback — and a teardown
that joins that thread is joining itself, which the standard reports by throwing, out of a
`noexcept` destructor, which is `std::terminate`. Fix it structurally rather than by
detecting it late: the queue and its stopped flag live in state the thread holds its own
reference to, the loop touches no `this` after running work, and stopping from that thread
detaches instead of joining.

**A handler may throw.** It is host code, and host code has bugs. An exception escaping your
event loop leaves the thread function with an uncaught exception — `std::terminate` again,
one typo in one status handler ending a healthy process. Catch at the loop boundary *and*
per handler: a loop over a snapshot of handlers otherwise aborts on the first throw, so one
caller's bug silences every other handler listening for that event. Report once per distinct
message, because a handler that throws on every event throws at the rate the events arrive.

### Decode before you claim the promise

A future settles once. If "settle" marks the operation done and *then* converts the payload,
a field of the wrong type throws where nothing can be settled any more: the fallback meant
to fail the call finds it already claimed, and the caller gets a broken promise, a hang, or a
segfault — never the typed error you documented. Convert and validate first, claim second.

The same rule at the other end. A **successful** completion whose payload will not parse is a
decode failure, not an empty object: substituting `{}` made `request_schema()` answer with a
schema declaring nothing, which no caller can tell from a model that declares nothing. An
*absent* payload — a null pointer, a completion with nothing to report — is a different
answer and still means `{}`.

### Validate what C cannot

Numbers cross the boundary as whatever the host had. A `double` can be NaN or an infinity,
and turning one into a duration panics — inside a detached task, which drops the completion
instead of firing it, which is a binding waiting forever for a callback that can no longer
come. Check before the spawn and answer through the completion, like any other refusal.
Decide what the range means while you are there: negative and infinity both mean "no bound",
a NaN is a caller bug, and a finite value too large for your duration type saturates.

### Synchronise callback state, and do not assume the symmetric fix

State touched from an FFI callback thread and from the caller's thread needs a decision per
field, not one policy for all of them:

- **Compare and store under the same lock.** Computing "did the token change?" outside the
  lock that guards it is a race inside the string's own buffer, not merely a stale answer.
- **A mutex where the callback does not run under it; an atomic where it does.** The C++
  speaker keeps `running`, its device handle and its format under one mutex — its render
  callback takes no lock, so holding that mutex across device teardown is safe. The
  microphone cannot: closing a capture device *waits for the capture callback*, so a callback
  taking the same mutex would wait for the thread waiting for it. Atomic there, checked so a
  block captured while stopping is dropped rather than pushed. The symmetric fix deadlocks.
- **A read that raced an invalidation must not become the cache.** Reading the FFI without
  the lock is right — a JSON parse is not something to hold a media mutex across — but an
  event can invalidate while that read is in flight, and storing it afterwards puts the older
  answer back with nothing left to invalidate it, so a newly declared track stays invisible.
  Take a generation counter with the read; store only if it has not moved.
- **In flight is its own state.** A publish asked for and not yet answered is not published:
  there is no sender behind the slot, so a push in that window is taken by the FFI and
  dropped. Counting it as published reintroduces exactly the silent failure the *Refuse; do
  not fail quietly* table below exists to prevent; counting it as nothing tells a caller who
  just called `publish()` to call `publish()`. Keep the third state, and say "wait for the
  future" in the refusal.

### Order is part of the contract

`tracks()` is indexed by position, and every SDK promises that position is the order the
session declared them in. Collecting the declared tracks into a name-keyed map sorts them
alphabetically and silently renumbers what `tracks()[0]` means for every caller. Keep the
sequence and look up by scanning it — a session declares a handful of tracks, not a
dictionary's worth.

---

## The object model, which every SDK should agree on

Users move between our SDKs. Same concepts, same names, idiomatic spelling.

**`Reactor`** — the client. Connection, commands, recordings, uploads, and access to tracks.
No media methods on it: see below.

**`Track`** — a named media slot the *model* declares, with a `kind` (video/audio) and a
`direction` (sendonly/recvonly). One type for all four combinations, because the operations
are the same operations. It carries `push_frame`, `on_frame`, `on_raw_frame`, `publish`,
`unpublish`, `pause`, `resume`, `published`, `paused`, `mid`.

**One frame API for both kinds.** Expose `Track.on_frame` and `Track.push_frame`
for video and audio; do not add separate `on_audio` / `push_audio` methods or aliases.
`Track.kind` determines the media type: video handlers receive video frames and audio
handlers receive PCM audio frames. Keep the language's native typing: C++ overloads
`on_frame` for `const VideoFrame&` / `const AudioFrame&` and `push_frame` for `Bytes`
with dimensions / `Samples` with sample rate and channel count. Validate typed handlers
and buffers against the declared kind, and name the expected type in a mismatch error.
The FFI's separate video/audio callbacks and push functions stay internal; this is a
public object-model contract, not an ABI rename. Migrate device helpers, examples,
tests and docs together when removing the old audio-specific methods.

**A list of tracks with filters** — `tracks.with_kind(...)`, `.with_direction(...)`,
`.one()`, chainable in either order, so a caller can say which track they mean without
hardcoding a name.

**Errors** — one flat list of codes shared with the core (`crates/reactor-core/src/error.rs`),
one typed class per code (`scripts/check-error-codes-parity.py` reports the current count
and enforces it in CI, so take it from there rather than from a number written down
somewhere), each carrying `code`, `message`, `recoverable`, `status`, `operation`,
`retry_after_ms`. Recoverability is **derived from the code**, never stored per-site, so
two SDKs cannot disagree about whether a timeout is worth retrying. The same object is
what an `on_error` event delivers and what a failed call raises.

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
| A typed handler or push buffer incompatible with `Track.kind` | never fires or sends the wrong media | reject, naming the expected frame or buffer type |
| Raw video frame bytes whose length ≠ `width * height * 4` | reads out of bounds | raise, naming both numbers |
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
- **Verify a fix in both directions.** A test that passes with your change and would have
  passed without it records an intention, not a defect. Every lifetime finding above has a
  test that fails on the old code — `Subprocess aborted`, a segfault, a two-second wait that
  never resolves — and that failure is the only evidence the fix is the fix. Stash the
  change, run the test, put it back.
- **Run the suite under a sanitizer for anything about lifetime.** A callback writing through
  a freed pointer is invisible in a passing run: same tests, same assertions, no crash.
  AddressSanitizer turned ours into one line naming the callback. Worth a scratch build even
  where CI has none.
- **Make a race deterministic instead of hoping for it.** The fake is the place: have the
  library's own read fire the event that invalidates the cache, and take the answer *before*
  running that hook so what comes back is the stale one. A race you can reproduce on demand
  is a regression test; one you cannot is an anecdote.

---

## Endurance suites run under a debugger, or a native crash is undebuggable

The intermittent segfault an endurance suite exists to hunt dies on a Rust/libwebrtc thread,
so the language runtime's own crash reporting never shows the one stack that matters
(faulthandler prints `<no Python frame>`; swift-testing records nothing at all — the process
simply dies). Run the suite's process as the debugger's **direct inferior** from day one — a
debugger does not follow its inferior's grandchildren, so wrapping `mise run`/`uv run`/
`swift test` captures nothing — and on the stop, dump *every* thread's backtrace. Then a
separate `if: failure()` step lifts the dump into the suite's results artifact as a
`native-crash-diagnostics.json` (ANSI-stripped, capped, carrying the signal, run URL/SHA, a
reproduce command, and written reading instructions in its own `what_is_this_file`).

On Linux (Python job) this is gdb running pytest: script written to a file with `printf` and
`-x` (an `if` block split across chained `-ex` args silently misbehaves), exit code kept
honest by `if $_isvoid($_exitcode)` / `quit 139` / `else` / `quit $_exitcode` — `$_exitcode`
is void exactly when the inferior died on a signal. On macOS (Swift job) it is lldb, and the
macOS specifics are the trap:

- **The inferior is `swiftpm-testing-helper`, not the test bundle.** On macOS the
  `<Package>PackageTests.xctest` product is a Mach-O *bundle* — `cannot execute binary
  file`. What `swift test` actually spawns is the toolchain's helper executable (watch a
  live run's `ps` to see it), at `$(dirname $(xcrun --find swift))/../libexec/swift/pm/
  swiftpm-testing-helper`; it dlopens the bundle and runs in-process, so debugging it *is*
  debugging the tests. Invoke it with `swift test`'s exact arguments:
  `--test-bundle-path <bundle-binary> --filter <ModuleName> <bundle-binary>
  --testing-library swift-testing` (the bundle path really appears twice; the filter
  matches the full test ID `<Module>.<Suite>/<test>`, so the module name selects the
  whole target).
- **Set `DYLD_FRAMEWORK_PATH`/`DYLD_LIBRARY_PATH` via lldb's `target.env-vars`, not the
  environment.** The bundle @rpath-links `Testing.framework` from the Xcode platform's
  Developer dir; `swift test` papers over that with DYLD_* env vars, but SIP strips DYLD_*
  when a restricted binary (lldb) is spawned, so inheritance silently loses them and the
  helper dies dlopening the bundle. Non-DYLD vars (API keys, durations) inherit fine.
- **lldb never propagates the inferior's exit code**, and the quiet mode a clean log wants
  (`-b -Q`) also suppresses the `Process N exited with status = N` line people parse. Have
  the lldb script print its own marker after `run` — a one-line `script import lldb; ...`
  emitting `ENDURANCE_INFERIOR_STATE: exited exit_status=<n>` vs `...: stopped` — and let
  the run block translate: normal exit passes the code through, `stopped` means a native
  fault and exits 139. The crash's all-thread backtrace comes from a stop-hook
  (`target stop-hook add -o ... -o "thread backtrace all"`); it also fires once at launch
  (dyld entry SIGSTOP), so both the live log and the diagnostics step take the *last*
  banner, and the fault is named by the faulting thread's `stop reason = EXC_BAD_ACCESS`
  line — macOS's analogue of gdb's `Program received signal SIGSEGV`.

---

## Endurance and leak tests, past what unit tests reach

Unit tests and the seven scenarios are both short-lived — they cannot see a handle, a
callback, or an OS resource that leaks a little on every cycle. This suite runs the binding
for minutes to hours instead and watches whether the numbers keep climbing. Python's
(`sdks/python/endurance-tests/`) is the reference; port it file-for-file rather than
redesigning it, the way the object model and the seven scenarios get ported.

Several scenario shapes, all needed, because each finds leaks the others cannot:

- **`test_lifecycle_churn`** — a fresh client per full connect → publish → subscribe →
  command → unpublish → disconnect → close cycle. The one that exercises the native handle's
  whole lifetime (create/destroy), so a leak tied to *tearing down* a client only shows up
  here.
- **`test_session_churn`** — one client, connected once, that repeats
  publish → subscribe/unsubscribe → command → unpublish many times without ever
  disconnecting. The one for a leak in a single *operation*, which a coarser connect/close
  cycle dilutes into noise.
- **One scenario per operation pair worth isolating** (`test_publish_churn`:
  publish → unpublish, nothing else in the loop; `test_pause_resume_churn`: pause → resume on
  a recvonly track, nothing else) — session-churn already mixes publish/frame/command/unpublish
  every cycle, which means a leak specific to just one of those operations shows up as a small
  contribution to a trend several operations are all feeding, not a clear signal on its own.
  Splitting it into its own scenario turns "some metric drifted a little in the broad mix" into
  "publish-churn failed, pause-resume-churn passed" — the report itself names the culprit.
  Cheap to add: same fixture, same metrics, just a narrower loop body — add one per
  operation you'd want isolated, not just the two above. These also run *far* more iterations
  per minute than session-churn (no frame-pump wait, no command round trip beyond the
  operation itself), so they reach a stable trend faster too. **This is not hypothetical** — the
  first real CI run of `test_pause_resume_churn` against production found a genuine, linear,
  no-plateau RSS climb (+50% over 5 minutes) that `test_publish_churn` (same run, same process,
  same fixture) did not show at all. Folded into `session-churn`'s broad mix, that signal would
  have been diluted into "RSS grew a bit, inconclusive" instead of naming pause/resume outright.
- **`test_video_publish_steady` / `test_audio_publish_steady`** — the opposite shape from every
  scenario above: publish once (video or audio) and hold it, streaming continuously for the
  whole run, no pause/unpublish/reconnect at all. Every other scenario here is *churn*
  (repeatedly doing and undoing something); a leak tied to *elapsed streaming time or frame/
  chunk count* rather than to churn count would not necessarily show up in a churn scenario even
  run forever, so this steady-state shape is a distinct, necessary fourth category, not a
  variant of the others. Keep audio and video as separate scenarios, not one parameterized over
  both — they share nothing below `push_frame()` (separate adapters, separate encoder/decoder
  threads in the native runtime), so a leak in one is not evidence about the other, and a report
  that names `audio-publish-steady` specifically is more useful than one that says
  `media-publish-steady[audio]`.

**Extensible by design, not by accident**: factor the two things every scenario repeats —
the RSS/CPU/thread/fd/handle-count checks at the end of the loop, and the `finally`-block
report-writing/raise-on-`FAIL` sequence — into two shared calls (Python:
`trends.standard_resource_metrics(samples, live_clients_baseline_zero=...)` and
`report.finish_and_check(...)`) that every scenario file calls instead of repeating. A sixth
scenario file should be "write the loop body that does the one thing you want to isolate, call
these two," not another ~150-line copy with a different middle. A scenario's *own* invariant
(session-churn's `pending_completions` check, publish-churn's `track.published` check,
pause-resume-churn's `paused_tracks` check) still lives in that scenario's own loop — it is
specific to what that scenario exercises, not generic resource accounting, so it does not
belong in the shared helpers.

Track RSS, CPU as **two separate questions** (work actually done — from a delta between
samples, never a raw cumulative counter, which "grows" by construction and proves nothing —
and OS-reported busy-percent, which can read over 100% with multiple native threads), thread
count, and fd/handle count. Add whatever your language exposes for live-object or
orphaned-callback counts (Python's `_LIVE_CLIENTS`/`_ORPHANED_CALLBACKS`) if it has an
equivalent; note in your README if it does not, rather than silently having a smaller suite.

**`num_fds` always gets the trend-based check (`assert_no_sustained_growth`, with
`use_median=true`, exactly like `num_threads`), never an exact `assert_never_grows`/
`assertNeverGrows` — one rule, no per-scenario or per-language exception.** This used to be
a per-scenario opt-in (Python's `fds_exact`, Swift's `fdsExact`) on the theory that some
scenario's fd teardown was provably synchronous with its own cycle boundary, validated by
that binding's own real runs. It wasn't a stable theory: Python's lifecycle-churn validated
`fds_exact=True` across its own runs, but real CI runs on C++ and then Swift's *own*
lifecycle-churn — the identical scenario, same shared native FFI/WebRTC layer — each
independently caught `num_fds` take a one-cycle step (e.g. 13 -> 15) that settled right back
down before the run ended, not a leak, just native socket-teardown timing landing a sample
mid-flight. Two out of three bindings falsified the "provably synchronous" premise the third
one's own validation seemed to support — that is the whole reason this is a flat, no-exceptions
rule now rather than "validate it for your own binding first": a check that a leak can pass on
one binding and fail falsely on another because of that binding's own real-run history isn't
one worth keeping a language-specific escape hatch for. `assert_no_sustained_growth`'s
median-backed trend already tolerates that same one-cycle blip (see `num_threads`'s identical
reasoning below) without giving up on catching a real leak — there is no scenario where the
exact check would catch something the trend check misses. Do not add the parameter back for a
new scenario or a new binding, however tempting a "well *this* teardown really is synchronous"
argument looks — it has already looked that way twice and been wrong both times.

**A leak already present on the very first cycle needs `assert_always_zero`, not a
baseline-relative "never grows".** A trend check compares against the baseline it first
sees, so a value that is already leaking on cycle 0 becomes the accepted normal and the
check passes forever. Anything that should start at zero and stay there — orphaned
callbacks, for one — gets the absolute check.

**Sustained growth is last-third vs *middle*-third, not vs first-third.** A one-time
ramp to a new steady-state (a buffer or pool growing once, not an unbounded leak) can land
anywhere in the run; comparing last-third to first-third makes *where the ramp happened to
fall* the thing that decides pass/fail, not whether growth continued after it. Comparing
last-third to middle-third asks "did it keep growing after that" instead, so a ramp that has
already plateaued by the back third reads as flat. Full reasoning and the accepted
trade-off (a real leak still in its early, slow-accelerating phase near the end of a short
run can misread as flat) belongs in `assert_no_sustained_growth`'s own docstring in
`helpers.py` — read it there, don't re-derive it. This was found by two otherwise-similar
CI runs producing opposite verdicts on the same total growth; do not assume your first
green run means the check is well-calibrated.

**A `Timeline` of a handful of evenly-spaced checkpoints across the run, in every report,**
is what actually shows *why* — flat, one-time ramp, or still climbing — instead of collapsing
a whole run into two or three numbers nobody can picture the shape of. Compute it from the
samples you already collected; it costs nothing extra to gather.

**Put the midpoint in the Results table itself, not only in the Timeline.** Each metric's
`start`/`end` are already the first-third/last-third means (post-warmup, not raw first/last
samples — see above), so `start → end` alone routinely reads as real growth on a perfectly
healthy run: a buffer or pool reaching its steady-state size is a one-time step early on that
moves `end` up relative to `start` without ever being a leak. Add the middle-third mean
(`mid`, the same number the pass/fail check already computes internally) as its own column,
plus the `mid → end` delta — that delta is the actual quantity the verdict is based on, so
showing it directly is what lets a reader conclude "flat from the midpoint on, so not a leak"
from the table alone, without reading the surrounding prose or reaching for the Timeline. A
report a human skims in 10 seconds should not need the full checkpoint list to answer "is this
actually still climbing".

**A report needs to say what it is, on its own — it gets read standalone,** in a GitHub
Actions Job Summary or a downloaded artifact, days after the run and without the source open
next to it. Two fields worth carrying for that: a one-line, hand-written `description` of what
the scenario's loop actually does (rendered right under the title) — the `test_name` slug
alone (`publish-churn`) does not say "no frames, no commands" or distinguish it from
session-churn's broader mix, and that distinction is the whole point once you have more than
two or three scenarios; and the SDK version under test (`reactor_sdk.__version__` or
equivalent), rendered above the commit SHA — the commit says what code ran, the version says
what a human comparing reports across releases actually wants to filter on. Neither belongs in
the reporting module itself if it would need importing your binding's own package (see the
FFI-free constraint below) — take them as plain string parameters from the scenario, the same
way `sdk`/`test_name` already are.

**Reporting: one shared in-memory result, three renderings, written regardless of outcome.**
A single result object (Python's `RunResult`, built by `report.py`) renders to JSON, Markdown,
and plain text, so the three formats cannot drift apart the way hand-written duplicates
would. Write all three to disk even when a scenario fails or errors — a report that only
appears on success is useless for the run you actually need to debug. Keep this module free
of your binding's own import chain (`report.py` deliberately does not import `helpers.py`) so
its own unit tests need no live service or built native library.

**Live output: a compact status block by default, not a row dumped per cycle.** Reprint one
block in place on an interval (Python: every 30s) so a long run's log stays readable; gate the
old per-cycle table behind an opt-in env var (`ENDURANCE_VERBOSE=1`) for when you actually
want the detail, and stream it live rather than only at the end — a run that gets killed
partway should still have shown something.

**Destruction-order bugs are a second, independent finding here — in both C++ and Swift,**
neither one copying the other. A stack-local client destroyed before a stack-local
subscription (C++ destroys locals in reverse declaration order; Swift ARC releases on
whichever scope drops the last reference) unregisters the handler before the client closes,
silently skipping the one thing the cycle exists to exercise: does close() clean up a still-
registered handler. Fix it by controlling the moment explicitly (an owning pointer you
`.reset()`/an explicit `withExtendedLifetime`) rather than relying on declaration order to
line up with teardown order. Wrap each cycle's body so a mid-cycle failure still
disconnects/closes before the error propagates — the same shape the *Refuse; do not fail
quietly* invariant requires elsewhere.

**CI wiring**: `workflow_dispatch` is the direct entrypoint (this suite is long, noisy, and
reads a trend rather than a single right-or-wrong answer — it does not *gate* a PR or a
release the way the seven scenarios and integration-tests do), one job per SDK in
`endurance-tests.yml` gated on a `sdk` choice input so adding a language later is "add a
choice plus a job," not a restructure.

**It also runs itself, automatically, after every real release** —
`endurance-tests-on-release.yml` watches `release-<lang>.yml` via `workflow_run` and
dispatches `endurance-tests.yml` for that language once the run's own release-publishing job
(the one that actually calls `gh release create`/`gh release edit --draft=false` — its `name:`
differs per language, e.g. Python's is "Publish GitHub release", C++'s is "Release") reports
`conclusion == success`. Checking that job specifically, not the workflow's own top-level
`conclusion`, is load-bearing: `release-<lang>.yml` also completes "successfully" for a
pull-request build or a `dry_run` `workflow_dispatch`, neither of which publishes anything —
trusting the top-level conclusion alone would dispatch an endurance run after every green PR
build of the release workflow, not just an actual release. Duration comes from the
`RELEASE_ENDURANCE_DURATION_MINUTES` repo variable (Settings → Secrets and variables →
Actions → Variables), not a literal, so it can be retuned without a file edit — same pattern
`release-python.yml`'s `PUBLISH_TO_PYPI`/`release-cpp.yml`'s `PUBLISH_CPP_RELEASE` kill
switches use. **Wiring a new binding's endurance suite in means adding a job to this file
too**, not just to `endurance-tests.yml`: add `"Release <Lang> SDK"` to the `workflows:` list,
copy one of the existing per-SDK jobs, and point its API check at that SDK's own
`release-<lang>.yml` release-job name (read it off that file — don't assume it matches another
SDK's).

**Matrix one job per scenario, discovered rather than hand-listed.** Scenarios used to run
sequentially inside a single SDK job — fine with two of them, a liability once a suite grows
past three or four (duration_minutes × scenario_count, past an hour on a 20-minute
duration_minutes run with six scenarios). A `setup` job globs the scenario test files
(`find endurance-tests/tests -name 'test_*.py'`, piped through `jq -R -s -c 'split("\n") |
map(select(length > 0))'` into the compact JSON array `strategy.matrix` needs) and the SDK's
own job matrixes over that output (`fromJSON(needs.setup.outputs.scenarios)`) instead of a
hand-maintained list or count — adding a scenario file is then "add the file," full stop, not
also bumping a separate `SCENARIO_COUNT` elsewhere in the workflow, a manual-sync step the
scenario count previously needed (harmless so far, but exactly the kind of thing that goes
stale silently). `fail-fast: false` on the matrix, so one scenario's real
leak or flaky connect doesn't cancel the others mid-run. Each matrix job's own `timeout-minutes`
only needs to cover *one* scenario's duration_minutes plus setup, not every scenario's — a
smaller, simpler number than the old sequential math, computed the same way (see the workflow's
own comment on why this can't be a `${{ }}` expression: GitHub Actions has no arithmetic
operators). `actions/upload-artifact` needs a scenario-suffixed `name:` (`endurance-test-
results-${{ matrix.scenario }}`) — two matrix jobs uploading the same artifact name in one run
is a hard error, not a merge — so this trades the old single combined zip for one per scenario;
fine as long as whoever consumes them expects that. The job-summary step needs no change at
all: GitHub renders per-job `$GITHUB_STEP_SUMMARY` writes as separate sections on the run's one
Summary page, in job order, so matrixing already gives the same "every scenario's report is
readable from the Summary page" property the old single job had, just split by scenario
instead of concatenated top to bottom in one job's summary.

`if: always()` on both the job-summary step (render the scenario's own `*-report.md` into
`$GITHUB_STEP_SUMMARY`, so a failed run's shape is visible without downloading anything) and
the `actions/upload-artifact` step (upload `endurance-results/` even on failure — that's the
run you need most). `concurrency` global rather than per-ref (`group: endurance-tests`,
`cancel-in-progress: false`), because every scenario runs against one real shared
backend/quota and two *runs* racing it at once makes both runs' trends noisier — the whole
point of the suite; this only serializes separate runs, not the scenario matrix inside one
run, which is the whole point of matrixing it. Each scenario process paces its own session
creation independently (the same `paced_connect` integration-tests/conftest.py already has,
comfortably under quota per-process), so N scenarios running at once is a smaller version of
the bet ci.yml's integration-test jobs already made running every SDK concurrently instead of
chained — watch the first real dispatch of a newly-matrixed suite for 429s before assuming
that bet holds here too, same as that change did. A `run-name:` that names which SDK the run
is for, since every run otherwise shows the same generic workflow name in the Actions list.

**Run the suite under gdb from day one — a native crash in a managed-language suite is
undebuggable otherwise.** The intermittent segfault this suite hunts dies on a Rust/libwebrtc
thread, so all faulthandler ever prints is `Fatal Python error: Segmentation fault` and
`Current thread ... <no Python frame>` — the one thread whose stack matters is the one whose
stack you never get. Wrap pytest as gdb's *direct* inferior
(`gdb -batch -x pytest-under-gdb.cmds --args .venv/bin/python -m pytest ...`), never via
`mise run`/`uv run`: gdb does not follow spawned grandchildren, so wrapping the task runner
captures nothing. Traps, each hit for real while wiring this: gdb is not in the ubuntu-latest
image (apt-install it, `apt-get update` first — the image's snapshot index goes stale and 404s
on moved archives); an `if` block split across chained `-ex` args silently takes the crash
branch even on clean exits, so write the script to a file with `printf` and `-x` instead
(verified against ubuntu-24.04's gdb); keep the step's exit code honest with
`if $_isvoid($_exitcode)` / `quit 139` / `else` / `quit $_exitcode` — `$_exitcode` is void
exactly when the inferior died on a signal, so a crash is 139, a test failure is pytest's own
code, and green stays green; and `tee` gdb's output, but only after `set -eo pipefail` at
the top of the run block: this repo's workflow `run` steps render as `bash -e` with no
pipefail (actions' own steps have it; yours do not), and without it the pipeline reports
tee's exit code — a caught SIGSEGV has already surfaced once as a green run (34888100192),
skipping the failure-gated diagnostics step entirely. Then a separate `if: failure()` step
lifts everything from the `received signal` line — both of gdb's stop shapes, `Program
received signal` (main thread) and `Thread N "name" received signal` (any other thread),
because the crash this setup hunts lands on an FFI host thread — onward into
`endurance-results/native-crash-diagnostics.json` —
ANSI-stripped, capped, carrying the signal, run URL/SHA, a reproduce command, and written
instructions for reading the dump — so it rides the artifact zip the suite already uploads and
whoever debugs the crash months later, human or agent, starts from the faulting thread's stack
instead of re-deriving this whole setup (the JSON's own `what_is_this_file` field says all of
this in-file). Crash-accelerating env (`MALLOC_PERTURB_=165`, `RUST_LOG=info`) belongs on a
*hunting* branch only, not in the committed workflow: the suite's whole value is measuring
normal conditions, and an allocator that scribbles freed blocks is not one.

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
  contributor runs exactly what CI runs. Anything longer than a line or two belongs in
  `scripts/*.sh` with a `bash` shebang: a task's `run` is handed to `/bin/sh`, which on a
  Debian runner is dash and refuses `set -o pipefail`, so a gate written that way passes on
  a laptop and dies on its first line in CI.
- **A guard CI does not run is not a guard.** Hang it off the task CI already calls, or add
  the step — but check which. Ours ran locally for a day while CI never called it, and the
  reason it was not a step was a token without `workflow` scope, not a decision.
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
- **A static library exports its private dependencies whether you meant to or not.** Linking
  the native library `PRIVATE` still writes it into the exported link interface — as an
  absolute path to *your* build directory, which exists nowhere for whoever unpacks the
  archive, while the copy the package ships goes unused. Export a named target the installed
  config defines relative to the package instead, and give every other private dependency
  the same treatment: a `Threads::Threads` the config never looks up fails a consumer's
  configure on an imported target nobody created. This bit us twice, on two different
  targets.
- **On macOS the library carries its own load path.** Cargo writes the absolute one from the
  build machine, so a package can link correctly and still send the loader to a directory
  that only ever existed on CI. Rewrite the installed copy's id to `@rpath/…` at install
  time. ELF needs none of this — rustc writes a plain SONAME.
- **Prove the archive by relocating it.** Install, *move the tree*, delete the native
  library's build directory, and only then build a consumer against it. Checking an install
  tree in place cannot see either failure above, because on the machine that produced it
  every baked-in path still resolves. Link every target the package exports while you are
  there: ours checked the main library and missed the optional audio one for exactly that
  reason.
- **The version is the release switch.** Bumping it in the manifest and merging to main is
  what publishes; everything else on main is a no-op.
- **`client_info.sdk_version` must be your binding's published version, not `reactor-core`'s.**
  `ReactorOptions::new()` defaults `sdk_version` to `CORE_VERSION` (`reactor-core`'s own crate
  version, i.e. the workspace version) — a Rust-internal number the coordinator has no reason
  to see. `reactor-ffi`'s `create_impl` (`crates/reactor-ffi/src/lib.rs`) never overrides it, and
  `reactor_create`/`reactor_create_with_adm` don't even take a `sdk_version` argument at the C
  ABI, so **every FFI-based binding today reports the workspace version, not its own package
  version** (confirmed for Python and C++; the same gap hits any new binding built from this
  skill until the FFI boundary grows a parameter for it). `sdk_type` has the same flattening —
  `create_impl` hardcodes it to `"ffi"` for every language, so the coordinator can't even tell
  Python and C++ apart. Fixing this means threading a version (and ideally a language-specific
  `sdk_type`) through the FFI call, not something a single binding can patch on its own — flag
  it rather than silently shipping another binding into the same gap. (This is exactly the bug
  `sdks/js` had on its own wasm-bindgen path: it defaulted the same way, fixed by having the JS
  package hand its own `package.json` version to the binding — see that PR for the shape of the
  fix, though the FFI boundary needs a wire change the wasm one didn't.)
- **Gate publishing on a variable your CI can actually see.** A kill switch read in a
  job-level `if` cannot see an environment-scoped variable — the environment is resolved
  after the condition decides whether the job runs — and that silently skipped a release
  that had already tagged and built. Check it in a step.

---

## Two more release gates: CHANGELOG.md and a real integration-tests suite

Both are things a version bump can sail past silently if nothing checks for them — add
both when you wire up the new binding's `release-<lang>.yml`, not after the first release
ships without one.

- **CHANGELOG.md, and CI fails the release without it.** Start the file empty, in [Keep a
  Changelog](https://keepachangelog.com/en/1.0.0/) format, with a bare `## [Unreleased]`
  heading, from the SDK's very first commit — before there is anything to release, so the
  habit exists before the pressure to skip it does. In `release-<lang>.yml`'s
  `detect-version` job, right after the version-diff step, add a step that runs only when
  a bump was detected and fails unless the CHANGELOG has a `## [<version>]` heading
  matching the bumped version — `grep -qF "## [${VERSION}]" sdks/<lang>/CHANGELOG.md`, see
  `release-js.yml`/`release-python.yml`/`release-cpp.yml` for the exact shape. It writes
  nothing itself: the entry still has to be added by hand, in the same PR as the bump. Then
  point your release notes at it ("See `sdks/<lang>/CHANGELOG.md` for what changed"), same
  as the other three.
- **A real integration-tests suite, against a live model, gating both CI and release.**
  Unit tests and the seven scenarios above don't reach the wire — this is the one thing
  that actually drives the real client against a real `reactor/echo` session end to end.
  `sdks/js/integration/` (Playwright) and Python's suite are the template. Wire it in
  twice: as a required job in `ci.yml` (added to `ci-complete`'s `needs`, so it gates every
  PR) and again inside `release-<lang>.yml`, ahead of the `release` job, so a version bump
  can't ship without this exact commit having passed it. Skip it there on `pull_request`
  events specifically — `ci.yml`'s own run already covers the same commit, and echo's
  session-per-minute quota doesn't have room for both workflows racing it at once; that
  collision happened in practice (see `release-js.yml`'s and `release-python.yml`'s own
  notes on the step). Nothing publishes off a `pull_request` event anyway, so skipping it
  there loses no coverage.
- **Integration-test retries: one flat rule, `scripts/retry.sh`, encapsulated in the task
  itself, no per-language exception.** A live model's shared session-creation quota, its
  capacity pool, or a session/SDP poll can all fail transiently without any bug in your
  binding — that's what an integration-tests suite is exposed to that a unit test never is.
  Wrap only the *actual test-invocation line* of your `test:<lang>:integration-tests` mise
  task (Python/C++/JS: the last entry of its `run` array; Swift: inside
  `scripts/swift.sh`'s `integration-tests`/`integration-tests-ios-simulator` cases, since
  that task just delegates to the script) with `scripts/retry.sh` — never the build/cmake/
  cargo steps ahead of it, so a retry never re-pays for a build that already succeeded.
  This makes `mise run test:<lang>:integration-tests` itself retry, everywhere it's called
  — `ci.yml`, every `release-<lang>.yml`, and a contributor running it locally — with
  nothing for any caller to remember to wrap. A first pass wrapped the CI *workflow step*
  instead (`run: scripts/retry.sh mise run test:<lang>:integration-tests`), which reviewer
  feedback on PR #193 pushed back on for good reason: it left every direct caller of the
  same mise task (all four `release-<lang>.yml` files) to independently remember to wrap
  their own copy too — exactly the kind of drift this rule exists to prevent, and Codex
  review on that same PR caught it happening within the same PR. Don't reintroduce that
  shape; the task should be robust on its own, not depend on every caller remembering to
  wrap it.

  Not a framework-specific retry either (`pytest-rerunfailures`, Playwright's `retries`,
  Catch2/swift-testing have neither) — that had already drifted once, separately: Python's
  old `--only-rerun RateLimitedError` caught only the rate-limit case, missing the capacity
  and timeout ones, and Swift's own connect-retry comment claimed to cover capacity when
  its code didn't. One script, unscoped by exception type, called from exactly one place
  per binding. If `scripts/retry.sh`'s policy (4 attempts, 15s/30s/60s backoff) ever needs
  to change, change it there; don't add a parallel mechanism next to it.

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
10. **The integration-tests suite**, wired into `ci.yml` and `release-<lang>.yml` as
    described above — its own PR, once there is enough SDK to run it against a live model.
11. **The version bump**, alone, because it is the release switch — and because
    `CHANGELOG.md` needs its own entry, this is also the PR CI checks that entry exists.

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
- [ ] `check-error-codes-parity.py` knows about your binding's error file.
- [ ] If your binding gets its own `docs` reference page, that repo's `sdk-docs-sync` and
      `render_error_codes.py`'s `ACTION_HINTS` (if applicable) know about your language too.
- [ ] `client_info.sdk_version` the coordinator sees for this binding matches its own published
      package version — not `reactor-core`'s `CORE_VERSION` default. See *Packaging and
      release* above; today's FFI boundary doesn't expose a way to set this, so if your PR
      doesn't add one, say so explicitly rather than let it pass silently.
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
- [ ] A real integration-tests suite exists (`sdks/js/integration/`-style, against a live
      model), required in `ci-complete`, and run again inside `release-<lang>.yml` ahead of
      the release job — skipped there only on `pull_request`.
- [ ] `sdks/<lang>/CHANGELOG.md` exists from the first commit (empty, `## [Unreleased]`),
      and `release-<lang>.yml` fails a version-bumped release that has no matching
      `## [<version>]` heading in it.

Repo conventions — Linear ticket, branch naming, DCO, commit messages — are in
[`CONTRIBUTING.md`](../../../CONTRIBUTING.md). Driving a stack to merge is the
`pr-stack` skill; opening a single PR well is `pr-workflow`.
