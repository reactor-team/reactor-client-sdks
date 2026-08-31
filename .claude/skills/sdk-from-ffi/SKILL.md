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
- Media (`on_frame`, `on_audio`) runs **inline on the FFI's delivery thread**, deliberately.
  Blocking there is the backpressure: the FFI keeps only the newest video frame while your
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
- [ ] `check-error-codes-parity.py` knows about your binding's error file.
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

Repo conventions — Linear ticket, branch naming, DCO, commit messages — are in
[`CONTRIBUTING.md`](../../../CONTRIBUTING.md). Driving a stack to merge is the
`pr-stack` skill; opening a single PR well is `pr-workflow`.
