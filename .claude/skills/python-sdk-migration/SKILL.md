---
name: python-sdk-migration
description: >
  Migrate a Python codebase onto the current `reactor-sdk` (this repo, `sdks/python`) —
  either from the old aiortc-based `reactor-team/py-sdk` (last release 0.8.x, deprecated,
  being archived), or from a pre-1.0 install of this SDK. Use this whenever the user asks to
  "migrate to the new reactor-sdk / python sdk", "upgrade from py-sdk", "port off the old
  Reactor Python client", mentions hitting `AttributeError`/`ImportError` on `reactor_sdk`
  symbols like `ReactorState`, `Capabilities`, `MediaStreamTrack`, `get_state`,
  `set_frame_callback`, `RecordingClient`, or references breaking changes around
  `send_command`, `Track`, `ReactorError`/`ReactorFFIError`, or audio device modes. Also use it
  to review a PR that touches `reactor_sdk` usage for compatibility with the current API.
---

# Python SDK migration

There are **two different migrations** this covers, and the first thing to establish is which
one applies — they are not the same size.

<Warning>
  **Both generations publish to PyPI under the identical name `reactor-sdk` and import as
  `reactor_sdk`.** You cannot tell which generation a codebase targets from the import
  statement, and — this is the part that's easy to get backwards — **not from the pinned
  version either.** `reactor-team/py-sdk`'s last PyPI release is `0.8.0`; this repo has never
  published anything (checked against the live PyPI index at the time of writing — every
  release `0.1.0` through `0.8.0` comes from the old repo). So **any `pip`-installed
  `reactor-sdk` below `1.0.0` is the old `py-sdk`, full stop** — there is no PyPI-installed
  "pre-1.0 of this repo" to be on. Check the actual API surface used (see "Which migration
  applies" below); a version pin only tells you the old-SDK case, never the other one.
</Warning>

1. **Old `py-sdk` → this repo's `reactor-sdk` 1.0.0.** The common case, and almost certainly
   what you're looking at if the code was ever `pip install`ed rather than pointed at a git
   checkout. A full rewrite: `reactor-team/py-sdk` was built on `aiortc` and hands you real
   `MediaStreamTrack` objects; this repo is a Rust core behind a `ctypes` FFI boundary and
   hands you raw frames instead. Nothing is a drop-in rename — assume every call site needs a
   look, not just the ones that error at import time.
2. **Pre-1.0 `reactor-sdk` (this repo) → 1.0.0.** Rare, and only possible at all if the
   dependency was a git URL, a local path, or a wheel built from a pre-1.0 commit of this
   repo — never a PyPI install (see the warning above). Mostly relevant to reactor-team's own
   internal consumers who tracked `main` before the 1.0.0 cut. Narrower than migration 1:
   three breaking changes and one additive feature. See
   ["Within this repo: pre-1.0 → 1.0.0"](#within-this-repo-pre-1-0-1-0-0) below.

Skip straight to the checklists — don't re-derive this from first principles by diffing the
two repos yourself; the tables below are already verified against both codebases.

---

## Which migration applies

Grep the target codebase for any of these. If it hits, it's the **old `py-sdk`**:

```bash
grep -rEn "MediaStreamTrack|ReactorState|get_capabilities|get_last_error|get_session_info|get_remote_tracks|set_frame_callback|RecordingClient|\.recording\(\)|fetch_jwt_token|download_clip_as_file|ConflictError|VersionMismatchError" --include="*.py" .
```

A hit on `ConflictError`/`VersionMismatchError` needs a second look: both names exist in
*both* generations now, with **different meaning** — see the table below. Everything else in
that grep is exclusive to the old SDK.

If none of those hit, check how `reactor-sdk` is actually installed (`pip show reactor-sdk`,
or the dependency spec in `pyproject.toml`/`requirements.txt`/`uv.lock`). A plain version pin
from PyPI means old `py-sdk` regardless of the number (see the warning above) — there is
nothing further to check, go to migration 1. Only a git/path/local-wheel dependency can mean
migration 2; confirm by checking whether `Track` (this repo, post-#28) or the typed error
classes (post-#30) already exist in the installed package before assuming it's pre-1.0 of
this repo rather than already current.

---

## Old `py-sdk` → this repo, 1.0.0

### The constructor's positional arguments changed meaning, silently

This is the single most dangerous item here because it doesn't raise — it just does the wrong
thing.

```python
# Old py-sdk: Reactor(model_name, api_key=None, api_url=DEFAULT_BASE_URL, local=False, ...)
Reactor("my-model", "rk_live_...")   # model_name="my-model", api_key="rk_live_..."

# This repo:   Reactor(api_url=None, model_name=None, *, jwt=None, api_key=None, local=False)
Reactor("my-model", "rk_live_...")   # api_url="my-model", model_name="rk_live_..." — WRONG,
                                      # and it will not fail until connect() tries to reach
                                      # "my-model" as a URL.
```

**Always convert positional constructor calls to keyword arguments during this migration.**
`Reactor(model_name="my-model", api_key="rk_live_...")` is unambiguous in both generations and
is the only safe rewrite — don't rely on the new SDK's position-sniffing heuristic (it only
protects `Reactor("my-model")` called with a single argument, not two).

The old constructor's `model_tracks` parameter (preset tracks for codegen'd SDKs) has no
equivalent — drop it, there is nothing to migrate it to.

### Media: no more `MediaStreamTrack`, no more real audio devices

This is the largest conceptual change and the reason nothing here is a rename.

| Old (`py-sdk`) | New (this repo) |
|---|---|
| `publish_track(name, track: MediaStreamTrack)` | `publish_track(name) -> Track`, then push raw frames into it: `push_video_frame(name, bgra_bytes, w, h)` / `push_audio_frame(name, pcm_bytes, samples, ...)`, or the object form `track.push_frame(data)` — see [`Track`](../../sdks/python/reactor_sdk/track.py). You build/capture the media yourself (OpenCV, a file, a synthesizer); there is no `aiortc` track to hand over. |
| `get_remote_tracks() -> dict[str, MediaStreamTrack]` | Nothing hands you a media object. Decoded frames arrive via `on("frame", ...)` / `on("audio", ...)` (raw, client-wide), or a `Track`'s `on_frame()`/`on_raw_frame()` (scoped to one track — see the next two rows; there is no client-wide `reactor.on_frame` anymore). |
| `set_frame_callback(callback)` | `track.on_frame` (decorator, per-track) — found via `reactor.track(name)` or `reactor.tracks.with_kind(...).with_direction(...).one()`. There is no client-wide equivalent; see item 5 under ["Within this repo: pre-1.0 → 1.0.0"](#within-this-repo-pre-1-0-1-0-0). |
| `@reactor.on_track(name)` — a decorator **factory**, pre-filtered by name | `@reactor.on_track` — a **bare** decorator, fires `(name, mid)` for every track; filter by name yourself, or use `reactor.track(name).on_frame` to scope to one track without filtering in the handler body. |
| `@reactor.on_frame` — client-wide, video only | **Removed.** Register on a [`Track`](../../sdks/python/reactor_sdk/track.py) instead: `reactor.track(name).on_frame` by name, or `reactor.tracks.with_direction("recvonly").with_kind("video").one().on_frame` when you don't want to hardcode the name. `reactor.tracks` is a `TrackList` — a `list[Track]` with `with_kind()`/`with_direction()` filters and `.one()`. The raw `on("frame", ...)` / `on("audio", ...)` events are unaffected. |
| Real microphone/speaker via the platform audio device module | **Removed, no replacement.** Every `Reactor` now forces synthetic-only audio: a `sendonly` audio track carries only PCM you push with `push_audio_frame()`/`track.push_frame()`, and a model's audio must be played back explicitly (`on("audio", ...)` → your own playback, e.g. `sounddevice`). There is no constructor flag to opt back into real devices. If the old code relied on the platform module capturing a live mic automatically, that capability is gone — budget time to build a capture/playback path, not just swap an argument. |

### Recording: no more `RecordingClient`

| Old | New |
|---|---|
| `reactor.recording` returns a separate `RecordingClient` with its own `request_clip()`, `request_recording()`, `download_clip_as_file()`, `close()` | `request_clip(duration_seconds)` / `request_recording()` are directly on `Reactor`, returning a `Clip`. No separate client object, no `.close()` to manage. |
| `download_clip_as_file(...)` (on `Reactor` or `RecordingClient`) | No equivalent. Fetch `clip.playlist_url` (an HLS manifest) and concatenate its `.ts` segments yourself — see [`examples/record.py`](../../sdks/python/examples/record.py) for the runnable version, or the pattern in the public docs' [Recordings](https://docs.reactor.inc/concepts/recordings) page. |

### Errors: same two class names, different meaning — read this even if the names match

| Old | New |
|---|---|
| One `ReactorError` dataclass with `code`, `message` as the `on_error` payload; `get_last_error()` polls the last one | `ReactorError` is still the `on_error` payload — and is now also the exception base every failed call raises (`ReactorFFIError`, an intermediate name in this repo's own history, no longer exists — see below). Fields are `code, message, timestamp_ms, recoverable, status, operation, retry_after_ms` — no polling method, and no `component`. |
| `ConflictError(Exception)`, `VersionMismatchError(Exception)` — plain exceptions, old SDK's own session/version-conflict signaling | `ConflictError`, `VersionMismatchError` — **real, current classes**, subclasses of `ReactorError` with `code`/`message`/`recoverable`/`status`/`operation`/`retry_after_ms`. Same names, unrelated implementation. **Don't assume old call sites that catch these by name are still correct** — check what they actually expect on the exception object. |
| One untyped failure path in practice | 16 typed subclasses of `ReactorError` (`UnauthorizedError`, `NotFoundError`, `RateLimitedError`, `InvalidStateError`, `DisconnectedError`, and more) — catch the specific one you can act on, or `ReactorError` broadly. Full list in [`errors.py`](../../sdks/python/reactor_sdk/errors.py). |
| `ReactorState`, `get_state()` | Doesn't exist. Use `reactor.status` (`ReactorStatus` enum) for connection state; there is no separate "state" object. |
| `Capabilities`, `get_capabilities()` | Doesn't exist as a public type. Capabilities drive `reactor.tracks` / `reactor.track(name)` internally; there's no direct accessor for the raw capabilities payload from Python. |
| `get_session_info()` | Doesn't exist. Use `reactor.session_id`. |

### Everything else, briefly

| Old | New |
|---|---|
| `disconnect(recoverable: bool = False)` | `disconnect()` — no parameter, **always** preserves the session for `reconnect()`. If old code called `disconnect()` (default `recoverable=False`, terminating the session) and expected that to end things permanently, it now doesn't — call nothing further, or check the platform API for explicit session termination. |
| `unpublish_track(name) -> None`, async | `unpublish_track(name) -> int`, **sync**. Returns `0`/`-1`, does not raise. Drop the `await`, and check the return value — this is the one operation in the SDK that fails silently by design (see "Known traps" below). |
| `connect(*, session_id=None, connection_id=None, auto_resume_tracks=True)` | `connect(*, session_id=None, connection_id=None)` — `connection_id` is back (added post-1.0.0, closing a rewrite gap; same idea as the old parameter — adopt a connection slot a backend already registered for the session). `auto_resume_tracks` stays gone: every output track always starts subscribed; call `pause_track(name)` right after `connect()` for the ones you don't want yet. |
| `fetch_jwt_token(...)` | `fetch_jwt(api_key, api_url, *, models=None, max_sessions=None, expires_after=None) -> str` — renamed **and** the signature changed (`api_url` is now required, not read from a default). It is synchronous; wrap it in `asyncio.to_thread()` from async code. |
| `on(event: ReactorEvent, handler)` | `on(event: str, handler)` — `ReactorEvent` doesn't exist; event names are plain strings (`"status_changed"`, not `"statusChanged"`). |
| `on_status(func)` / `on_status(ReactorStatus.READY)` / `on_status([READY, WAITING])` | Same three forms, same behavior — this one carried over unchanged. |
| `send_command(command, data)` — fire-and-forget, reply arrives later as a `message` event | `send_command(command, data) -> dict | None` — **awaits and returns the correlated reply**. To fire without waiting, `asyncio.create_task(reactor.send_command(...))`. |
| `upload_file(...)` requiring a fully `READY` session | `upload_file(...)` needs only an active session (as soon as the coordinator creates one), not full `READY`. |

---

## Within this repo: pre-1.0 → 1.0.0

Narrower — three breaking changes, one additive feature, all in the same release:

1. **`send_command()` now awaits its reply** instead of firing and forgetting. See the row
   above; same fix applies whether you're coming from `py-sdk` or an earlier `reactor-sdk`.
2. **Errors became typed exceptions, and `ReactorError` dropped `component`.** If code
   pattern-matches on `error.component` (`"api"`/`"gpu"`) or catches only the base exception
   and parses the message string for a code, both need updating — see the errors table above.
   Mid-transition this base was briefly named `ReactorFFIError`; it was folded into
   `ReactorError` (the same class the `on_error` event already used) before 1.0.0 shipped, so
   code written against an intermediate 0.9.x/1.0.0-pre build may still say
   `from reactor_sdk import ReactorFFIError` — that import now fails, replace it with
   `ReactorError`.
3. **Audio is synthetic-only; `adm_mode` is gone from the constructor.** Drop the argument
   entirely — passing it raises `TypeError` now, it isn't silently ignored.
4. **New, additive: the `Track` object.** `reactor.track(name)` / `reactor.tracks` return
   objects with `publish()`, `push_frame()`, `on_frame()`/`on_raw_frame()`, `pause()`/
   `resume()` that raise on a direction mismatch instead of silently doing nothing. This is
   optional — every name-based call (`publish_track()`, `push_video_frame()`,
   `on("frame", ...)`) still works unchanged. Adopt it opportunistically; don't rewrite
   working name-based code just because it exists.
5. **`reactor.on_frame` (client-wide) is removed.** It only ever worked for video, and one
   handler fed every `recvonly` video track couldn't tell them apart. This one is **not**
   optional if the codebase uses it — there is no name-based fallback for the client-wide
   decorator specifically. Move to `reactor.track(name).on_frame` or
   `reactor.tracks.with_direction("recvonly").with_kind("video").one().on_frame`.
   `reactor.tracks` became a `TrackList` (a `list[Track]` with `with_kind()`/`with_direction()`
   filters and `.one()`) to make the filtered form convenient. The raw `on("frame", ...)` /
   `on("audio", ...)` events are unaffected — only the decorator is gone.

---

## Known traps — worth calling out explicitly during a migration, not just fixing symbols

- **`publish_track()` / `Track.publish()` failures don't clean up the session.** If the
  migrated code publishes outside a `try`/`finally: await reactor.disconnect()`, a failure
  there leaves the session orphaned, and the *next* `connect()` attempt fails with
  `ConflictError` — a confusing failure one call away from its actual cause. Wrap the whole
  connected lifetime, publish included, in one `try`/`finally`.
- **`unpublish_track()` / `Track.unpublish()` don't raise.** They're the one operation left in
  the old "fails quietly" style — `0` success, `-1` failure, no exception either way. Check
  the return value explicitly if the caller needs to know.
- **No built-in audio playback.** If the old code relied on the platform audio module playing
  a model's voice through real speakers, there is no one-line replacement — budget for
  building a small playback loop (`sounddevice` or similar) against `on("audio", ...)` /
  `track.on_frame()`. See [`examples/pygame_app`](../../sdks/python/examples/pygame_app) for a
  worked example, not a library you can import.
- **`ConflictError`/`VersionMismatchError` name collisions** (above) — a `except
  ConflictError:` block ported unchanged from the old SDK will still run, but on a different
  condition than the developer originally meant. Read what triggers it now before trusting it.

---

## Migration procedure

1. Run the "Which migration applies" grep above across the target codebase.
2. Convert every `Reactor(...)` construction to keyword arguments (`model_name=`, `api_key=`
   or `jwt=`, `api_url=`, `local=`) — do this first, before anything else, since it's silent
   if missed.
3. Work through the tables top to bottom, fixing call sites. Prefer `grep -rn` for each old
   symbol name over trying to remember where they're used.
4. For anything touching media (`MediaStreamTrack`, `publish_track`, `get_remote_tracks`,
   frame callbacks), expect to restructure, not just rename — read the "Media" section above
   and the current [`Track`](../../sdks/python/reactor_sdk/track.py) docstrings.
5. Search for bare `except ConflictError` / `except VersionMismatchError` and re-verify what
   condition they're meant to catch.
6. Wrap connected lifetimes that call `publish_track()`/`track.publish()` in
   `try`/`finally: await reactor.disconnect()` if they aren't already.
7. Run the target codebase's own test suite. If it has none, at minimum exercise connect →
   publish/push → receive → disconnect once against `local=True` or a real key.
8. Pin `reactor-sdk>=1.0` explicitly in the migrated project's dependency file — mid-1.0
   transition, a loose pin can silently resolve back to a `0.8.x` old-generation wheel on a
   platform with no matching 1.0 wheel (musl, very old glibc, 32-bit, Windows on ARM). See the
   [SDK README](../../sdks/python/README.md#supported-platforms).

## Reference

- Current API: [`reactor_sdk/client.py`](../../sdks/python/reactor_sdk/client.py),
  [`track.py`](../../sdks/python/reactor_sdk/track.py),
  [`errors.py`](../../sdks/python/reactor_sdk/errors.py).
- Public docs: [Python SDK reference](https://docs.reactor.inc/sdk-reference/python/reactor),
  [`Track`](https://docs.reactor.inc/sdk-reference/python/track),
  [changelog](https://docs.reactor.inc/changelog/overview).
- The tables above were verified against `reactor-team/py-sdk` @ `0.8.1` and this repo's
  `main` as of 2026-08-17 — send_command correlation, `Track`, typed errors, synthetic-only
  ADM, the `reactor.on_frame` removal / `TrackList` change, the `ReactorFFIError` →
  `ReactorError` unification, and `connect(connection_id=...)` are all merged to `main`.
  `download_clip()` / `download_recording()` (would close the `download_clip_as_file()` gap
  called out above) and a built-in `AudioPlayer` (would close the "no built-in playback" gap
  above) are open PRs (#36, #38) at time of writing, **not yet on `main`** — check whether
  either has merged before telling someone that gap is still open.

  If the repo has moved since, spot-check a table row against the actual source before
  trusting it on a large migration.
