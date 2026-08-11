# Reactor SDK — Python

Async Python client for [Reactor](https://reactor.inc), wrapping the `reactor-ffi`
C ABI over ctypes. Connects to a model, exchanges commands over a data channel,
and sends or receives real-time video and audio over WebRTC.

```bash
pip install reactor-sdk
```

Requires Python 3.10+.

## Quickstart

```python
import asyncio
from reactor import Reactor

async def main():
    async with Reactor("https://api.reactor.inc", "my-model", jwt=token) as r:
        r.on("status_changed", lambda status: print("status:", status))
        r.on("message", lambda msg: print("message:", msg))

        await r.connect()
        r.send_command("hello", {"text": "hi"})
        await asyncio.sleep(10)

asyncio.run(main())
```

See [`examples/`](examples/) for sending video, sending audio, pausing tracks,
recording clips, and tagging frames with metadata.

## Events

Register handlers with `r.on(event, handler)`:

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
2. `libreactor_ffi.{dylib,so}` / `reactor_ffi.dll` next to the `reactor` package —
   where a released wheel puts it.
3. `target/release/` in an enclosing Cargo workspace, for development.

## Development

```bash
mise run lint:python     # ruff check + format
mise run test:python     # pytest
mise run build:wheel     # cargo build --release, then a wheel with it bundled
```

Tests that need the compiled library skip themselves when it is absent, so `pytest`
works on a fresh checkout. Building a wheel without one produces a pure-Python wheel
and warns; that is fine for an editable install and wrong for a release.

## License

Apache-2.0
