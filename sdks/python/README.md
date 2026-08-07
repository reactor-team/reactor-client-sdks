# reactor-sdk (Python)

[![PyPI: reactor-sdk](https://img.shields.io/pypi/v/reactor-sdk.svg?label=reactor-sdk)](https://pypi.org/project/reactor-sdk/)
[![PyPI Downloads](https://img.shields.io/pypi/dm/reactor-sdk.svg?label=downloads)](https://pypi.org/project/reactor-sdk/)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](../../LICENSE)

Async Python client for [Reactor](https://reactor.inc) — connect to a live
world model over WebRTC, receive its streaming video, and send commands
that steer what it generates while it runs.

---

## Quick Start

### Installation

```bash
pip install reactor-sdk
```

`reactor-sdk` itself is pure Python — the wheel doesn't include the native
library. You also need `libreactor_ffi` built from this repo and
discoverable at runtime:

```bash
git clone https://github.com/reactor-team/reactor-client-sdks
cd reactor-client-sdks
cargo build -p reactor-ffi --release
```

The library is located, in order: the `REACTOR_FFI_LIB` environment
variable, `libreactor_ffi.{dylib,so}` / `reactor_ffi.dll` next to the
`reactor` package, or `target/release/...` walking up from it. In practice:

- **Working from a checkout** (`pip install -e sdks/python`) — nothing else
  to do; the walk-up finds `target/release` automatically.
- **`pip install reactor-sdk` from PyPI** into another project — set
  `REACTOR_FFI_LIB` to the library you built above.

### Basic Usage

```python
import asyncio
from reactor import Reactor

async def main():
    async with Reactor("https://api.reactor.inc", "my-model", jwt="...") as r:
        r.on("status_changed", lambda s: print("status:", s))
        r.on("message", lambda msg: print("message:", msg))

        await r.connect()
        r.send_command("hello", {"text": "hi"})

        await asyncio.sleep(10)

asyncio.run(main())
```

Set `local=True` (or `REACTOR_LOCAL=1` in the examples) to talk to a
`reactor-runtime` instance running on your machine instead of
`api.reactor.inc`.

---

## Documentation & Resources

| Resource | Covers |
|---|---|
| [`docs/concepts.md`](../../docs/concepts.md) | Sessions and connection state, tracks and capabilities, scopes, callback threading. Start here. |
| [`docs/messaging.md`](../../docs/messaging.md) | Sending commands, receiving messages, capabilities negotiation, track publish/pause/resume. |
| [`docs/recording.md`](../../docs/recording.md) | Requesting clips and full-session recordings. |
| [`docs/frame-metadata.md`](../../docs/frame-metadata.md) | Tagging and reading per-frame metadata trailers on video tracks. |
| API reference | Below, on this page. |

---

## API Reference

### `Reactor(api_url, model_name, *, jwt=None, local=False, adm_mode=None)`

| Method | Description |
|---|---|
| `on(event, handler)` / `off(event, handler)` | Subscribe / unsubscribe from an event (see table below). |
| `await connect(session_id=None)` | Create (or adopt) a session and establish the WebRTC transport. |
| `await reconnect()` | Reconnect the existing session after a transient failure. |
| `await disconnect()` | Gracefully disconnect; the session is preserved and can be resumed with `reconnect()`. |
| `close()` | Destroy the underlying native handle. Called automatically on `__aexit__`. |
| `send_command(name, data, scope="application")` | Fire-and-forget command over the data channel. `scope="runtime"` sends a platform-level command. Returns `0` on success, `-1` on error. |
| `await publish_track(name)` | Activate a named `sendonly` track slot. |
| `unpublish_track(name)` | Deactivate a `sendonly` track (sync). |
| `await pause_track(name)` / `await resume_track(name)` | Pause / resume a `recvonly` track subscription. |
| `push_video_frame(track_name, data, width, height, user_data=None)` | Push a raw **BGRA** frame into a `sendonly` video track. `user_data` tags the frame; see [`docs/frame-metadata.md`](../../docs/frame-metadata.md). |
| `push_audio_frame(track_name, data, samples_per_channel, sample_rate=48000, num_channels=1)` | Push interleaved `int16` PCM into a `sendonly` audio track. |
| `await request_clip(duration_seconds) -> Clip` | Request a clip of the last N seconds of the session. |
| `await request_recording() -> Clip` | Request a clip covering the whole session so far. |
| `await upload_file(path) -> FileRef` | Upload a local file; pass the result as a command argument. |
| `status` (property) | `"disconnected" \| "connecting" \| "waiting" \| "ready"`. |
| `session_id` (property) | Current session ID, or `None` when disconnected. |

`Reactor` is also an async context manager (`async with Reactor(...) as r:`)
that disconnects and closes the handle on exit.

### Events (`r.on(event, handler)`)

| Event | Handler signature | Fires when |
|---|---|---|
| `status_changed` | `(status: str)` | The connection state changes. |
| `error` | `(err: ReactorError)` | A recoverable or fatal error occurs. |
| `message` | `(msg: dict)` | An application-scoped message arrives from the model. |
| `runtime_message` | `(msg: dict)` | A platform-scoped message arrives (capabilities, moderation, recording lifecycle). |
| `track_received` | `(name: str, mid: str \| None)` | A remote media track is announced. |
| `capabilities_received` | `(caps: dict)` | The runtime published its track/command capabilities. |
| `session_id_changed` | `(session_id: str \| None)` | The active session ID changes (`None` when cleared). |
| `frame` | `(data: bytes, width: int, height: int, frame_id: int, timestamp_us: int, user_data: bytes)` | A raw BGRA video frame arrives on a `recvonly` track. |
| `audio` | `(data: bytes, num_samples: int, sample_rate: int, channels: int)` | Decoded PCM audio arrives on a `recvonly` track. |

### Types

| Type | Fields |
|---|---|
| `Clip` | `session_id, kind, start_marker, end_marker, now_marker, predicted_ready_at_ms, playlist_url` |
| `FileRef` | `upload_id, name, mime_type, size` — pass as a command argument to reference an uploaded file. |
| `ReactorError` | `code, message, timestamp_ms, recoverable, component, retry_after_ms` |
| `ReactorFFIError` | Raised when an async FFI operation (`connect`, `request_clip`, ...) fails. |

---

## Samples

Runnable end-to-end scripts in [`examples/`](examples/), each driven by
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

Run `main.py` directly; every other example imports its sibling
`reactor_client.py` with a relative import, so run it as a module instead
(both from `sdks/python/`):

```bash
REACTOR_MODEL=my-model REACTOR_JWT=<token> python examples/main.py

REACTOR_MODEL=my-model REACTOR_JWT=<token> python -m examples.push_video --track video_input
```

---

## Development and Contributing

See the repo-wide [`CONTRIBUTING.md`](../../CONTRIBUTING.md) for dev setup,
code style, and how to open a pull request.

---

## License

Apache-2.0 — see [`LICENSE`](../../LICENSE).
