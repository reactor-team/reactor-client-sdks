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
  methodology exists to prevent.
---

# Building an SDK on `reactor-ffi`

The Python SDK is the first binding, and everything below is what it cost to arrive at.
Read it before writing the second one: none of these are style preferences, and most were
found by shipping the mistake first.

The shape is always the same. A Rust core (`crates/reactor-core`) does the protocol and
WebRTC; `crates/reactor-ffi` exposes it as 24 C functions; your SDK is a binding over
those plus an object model that hides them. **You are writing the object model, not a
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

**3. Know which strings you own.** `reactor_status` returns a `const char *` static literal
— do not free it. `reactor_session_id`, `reactor_tracks`, `reactor_paused_tracks` and every
`error_json` return heap memory you must pass to `reactor_free_string`. The asymmetry is
deliberate and easy to get backwards in both directions: freeing the static one corrupts
the heap, not freeing the others leaks on every property read.

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

## Testing

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
- [ ] The SDK's own lint/test tasks are in `mise.toml` and wired into CI.

Repo conventions — Linear ticket, branch naming, DCO, stacked PRs — are in
[`CONTRIBUTING.md`](../../../CONTRIBUTING.md) and the `pr-workflow` skill.
