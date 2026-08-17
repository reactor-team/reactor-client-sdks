---
name: python-sdk-migration
description: >
  Migrate a Python codebase off the old aiortc-based `reactor-team/py-sdk` (last release
  0.8.x, deprecated, being archived) onto the current `reactor-sdk` (this repo,
  `sdks/python`, 1.0.0). Use this whenever the user asks to "migrate to the new reactor-sdk
  / python sdk", "upgrade from py-sdk", "port off the old Reactor Python client", mentions
  hitting `AttributeError`/`ImportError` on `reactor_sdk` symbols like `ReactorState`,
  `Capabilities`, `MediaStreamTrack`, `get_state`, `set_frame_callback`, `RecordingClient`,
  or references breaking changes around `send_command`, `Track`, `ReactorError`, or audio
  device modes. Also use it to review a PR that touches `reactor_sdk` usage for
  compatibility with the current API.
---

# Python SDK migration

Migrating off the old `reactor-team/py-sdk` (aiortc-based, last PyPI release `0.8.0`,
deprecated) onto this repo's `reactor-sdk` 1.0.0. A full rewrite, not a version bump:
`py-sdk` was built on `aiortc` and hands you real `MediaStreamTrack` objects; this repo is a
Rust core behind a `ctypes` FFI boundary and hands you raw frames instead. Nothing here is a
drop-in rename — assume every call site needs a look, not just the ones that error at import
time.

Skip straight to the checklists below — don't re-derive this from first principles by
diffing the two repos yourself; the tables are already verified against both codebases.

---

## Confirm it's actually the old `py-sdk`

Grep the target codebase for any of these — exclusive to the old SDK, so a hit confirms this
skill applies:

```bash
grep -rEn "MediaStreamTrack|ReactorState|get_capabilities|get_last_error|get_session_info|get_remote_tracks|set_frame_callback|RecordingClient|\.recording\(\)|fetch_jwt_token|download_clip_as_file" --include="*.py" .
```

If nothing hits, check how `reactor-sdk` is actually installed (`pip show reactor-sdk`, or
the dependency spec in `pyproject.toml`/`requirements.txt`/`uv.lock`). A version pin below
`1.0.0` from PyPI is the old `py-sdk` regardless of what the grep found — this repo has never
published anything below `1.0.0` (checked against the live PyPI index at the time of
writing; every release `0.1.0` through `0.8.0` comes from the old repo). If it's already
`>=1.0.0`, or a git/path dependency already exposing `Track` or the typed error classes,
there is nothing here to migrate.

---

## Migrating to `reactor-sdk` 1.0.0

### The constructor's positional arguments — good news, this one just works

```python
Reactor(model_name: str, api_key: str | None = None, *, jwt=None, api_url=DEFAULT_API_URL, local=False)
```

This is the old `py-sdk`'s own positional order (`Reactor(model_name, api_key=None,
api_url=DEFAULT_BASE_URL, local=False, ...)`), exactly. A ported `Reactor("my-model",
"rk_live_...")` call needs **no rewrite at all** — `model_name` and `api_key` land right.

This was not always true: an earlier post-1.0.0 build took `(api_url, model_name)` positionally
in either order, sniffed by which one looked like a URL — a real footgun, since it failed
silently rather than raising, and the sniffing only protected a single positional argument, not
two. Fixed before this skill was last verified (below) by making `api_url` keyword-only with a
concrete default, which removed the ambiguity outright rather than sniffing around it. If a
version between there and here is what's actually installed, check the installed signature
(`help(Reactor.__init__)`) before trusting this row.

The old constructor's `model_tracks` parameter (preset tracks for codegen'd SDKs) has no
equivalent — drop it, there is nothing to migrate it to.

### Media: no more `MediaStreamTrack`, no more real audio devices

This is the largest conceptual change and the reason nothing here is a rename.

| Old (`py-sdk`) | New (this repo) |
|---|---|
| `publish_track(name, track: MediaStreamTrack)` | `publish_track(name) -> Track`, then push frames into the track itself: `track.push_frame(data)` for both kinds — see [`Track`](../../sdks/python/reactor_sdk/track.py). The name-based `push_video_frame`/`push_audio_frame` are gone; pushing before `publish()` now raises `InvalidStateError` instead of going nowhere. You build/capture the media yourself (OpenCV, a file, a synthesizer); there is no `aiortc` track to hand over. |
| `get_remote_tracks() -> dict[str, MediaStreamTrack]` | Nothing hands you a media object. Decoded frames arrive on the `Track`: `on_frame()` for numpy arrays, `on_raw_frame()` for the bytes — see the next two rows. There is no client-wide delivery of any kind; `on("frame", ...)` / `on("audio", ...)` are refused at registration, with the per-track replacement in the error. |
| `set_frame_callback(callback)` | `track.on_frame` (decorator, per-track) — found via `reactor.track(name)` or `reactor.tracks.with_kind(...).with_direction(...).one()`. There is no client-wide equivalent — register per-track instead. |
| `@reactor.on_track(name)` — a decorator **factory**, pre-filtered by name | `@reactor.on_track` — a **bare** decorator, fires with the resolved [`Track`](../../sdks/python/reactor_sdk/track.py) itself (not a bare name) for every track; filter on `track.name` yourself, or use `reactor.track(name).on_frame` to scope to one track without filtering in the handler body. `track.mid` carries the WebRTC media stream id the old `mid` argument did. |
| `@reactor.on_frame` — client-wide, video only | **Removed.** Register on a `Track` instead: `reactor.track(name).on_frame` by name, or `reactor.tracks.with_direction("recvonly").with_kind("video").one().on_frame` when you don't want to hardcode the name. `reactor.tracks` is a `TrackList` — a `list[Track]` with `with_kind()`/`with_direction()` filters and `.one()`. The raw client-wide `on("frame", ...)` / `on("audio", ...)` events are gone too: use `on_raw_frame()`, which takes the same arguments they did. |
| Real microphone/speaker via the platform audio device module | Every `Reactor` still forces synthetic-only audio at the transport level — no constructor flag opts back into a real device there. But `reactor_sdk.audio_devices.Speaker`/`Microphone` (added post-1.0.0) wrap a `sounddevice` stream around a `Track` for you: `Speaker(reactor.track("output"))` plays a `recvonly` track through real speakers, `Microphone(await reactor.track("mic").publish())` captures the real mic into a `sendonly` track — `publish()` hands the track back, and pushing into one that was never published now raises `InvalidStateError`. Context managers, not automatic — construct and enter them explicitly, they don't come with the session. |

### Recording: no more `RecordingClient`

| Old | New |
|---|---|
| `reactor.recording` returns a separate `RecordingClient` with its own `request_clip()`, `request_recording()`, `download_clip_as_file()`, `close()` | `request_clip(duration_seconds)` / `request_recording()` are directly on `Reactor`, returning a `Clip`. No separate client object, no `.close()` to manage. |
| `download_clip_as_file(...)` (on `Reactor` or `RecordingClient`) | [`download_clip(clip, path=None)`](../../sdks/python/reactor_sdk/_recording.py) (module-level, exported from `reactor_sdk`) or, skipping `request_clip()`/`request_recording()` entirely, [`reactor.download_clip(seconds, path=None)`](../../sdks/python/reactor_sdk/client.py) / `reactor.download_recording(path=None)`. Given `path` it streams straight there and returns `None`; without one it returns the assembled bytes. Interleaved MPEG-TS, not the old fMP4 — playable as-is by most players, remux with `ffmpeg -c copy` if that container is specifically needed. See [`examples/record.py`](../../sdks/python/examples/record.py) (`--simple` flag for the one-call form) or the public docs' [Recordings](https://docs.reactor.inc/concepts/recordings) page. |

### Errors: same two class names, different meaning — read this even if the names match

| Old | New |
|---|---|
| One `ReactorError` dataclass with `code`, `message` as the `on_error` payload; `get_last_error()` polls the last one | `ReactorError` is still the `on_error` payload — and is now also the exception base every failed call raises. Fields are `code, message, timestamp_ms, recoverable, status, operation, retry_after_ms` — no polling method, and no `component`. |
| `ConflictError(Exception)`, `VersionMismatchError(Exception)` — plain exceptions, old SDK's own session/version-conflict signaling | `ConflictError`, `VersionMismatchError` — **real, current classes**, subclasses of `ReactorError` with `code`/`message`/`recoverable`/`status`/`operation`/`retry_after_ms`. Same names, unrelated implementation. **Don't assume old call sites that catch these by name are still correct** — check what they actually expect on the exception object. |
| One untyped failure path in practice | 16 typed subclasses of `ReactorError` (`UnauthorizedError`, `NotFoundError`, `RateLimitedError`, `InvalidStateError`, `DisconnectedError`, and more) — catch the specific one you can act on, or `ReactorError` broadly. Full list in [`errors.py`](../../sdks/python/reactor_sdk/errors.py). |
| `ReactorState`, `get_state()` | Doesn't exist. Use `reactor.status` (`ReactorStatus` enum) for connection state; there is no separate "state" object. |
| `Capabilities`, `get_capabilities()` | Doesn't exist as a public type. Capabilities drive `reactor.tracks` / `reactor.track(name)` internally; there's no direct accessor for the raw capabilities payload from Python. |
| `get_session_info()` | Doesn't exist. Use `reactor.session_id`. |

### Everything else, briefly

| Old | New |
|---|---|
| `disconnect(recoverable: bool = False)` | `disconnect()` — no parameter, **always** terminates the session server-side, same as the old default (`recoverable=False`). There is no way to make it preserve the session instead. If old code called `disconnect(True)` expecting the session to survive for a later resume, call [`reconnect()`](../../sdks/python/reactor_sdk/client.py) directly now instead of `disconnect()` + `connect()` — it tears the live connection down itself without ending the session, and works from any status including `ready`, not only after a drop. |
| `unpublish_track(name) -> None`, async | `unpublish_track(name) -> None`, **sync**. Drop the `await`. A failure is logged (`reactor_sdk` at `WARNING`), not raised — unpublish is commonly the last call in a `finally` block, and this deliberately doesn't interrupt it. (A 1.0.0-adjacent build had this returning `0`/`-1` instead of logging; if the installed package still does, it predates that fix.) |
| `connect(*, session_id=None, connection_id=None, auto_resume_tracks=True)` | `connect(*, session_id=None, connection_id=None)` — `connection_id` is back (added post-1.0.0, closing a rewrite gap; same idea as the old parameter — adopt a connection slot a backend already registered for the session). `auto_resume_tracks` stays gone: every output track always starts subscribed; call `reactor.track(name).pause()` right after `connect()` for the ones you don't want yet. |
| `fetch_jwt_token(...)` | `fetch_jwt(api_key, api_url=DEFAULT_API_URL, *, models=None, max_sessions=None, expires_after=None) -> str` — renamed; `api_url` is optional, same production default `Reactor()` itself uses. It is synchronous; wrap it in `asyncio.to_thread()` from async code. |
| `on(event: ReactorEvent, handler)` | `on(event: str, handler)` — `ReactorEvent` doesn't exist; event names are plain strings (`"status_changed"`, not `"statusChanged"`). |
| `on_status(func)` / `on_status(ReactorStatus.READY)` / `on_status([READY, WAITING])` | Same three forms, same behavior — this one carried over unchanged. |
| `send_command(command, data)` — fire-and-forget, reply arrives later as a `message` event | `await send_command(command, data) -> dict | None` — **awaits and returns the correlated reply**. To fire without waiting, `asyncio.create_task(reactor.send_command(...))`. |
| `upload_file(...)` requiring a fully `READY` session | Same requirement, unchanged — raises `InvalidStateError` before `ready`. (A 1.0.0-adjacent build relaxed this to only needing an active session, before the WebRTC handshake finished; fixed back to match `request_clip()`/`Track.pause()`'s own guard before this skill was last verified, below.) |

---

## Known traps — worth calling out explicitly during a migration, not just fixing symbols

- **`publish_track()` / `Track.publish()` failures don't clean up the session.** If the
  migrated code publishes outside a `try`/`finally: await reactor.disconnect()`, a failure
  there leaves the session orphaned, and the *next* `connect()` attempt fails with
  `ConflictError` — a confusing failure one call away from its actual cause. Wrap the whole
  connected lifetime, publish included, in one `try`/`finally`.
- **`unpublish_track()` / `Track.unpublish()` don't raise.** A failure is logged
  (`reactor_sdk` at `WARNING`), not raised — deliberately, since unpublish is commonly the
  last call in a `finally` block and raising there would replace whatever exception was
  already propagating. Check the logs if a track seems to have stayed published, rather than
  a return value — there isn't one (`None` either way) as of the fix that closed the gap
  described just above this line; an older 1.0.0-adjacent build returned an unchecked `0`/`-1`
  instead.
- **Playback/capture are `Speaker`/`Microphone` (`reactor_sdk.audio_devices`), not something to
  build yourself.** If the old code relied on the platform audio module playing a model's
  voice through real speakers or capturing a live mic, `Speaker(track)` /
  `Microphone(sendonly_track)` are the replacement — context managers wrapping a
  `sounddevice` stream around a `recvonly`/`sendonly` `Track`. Not automatic like the old
  platform module was: still has to be constructed and entered explicitly.
- **`disconnect()` always ends the session — there is no recoverable option.** If the old code
  called `disconnect(True)` (or relied on the default `False` terminating and expected
  `disconnect()` alone to preserve it, having read a docs page written before this was fixed),
  neither survives the port: call [`reconnect()`](../../sdks/python/reactor_sdk/client.py)
  directly instead of `disconnect()` + `connect()` to keep the session and resume it — it works
  from `ready` too, not only after a drop, and tears the live connection down itself.
- **`ConflictError`/`VersionMismatchError` name collisions** (above) — a `except
  ConflictError:` block ported unchanged from the old SDK will still run, but on a different
  condition than the developer originally meant. Read what triggers it now before trusting it.

---

## Migration procedure

1. Run the "Confirm it's actually the old `py-sdk`" grep above across the target codebase.
2. Work through the tables top to bottom, fixing call sites. Prefer `grep -rn` for each old
   symbol name over trying to remember where they're used.
3. For anything touching media (`MediaStreamTrack`, `publish_track`, `get_remote_tracks`,
   frame callbacks), expect to restructure, not just rename — read the "Media" section above
   and the current [`Track`](../../sdks/python/reactor_sdk/track.py) docstrings.
4. Search for bare `except ConflictError` / `except VersionMismatchError` and re-verify what
   condition they're meant to catch.
5. Wrap connected lifetimes that call `publish_track()`/`track.publish()` in
   `try`/`finally: await reactor.disconnect()` if they aren't already.
6. Run the target codebase's own test suite. If it has none, at minimum exercise connect →
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
  `ReactorError` unification, `connect(connection_id=...)`, `download_clip()` /
  `reactor.download_clip()`/`download_recording()`, `Speaker`/`Microphone`
  (`reactor_sdk.audio_devices`), `@reactor.on_track` handing over a `Track`, and
  `unpublish_track()`/`Track.unpublish()` logging instead of returning a code, are all merged
  to `main`. `Reactor(model_name, api_key)`'s positional order, `fetch_jwt()`'s optional
  `api_url`, and `upload_file()` requiring `ready` again are an open PR (#43) at time of
  writing, **not yet on `main`** — check whether it has merged before trusting those three
  specifically.

  If the repo has moved since, spot-check a table row against the actual source before
  trusting it on a large migration.
