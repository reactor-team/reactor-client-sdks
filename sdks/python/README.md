# Reactor Python SDK

[![PyPI: reactor-sdk](https://img.shields.io/pypi/v/reactor-sdk.svg?label=reactor-sdk)](https://pypi.org/project/reactor-sdk/)
[![PyPI Downloads](https://img.shields.io/pypi/dm/reactor-sdk.svg?label=downloads)](https://pypi.org/project/reactor-sdk/)
[![build](https://img.shields.io/github/actions/workflow/status/reactor-team/reactor-client-sdks/ci.yml?branch=main)](https://github.com/reactor-team/reactor-client-sdks/actions)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](../../LICENSE)

Connect your Python app to a live [Reactor](https://reactor.inc) model: send
commands, receive real-time video and audio. Built for scripts, servers and
computer-vision pipelines, authenticating with your API key server-side.

## Install

```bash
pip install reactor-sdk            # Python 3.10+, no runtime dependencies
pip install "reactor-sdk[audio]"   # adds PortAudio, for the microphone/speaker helpers
```

## Quickstart

```python
import asyncio
from reactor_sdk import Reactor, ReactorStatus

API_KEY = "..."


async def main():
    async with Reactor(model_name="my-model", api_key=API_KEY) as r:

        @r.on_status(ReactorStatus.READY)
        def on_ready(status):
            asyncio.create_task(r.send_command("set_prompt", {"prompt": "a forest at dawn"}))

        await r.connect()

        @r.track("video_output").on_frame
        def render(frame):
            print(f"frame: {frame.shape}")

        await asyncio.sleep(30)   # keep the session open while frames arrive


asyncio.run(main())
```

## Tracks

A model declares its tracks: each has a name, a kind (`video` or `audio`) and a
direction — `sendonly` you push into, `recvonly` you receive from.

### Getting one

```python
reactor.track("video_output")     # by name
reactor.tracks                    # every declared track, as a list
```

`reactor.tracks` is a list with filters, for when you would rather describe the
track than name it:

```python
reactor.tracks.with_kind("video")                        # or TrackKind.VIDEO
reactor.tracks.with_direction("recvonly")                # or TrackDirection.RECVONLY
reactor.tracks.with_kind("audio").with_direction("recvonly").one()
```

Filters chain in either order. `one()` returns the single match, or raises naming
the candidates.

Naming a track before `connect()` works: register handlers first, and the name is
checked against the model's declaration as soon as it arrives.

### Receiving

```python
output = reactor.track("video_output")

@output.on_frame
def render(frame):                # RGB numpy array, (height, width, 3)
    ...

@output.on_raw_frame              # the same frames, unconverted
def forward(bgra, width, height, frame_id, timestamp_us, user_data):
    ...

await output.pause()
await output.resume()
```

Only that track's frames reach the handler. `on_frame` converts to a numpy array
and needs numpy; `on_raw_frame` hands over the bytes WebRTC already decoded — BGRA,
or interleaved i16 PCM on an audio track — and needs nothing.

To react as tracks arrive instead of naming them:

```python
@reactor.on_track
def arrived(track):               # fires once per declared track
    print(track.name, track.kind, track.direction)
```

### Sending

```python
camera = await reactor.track("camera").publish()   # or: await reactor.publish_track("camera")

camera.push_frame(frame)                          # numpy array: shape carries the size
camera.push_frame(bgra, width=640, height=480)    # bytes: size spelled out
camera.push_frame(frame, user_data=b"seq=1")      # tag the frame's metadata
camera.push_frame(pcm, sample_rate=48000)         # audio track: interleaved i16 PCM

camera.unpublish()
```

One `push_frame` and one `on_frame` for both kinds — the track knows which it is.
Asking for something its direction does not have raises, rather than doing nothing.

Publishing is what puts a sender behind the slot, so `push_frame` before it raises
`InvalidStateError` rather than accepting frames nothing carries. A publish lasts
as long as the session: a reconnect resumes recvonly tracks and nothing else, so
publish again after one — `track.published` says which side of that you are on.

### Audio devices

The SDK opens no audio device: a sendonly audio track carries only the PCM you
push, and a model's audio arrives at `on_frame` for you to play. Two helpers do
that, and need `reactor-sdk[audio]`:

```python
from reactor_sdk.audio_devices import Microphone, Speaker

speaker = reactor.tracks.with_kind("audio").with_direction("recvonly").one()
mic = await reactor.track("mic").publish()

with Speaker(speaker), Microphone(mic):
    await asyncio.sleep(30)
```

- `Speaker` plays a recvonly audio track, buffering the jitter between what arrives
  and what the device asks for. Feed it directly with `submit()` if the PCM comes
  from elsewhere.
- `Microphone` captures the default input device into a sendonly track. One at a
  time: every local audio track is fed from one shared device.
- Both raise when PortAudio is missing, so catch that if you would rather run
  silent.

[`echo_audio.py`](examples/echo_audio.py) runs both together.

## Errors

A failed call raises an exception with a code you can branch on:

```python
from reactor_sdk import ConflictError, ReactorError, UnauthorizedError

try:
    await reactor.connect()
except UnauthorizedError:
    ...                       # token missing, expired or out of scope
except ConflictError:
    ...                       # a previous run left the session orphaned
except ReactorError as error:
    if error.recoverable:     # a timeout, a 5xx, a transport that dropped
        await reactor.reconnect()
    raise
```

Every exception carries `.code`, `.message`, `.recoverable`, `.status`,
`.operation` and `.retry_after_ms`:

```python
except RateLimitedError as error:
    await asyncio.sleep((error.retry_after_ms or 1000) / 1000)
```

`ReactorError` is the base, and also what `on_error` hands you — the same object a
failed call raises, plus `timestamp_ms`:

```python
@reactor.on_error
def log(error):
    print(error.code, error.operation, error.recoverable)
```

Subclasses: `InvalidStateError`, `DisconnectedError`, `NetworkError`,
`RequestTimeoutError`, `TransportError`, `UnauthorizedError`, `NotFoundError`,
`ConflictError`, `RateLimitedError`, `BadRequestError`, `ServerError`,
`VersionMismatchError`, `DecodeError`, `SessionTerminalError`,
`MessageTooLargeError`, `AbortedError`.

A command the model itself rejects reports the model's own code, which this package
cannot enumerate — match on `error.code` for anything outside that list.

## Recordings

```python
await reactor.download_clip(10, "clip.ts")    # last 10 seconds, streamed to disk
await reactor.download_recording("full.ts")   # whole session, streamed to disk

data = await reactor.download_clip(10)        # no path: the assembled bytes
```

- With a path the download streams straight to the file. Without one the whole clip
  is held in memory — fine for seconds, not for a session.
- The bytes are MPEG-TS, not MP4. `ffplay`, VLC and mpv play it as-is; remux with
  `ffmpeg -i clip.ts -c copy clip.mp4` if you need the container.
- `on_progress=lambda done, total: ...` follows the download.

`download_clip()` is `request_clip(seconds)` plus the download. Use the two-step
form to inspect the `Clip` first — its markers, `session_id`,
`predicted_ready_at_ms` — or to decide whether to download at all:

```python
clip = await reactor.request_clip(10)
await download_clip(clip, "clip.ts")          # the module-level function
```

## Samples

Runnable scripts in [`examples/`](examples/), driven by `REACTOR_API_URL` /
`REACTOR_MODEL` / `REACTOR_JWT` / `REACTOR_LOCAL` (see
[`reactor_client.py`](examples/reactor_client.py)):

| Script | Demonstrates |
|---|---|
| [`main.py`](examples/main.py) | Connect, list the model's tracks, send a command, disconnect. |
| [`push_video.py`](examples/push_video.py) | Stream generated frames into a `sendonly` video track. |
| [`push_audio.py`](examples/push_audio.py) | Stream a sine tone, a WAV file, or the microphone into a `sendonly` audio track. |
| [`echo_audio.py`](examples/echo_audio.py) | Full audio duplex: microphone out, the model's audio to the speakers. |
| [`pause_resume.py`](examples/pause_resume.py) | Pause and resume a `recvonly` track, counting only that track's frames. |
| [`record.py`](examples/record.py) | Request a clip or a full-session recording and download it. |
| [`frame_metadata.py`](examples/frame_metadata.py) | Read the per-frame metadata trailer off an incoming track. |
| [`frame_metadata_roundtrip.py`](examples/frame_metadata_roundtrip.py) | Tag outgoing frames and match the ones that come back. |
| [`metadata_publisher.py`](examples/metadata_publisher.py) | Publish tagged frames with no UI — pair with `pygame_app/`. |
| [`pygame_app/`](examples/pygame_app/) | Live video, speaker playback, and a control UI built from the model's capabilities. |

Every example except `main.py` and `pygame_app/` imports its sibling
`reactor_client.py`, so run those as modules (from `sdks/python/`):

```bash
REACTOR_MODEL=my-model REACTOR_JWT=<token> python examples/main.py
REACTOR_MODEL=my-model REACTOR_JWT=<token> python -m examples.push_video --track video_input
```

`pygame_app/` is standalone — see its own [README](examples/pygame_app/README.md).

## Development

```bash
mise run lint:python     # ruff check + format
mise run test:python     # pytest
mise run build:wheel     # cargo build --release, then a wheel with it bundled
```

Tests that need the compiled library skip without it, so `pytest` is clean on a
fresh checkout. `mise run build:wheel` without one produces a pure-Python wheel
with a warning — fine for an editable install, not for a release.

Rebuild the native library after pulling changes under `crates/`:
`cargo build -p reactor-ffi --release`. A signature that moved in the FFI but not
in your build fails at the call rather than at load, so it looks like a hang, not a
version error.

See the repo-wide [`CONTRIBUTING.md`](../../CONTRIBUTING.md) for the rest (DCO,
commit conventions, opening a PR).

## Documentation

The [full documentation](https://docs.reactor.inc/sdk-reference/using-the-sdk#python)
covers platform concepts and the other language SDKs.

## License

Apache-2.0 — see [`LICENSE`](../../LICENSE).
