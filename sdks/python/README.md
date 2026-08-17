# Reactor Python SDK

[![PyPI: reactor-sdk](https://img.shields.io/pypi/v/reactor-sdk.svg?label=reactor-sdk)](https://pypi.org/project/reactor-sdk/)
[![PyPI Downloads](https://img.shields.io/pypi/dm/reactor-sdk.svg?label=downloads)](https://pypi.org/project/reactor-sdk/)
[![build](https://img.shields.io/github/actions/workflow/status/reactor-team/reactor-client-sdks/ci.yml?branch=main)](https://github.com/reactor-team/reactor-client-sdks/actions)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](../../LICENSE)

Use this SDK to connect your Python app to a live [Reactor](https://reactor.inc)
model: send commands and receive real-time video and audio back. Built for
scripts, servers, and computer-vision pipelines — it authenticates directly
with your API key, server-side.

## Getting Started

```bash
pip install reactor-sdk
```

Requires Python 3.10+.

## Usage Example

```python
import asyncio
from reactor_sdk import Reactor, ReactorStatus

API_KEY = "..."  # Insert your API key here.


async def main():
    async with Reactor(model_name="my-model", api_key=API_KEY) as r:

        @r.on_status(ReactorStatus.READY)
        def on_ready(status):
            asyncio.create_task(r.send_command("set_prompt", {"prompt": "a forest at dawn"}))

        await r.connect()

        # The model's video output, whatever it is called.
        output = r.tracks.with_direction("recvonly").with_kind("video").one()

        @output.on_frame
        def render(frame):
            print(f"frame: {frame.shape}")

        await asyncio.sleep(30)  # keep the session open while frames arrive


asyncio.run(main())
```

## Tracks

A model declares its media tracks — a name, a kind (`video` or `audio`) and a
direction (`sendonly`, which you push into, or `recvonly`, which you receive
from). `reactor.tracks` is that declaration, and `reactor.track(name)` is one of
them as a `Track`:

```python
for track in reactor.tracks:
    print(track.name, track.kind, track.direction)   # e.g. output video recvonly
```

A `Track` knows which slot it is, so there is one `push_frame` and one `on_frame`
rather than a video and an audio version of each, and asking for something the
direction does not have is an error rather than a silent no-op:

```python
# Sending — publish the slot, then push into it.
camera = await reactor.publish_track("camera")
camera.push_frame(frame)                       # an RGB numpy array carries its own size
camera.push_frame(bgra, width=640, height=480) # raw bytes need it spelled out
camera.unpublish()

# Receiving — only this track's frames reach the handler.
output = reactor.track("output")

@output.on_frame
def render(frame):                             # RGB numpy array, (height, width, 3)
    ...

await output.pause()
await output.resume()
```

`on_frame` converts each frame into a numpy array. Use `on_raw_frame` for the
same frames as bytes — the arguments the client-wide `on("frame", …)` carries —
when the conversion is not wanted. Both get frames WebRTC has already decoded
(BGRA pixels, or interleaved i16 PCM); the difference is only the conversion:

```python
@output.on_raw_frame
def forward(bgra, width, height, frame_id, timestamp_us, user_data):
    ...
```

### Finding a track

`reactor.tracks` is a list, so it iterates and indexes like one — with filters that
chain when you would rather describe the track than name it:

```python
reactor.tracks                                              # all of them
reactor.tracks.with_kind(TrackKind.VIDEO)                   # or "video"
reactor.tracks.with_direction(TrackDirection.RECVONLY)      # or "recvonly"

# The one you mean, with an error naming the candidates if there is not exactly one.
output = reactor.tracks.with_direction("recvonly").with_kind("video").one()
```

The filters compose in either order, and a track whose kind the session has not
declared yet matches neither.

There is no client-wide `reactor.on_frame`: it was removed in 0.9.0. It only ever
worked for video — there was no `on_audio` counterpart — and a single handler fed
every recvonly video track cannot tell them apart, which is the case the `Track`
object exists for. `on("frame", …)` and `on("audio", …)` remain for raw
client-wide bytes; use a track's `on_frame` for decoded frames, and `.one()` above
when you do not want to hardcode the name.

**No audio device is ever opened.** A sendonly audio track carries only the PCM
you push into it, and a model's audio arrives at `on_frame` for you to play with
whatever you like — nothing is captured from your microphone or played through
your speakers on your behalf.

Naming a track before `connect()` is fine: the session has not declared anything
yet, so handlers can be registered first and the name is checked as soon as the
declaration arrives.

The name-based calls — `publish_track`, `pause_track`, `push_video_frame`,
`push_audio_frame`, `on("frame", …)` — all still work exactly as before. The one
removal is `reactor.on_frame`; see "Finding a track" above for what replaces it.

## Errors

A failed operation raises an exception carrying a code you can branch on, rather
than only a sentence you can print:

```python
from reactor_sdk import ReactorError, UnauthorizedError, ConflictError

try:
    await reactor.connect()
except UnauthorizedError:
    ...                      # the token is missing, expired or out of scope
except ConflictError:
    ...                      # a previous run left the session orphaned
except ReactorError as error:
    if error.recoverable:    # a timeout, a 5xx, a transport that dropped
        await reactor.reconnect()
    else:
        raise
```

Every exception has `.code`, `.message`, `.recoverable`, `.status` (when the
failure came from an HTTP one), `.operation` (which call failed) and
`.retry_after_ms` (the server's `Retry-After`, when it sent one):

```python
except RateLimitedError as error:
    await asyncio.sleep((error.retry_after_ms or 1000) / 1000)
```

`ReactorError` is the base of all of them, so `except ReactorError` still
catches everything. The classes are `InvalidStateError`, `DisconnectedError`,
`NetworkError`, `RequestTimeoutError`, `TransportError`, `UnauthorizedError`,
`NotFoundError`, `ConflictError`, `RateLimitedError`, `BadRequestError`,
`ServerError`, `VersionMismatchError`, `DecodeError`, `SessionTerminalError`,
`MessageTooLargeError` and `AbortedError`.

**One class.** The `on_error` event hands you the exact same `ReactorError` a
failed call raises — not a separate type that happens to agree — plus
`timestamp_ms`, only ever set here:

```python
@reactor.on_error
def log(error):                  # a ReactorError — an UnauthorizedError, etc.
    print(error.code, error.operation, error.recoverable)
```

A command or a control request the model itself rejects reports the model's own
code, which this package cannot enumerate — those raise `ReactorError` with
`.code` set to whatever arrived, so match on `error.code` for anything not in the
list above.

## Recordings

`request_clip(seconds)` and `request_recording()` return a `Clip` naming an HLS
playlist that expires — Reactor does not host clips. `download_clip()` fetches
every segment it names and hands you the concatenated bytes:

```python
clip = await reactor.request_clip(10)
data = await download_clip(clip, "clip.ts")
```

It's the interleaved MPEG-TS bytes, not an MP4 — playable as-is by most players
(`ffplay`, VLC, mpv); remux with `ffmpeg -i clip.ts -c copy clip.mp4` if you need
that container specifically. Pass `on_progress=lambda done, total: ...` to track
it, and omit the path to get the bytes back without writing a file.

## Documentation & Resources

See the [full documentation](https://docs.reactor.inc/sdk-reference/using-the-sdk#python) for platform
concepts and the other language SDKs.

## Samples

Runnable scripts in [`examples/`](examples/), each driven by
`REACTOR_API_URL` / `REACTOR_MODEL` / `REACTOR_JWT` / `REACTOR_LOCAL`
environment variables (see [`reactor_client.py`](examples/reactor_client.py)):

| Script | Demonstrates |
|---|---|
| [`main.py`](examples/main.py) | Minimal connect → list the model's tracks → send a command → disconnect. |
| [`push_video.py`](examples/push_video.py) | Stream generated frames into a `sendonly` video track. |
| [`push_audio.py`](examples/push_audio.py) | Stream a sine tone or a WAV file into a `sendonly` audio track. |
| [`pause_resume.py`](examples/pause_resume.py) | Pause and resume a `recvonly` track subscription, counting only that track's frames. |
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

## License

Apache-2.0 — see [`LICENSE`](../../LICENSE).
