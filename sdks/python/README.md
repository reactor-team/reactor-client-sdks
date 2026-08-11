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

### Handlers run on a native thread

Handlers are invoked from threads inside the native library, **not** from your
asyncio event loop. Two consequences:

* Do not touch asyncio state directly from a handler. `asyncio.Event.set()`,
  `Queue.put_nowait()` and friends are not thread-safe; go through
  `loop.call_soon_threadsafe(...)`.
* Blocking is safe, but you pay for it in dropped data rather than in stalled
  WebRTC. Each stream has its own delivery thread: block in a `frame` handler and
  you keep getting the newest frame while the ones in between are dropped; block
  in `audio` and you lose samples once its short queue fills. Control events and
  command results share a third thread with an unbounded queue, so those wait for
  you instead of being dropped.

```python
loop = asyncio.get_running_loop()
ready = asyncio.Event()

def on_status(status: str) -> None:
    if status == "ready":
        loop.call_soon_threadsafe(ready.set)

r.on("status_changed", on_status)
```

## Locating the native library

The wheel ships without the compiled library; it is resolved at first use, in
order:

1. `REACTOR_FFI_LIB` — absolute path to the library.
2. `libreactor_ffi.{dylib,so}` / `reactor_ffi.dll` next to the `reactor` package.
3. `target/release/` in an enclosing Cargo workspace (for development).

To build it from a checkout of this repository:

```bash
cargo build -p reactor-ffi --release
```

## Development

```bash
mise run lint:python     # ruff check + format
mise run test:python     # pytest
```

Tests that need the compiled library skip themselves when it is absent, so
`pytest` works on a fresh checkout.

## License

Apache-2.0
