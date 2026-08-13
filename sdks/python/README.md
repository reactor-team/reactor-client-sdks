# Python SDK for Reactor

[![PyPI: reactor-sdk](https://img.shields.io/pypi/v/reactor-sdk.svg?label=reactor-sdk)](https://pypi.org/project/reactor-sdk/)
[![PyPI Downloads](https://img.shields.io/pypi/dm/reactor-sdk.svg?label=downloads)](https://pypi.org/project/reactor-sdk/)
[![build](https://img.shields.io/github/actions/workflow/status/reactor-team/reactor-client-sdks/ci.yml?branch=main)](https://github.com/reactor-team/reactor-client-sdks/actions)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](../../LICENSE)

Use this SDK to connect your Python app to a live [Reactor](https://reactor.inc)
model: send commands and receive real-time video and audio back. Built for
scripts, servers, and computer-vision pipelines — it authenticates directly
with your API key, server-side.

---

## Getting Started

```bash
pip install reactor-sdk
```

Requires Python 3.10+.

---

## Usage Example

```python
import asyncio
from reactor_sdk import Reactor, ReactorStatus

API_KEY = "..."  # Insert your API key here.

async def main():
    async with Reactor(model_name="my-model", api_key=API_KEY) as r:

        @r.on_status(ReactorStatus.READY)
        async def on_ready(status):
            await r.send_command("set_prompt", {"prompt": "a forest at dawn"})

        def on_frame(bgra, width, height, frame_id, timestamp_us, user_data):
            print(f"frame: {width}x{height}")

        r.on("frame", on_frame)

        await r.connect()
        await asyncio.sleep(30)  # keep the session open while frames arrive

asyncio.run(main())
```

`@r.on_frame` needs `numpy` installed — see [Events](#events) below.

---

## Documentation & Resources

See the [full documentation](https://docs.reactor.inc/overview) for platform
concepts and the other language SDKs. The API reference below is the
accurate one for this package.

## Events

Register handlers with `r.on(event, handler)`, or with the equivalent
decorators (`@r.on_status`, `@r.on_error`, `@r.on_message`, `@r.on_track`,
`@r.on_frame`):

| Event | Arguments |
| --- | --- |
| `status_changed` | `status: str` — `disconnected` / `connecting` / `waiting` / `ready` |
| `session_id_changed` | `session_id: str \| None` |
| `message` | `payload: dict` — application message from the model |
| `runtime_message` | `payload: dict` — platform message |
| `capabilities_received` | `capabilities: dict` |
| `track_received` | `name: str`, `mid: str \| None` |
| `frame` | `bgra: bytes`, `width`, `height`, `frame_id`, `timestamp_us`, `user_data: bytes` |
| `audio` | `pcm: bytes`, `num_samples`, `sample_rate`, `channels` |
| `error` | `error: ReactorError` |

`@r.on_frame` is worth calling out on its own: it hands the handler a
decoded RGB `numpy` array instead of raw BGRA bytes, and the handler can
declare as few or as many of `(frame, frame_id, timestamp_us, user_data)`
as it needs:

```python
@r.on_frame
def render(frame):  # numpy array, shape (height, width, 3)
    ...
```

`@r.on_status` can also filter to one status: `@r.on_status(ReactorStatus.READY)`.
Requires `numpy` to be installed — it isn't a hard dependency of the
package, since the plain `on("frame", ...)` form (raw BGRA bytes, no
conversion) doesn't need it.

### Where handlers run

**Control events** — everything except `frame` and `audio` — run on your asyncio
event loop, so you can touch asyncio state from them directly:

```python
ready = asyncio.Event()

def on_status(status: str) -> None:
    if status == "ready":
        ready.set()          # safe: this runs on the loop thread

r.on("status_changed", on_status)
await asyncio.wait_for(ready.wait(), timeout=60)
```

**`frame` and `audio`** run on their own native delivery threads instead, because
that is what applies backpressure: while your handler runs, the library keeps only
the newest frame, so a slow consumer gets fresh frames rather than a growing
backlog. Two consequences:

* Blocking is safe — it never stalls WebRTC — but you pay in dropped data. Block in
  `frame` and the frames in between are discarded; block in `audio` and you lose
  samples once its short queue fills.
* You are off the loop, so reach asyncio through `call_soon_threadsafe`:

```python
loop = asyncio.get_running_loop()

def on_frame(bgra, width, height, frame_id, timestamp_us, user_data):
    loop.call_soon_threadsafe(frames.put_nowait, bgra)
```

### Closing

Use `async with`, or call `close()`. An `atexit` hook closes anything you leave
open, which matters more than it sounds: teardown has to finish while the
interpreter is still running, because once it starts finalising, native threads
never get the GIL back.

```python
loop = asyncio.get_running_loop()
ready = asyncio.Event()

def on_status(status: str) -> None:
    if status == "ready":
        loop.call_soon_threadsafe(ready.set)

r.on("status_changed", on_status)
```

---

## API Reference

### `Reactor(api_url=None, model_name=None, *, jwt=None, api_key=None, local=False, adm_mode=None)`

`api_url` and `model_name` can be given in either order (a URL always
starts with `http(s)://`, a model name never does); `api_url` defaults to
Reactor's production coordinator. Pass either `jwt` (a token you already
have) or `api_key` (exchanged for one at connect time) — `jwt` wins if
both are given.

| Method | Description |
|---|---|
| `on(event, handler)` / `off(event, handler)` | Subscribe / unsubscribe from an event (see table above). |
| `await connect(session_id=None)` | Create (or adopt) a session and establish the WebRTC transport. |
| `await reconnect()` | Reconnect the existing session after a transient failure. |
| `await disconnect()` | Gracefully disconnect; the session is preserved and can be resumed with `reconnect()`. |
| `close()` | Destroy the underlying native handle. Called automatically on `__aexit__`, and as a last resort on `__del__` / interpreter exit. |
| `send_command(name, data, scope="application")` | Command over the data channel. `scope="runtime"` sends a platform-level command. Returns a `CommandResult` (an `int`: `0` success, `-1` error) that can also be `await`-ed or wrapped in `asyncio.create_task` — the send already happened either way. |
| `await publish_track(name)` | Activate a named `sendonly` track slot. |
| `unpublish_track(name)` | Deactivate a `sendonly` track (sync). |
| `await pause_track(name)` / `await resume_track(name)` | Pause / resume a `recvonly` track subscription. |
| `push_video_frame(track_name, data, width, height, user_data=None)` | Push a raw **BGRA** frame into a `sendonly` video track. `user_data` tags the frame; see [`examples/frame_metadata.py`](examples/frame_metadata.py). |
| `push_audio_frame(track_name, data, samples_per_channel, sample_rate=48000, num_channels=1)` | Push interleaved `int16` PCM into a `sendonly` audio track. |
| `await request_clip(duration_seconds) -> Clip` | Request a clip of the last N seconds of the session. |
| `await request_recording() -> Clip` | Request a clip covering the whole session so far. |
| `await upload_file(path) -> FileRef` | Upload a local file; pass the result as a command argument. |
| `status` (property) / `get_status()` | Current `ReactorStatus` — a `str` enum, so it compares equal to `"ready"` and to `ReactorStatus.READY` alike. |
| `session_id` (property) / `get_session_id()` | Current session ID, or `None` when disconnected. |

`Reactor` is also an async context manager (`async with Reactor(...) as r:`)
that disconnects and closes the handle on exit.

### Types

| Type | Fields |
|---|---|
| `Clip` | `session_id, kind, start_marker, end_marker, now_marker, predicted_ready_at_ms, playlist_url` |
| `FileRef` | `upload_id, name, mime_type, size` — pass as a command argument to reference an uploaded file. |
| `ReactorError` | `code, message, timestamp_ms, recoverable, component, retry_after_ms` |
| `ReactorFFIError` | Raised when an async FFI operation (`connect`, `request_clip`, ...) fails. |

---

## The native library

Released wheels bundle `libreactor_ffi` for their platform, so `pip install` is all
you need. Wheels are published as GitHub release assets, tagged `python-vX.Y.Z`:

```bash
pip install https://github.com/reactor-team/reactor-client-sdks/releases/download/python-v0.9.0/<wheel>
```

Each is `py3-none-<platform>` — the SDK reaches the library through ctypes and links
no libpython, so one wheel per platform covers every supported interpreter. Built
for the same five platforms as reactor-webrtc: linux-x64, linux-arm64, mac-arm64,
mac-x64 and win-x64.

The library is resolved at first use, in order:

1. `REACTOR_FFI_LIB` — absolute path, which overrides everything.
2. `libreactor_ffi.{dylib,so}` / `reactor_ffi.dll` next to the `reactor_sdk` package —
   where a released wheel puts it.
3. `target/release/` in an enclosing Cargo workspace, for development.

---

## Samples

Runnable scripts in [`examples/`](examples/), each driven by
`REACTOR_API_URL` / `REACTOR_MODEL` / `REACTOR_JWT` / `REACTOR_LOCAL`
environment variables (see [`reactor_client.py`](examples/reactor_client.py)):

| Script | Demonstrates |
|---|---|
| [`main.py`](examples/main.py) | Minimal connect → send a command → disconnect. |
| [`push_video.py`](examples/push_video.py) | Stream generated frames into a `sendonly` video track. |
| [`push_audio.py`](examples/push_audio.py) | Stream a sine tone or a WAV file into a `sendonly` audio track. |
| [`pause_resume.py`](examples/pause_resume.py) | Pause and resume a `recvonly` track subscription. |
| [`record.py`](examples/record.py) | Request a clip or full-session recording and download the HLS segments. |
| [`frame_metadata.py`](examples/frame_metadata.py) | Read the per-frame metadata trailer off an incoming track. |
| [`frame_metadata_roundtrip.py`](examples/frame_metadata_roundtrip.py) | Tag outgoing frames and match them against the ones that come back. |
| [`metadata_publisher.py`](examples/metadata_publisher.py) | Publish tagged frames with no UI — the sending half of a two-process demo (pair with `pygame_app/`). |
| [`pygame_app/`](examples/pygame_app/) | A pygame application: live video display plus a dynamic control UI built from the model's declared capabilities. |

Run `main.py` directly; every other example (aside from `pygame_app/`,
which is its own standalone app — see its own
[README](examples/pygame_app/README.md)) imports its sibling
`reactor_client.py` with a relative import, so run it as a module instead
(both from `sdks/python/`):

```bash
REACTOR_MODEL=my-model REACTOR_JWT=<token> python examples/main.py

REACTOR_MODEL=my-model REACTOR_JWT=<token> python -m examples.push_video --track video_input
```

---

## Development

```bash
mise run lint:python     # ruff check + format
mise run test:python     # pytest
mise run build:wheel     # cargo build --release, then a wheel with it bundled
```

Tests skip themselves if the compiled library isn't present, so `pytest` runs
clean on a fresh checkout. `mise run build:wheel` without one still produces a
wheel, with a warning that it's pure-Python — fine for an editable install,
but not something to publish as a release.

See the repo-wide [`CONTRIBUTING.md`](../../CONTRIBUTING.md) for everything
else (DCO, commit conventions, opening a PR).

---

## License

Apache-2.0 — see [`LICENSE`](../../LICENSE).
