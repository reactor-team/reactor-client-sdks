# Reactor Python SDK

[![PyPI: reactor-sdk](https://img.shields.io/pypi/v/reactor-sdk.svg?label=reactor-sdk)](https://pypi.org/project/reactor-sdk/)
[![PyPI Downloads](https://img.shields.io/pypi/dm/reactor-sdk.svg?label=downloads)](https://pypi.org/project/reactor-sdk/)
[![build](https://img.shields.io/github/actions/workflow/status/reactor-team/reactor-client-sdks/ci.yml?branch=main)](https://github.com/reactor-team/reactor-client-sdks/actions)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](https://github.com/reactor-team/reactor-client-sdks/blob/main/LICENSE)

Connect your Python app to a live [Reactor](https://reactor.inc) model: send
commands, receive real-time video and audio. Built for scripts, servers and
computer-vision pipelines, authenticating with your API key server-side.

## Install

```bash
pip install reactor-sdk            # Python 3.10+, no runtime dependencies
pip install "reactor-sdk[audio]"   # adds PortAudio, for the microphone/speaker helpers
```

### Supported platforms

Each release ships one wheel per platform, carrying a prebuilt `libreactor_ffi`
for it. There is no source distribution: the package is useless without that
native library, and building it needs a Rust toolchain and a libwebrtc download.

| Platform | Wheel | Requires |
|---|---|---|
| Linux x86_64 | `manylinux_2_34_x86_64` | glibc 2.34+ (Ubuntu 22.04, Debian 12, RHEL 9, Amazon Linux 2023) |
| Linux aarch64 | `manylinux_2_34_aarch64` | glibc 2.34+ |
| macOS arm64 | `macosx_11_0_arm64` | macOS 11+ |
| macOS x86_64 | `macosx_13_0_x86_64` | macOS 13+ — libwebrtc's floor on this architecture |
| Windows x86_64 | `win_amd64` | Windows 10+ |

Any interpreter 3.10 or newer works on all of them: the SDK reaches the library
through `ctypes` and links no libpython, so the wheels are tagged
`py3-none-<platform>` and there is nothing per-version to match.

Anything outside that table — musl distributions, glibc older than 2.34, 32-bit,
Windows on ARM — has no wheel, and **pip will not say so**: 0.8.0 and earlier
were a single `py3-none-any` wheel that installs anywhere, so pip walks back to
one of those and leaves you on an older SDK with a different API. Pin a floor to
turn that into an error:

```bash
pip install "reactor-sdk>=1.0"
```

To run somewhere with no wheel, build the library yourself and point the SDK at
it with `REACTOR_FFI_LIB` — see [Development](#development).

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
camera.push_frame(frame, capture_time_us=t)        # stamp when it was captured
camera.push_frame(pcm, sample_rate=48000)         # audio track: interleaved i16 PCM

camera.unpublish()
```

Several cameras capturing one moment are one moment: push them with the same
`capture_time_us` and that is what the far end reads, instead of the microseconds
apart the pushes happened to land. The value is a point on the engine's clock —
`time_micros()`, not `time.time()`.

```python
from reactor_sdk import time_micros

now = time_micros()
for camera, frame in views.items():
    camera.push_frame(frame, capture_time_us=now)
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

`Speaker` is wired up in [`pygame_app/`](https://github.com/reactor-team/reactor-client-sdks/tree/main/sdks/python/examples/pygame_app), which plays a model's audio track alongside its video.

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

One failure is not in that family: exchanging an `api_key` for a token raises
`AuthError`, which is a `RuntimeError` and not a `ReactorError`. It happens inside
`connect()` when the client was given a key rather than a `jwt`, so catch it
alongside:

```python
from reactor_sdk import AuthError

try:
    await reactor.connect()
except AuthError:
    ...                       # the key itself was refused, or the auth host is unreachable
```

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

Seven minimal examples in [`examples/`](https://github.com/reactor-team/reactor-client-sdks/tree/main/sdks/python/examples),
one per capability. Each adds exactly one call to the same spine — connect, give
the model what it needs, receive frames — so the diff against the first one is
the lesson. The same seven exist in every Reactor SDK.

| # | Script | Teaches |
|---|---|---|
| 01 | `01_connect_and_receive.py` | Connect, send the model's first command, read the reply, count frames. |
| 02 | `02_pause_and_resume.py` | Pause and resume a track — nothing is generated while it is paused. |
| 03 | `03_publish_track.py` | Publish a `sendonly` track and push tagged frames into it. |
| 04 | `04_multi_connection.py` | Two clients on one session, the second adopting it by id. |
| 05 | `05_record_clip.py` | Request a clip and download it. |
| 06 | `06_frame_metadata.py` | Read the per-frame trailer: frame id, the sender's timestamp, `user_data`. |
| 07 | `07_upload_image.py` | Upload a file and pass the `FileRef` into a command. |

```bash
export REACTOR_API_KEY=rk_...
uv run python examples/01_connect_and_receive.py
uv run python examples/05_record_clip.py --clip 5 --out clip.mp4
```

Options come from flags or from `REACTOR_API_KEY` / `REACTOR_JWT` /
`REACTOR_MODEL` / `REACTOR_API_URL` / `REACTOR_LOCAL`; `--help` on any of them
lists the rest. See the [examples README](https://github.com/reactor-team/reactor-client-sdks/blob/main/sdks/python/examples/README.md)
for what each one needs.

[`pygame_app/`](https://github.com/reactor-team/reactor-client-sdks/tree/main/sdks/python/examples/pygame_app)
is the opposite kind of sample: a whole application — live video, speaker
playback, and a control UI built from the model's capabilities. Standalone, with
its own [README](https://github.com/reactor-team/reactor-client-sdks/blob/main/sdks/python/examples/pygame_app/README.md).

## Development

```bash
mise run lint:python     # ruff check + format
mise run test:python     # pytest
mise run build:wheel     # cargo build --release, then a wheel with it bundled
```

Tests that need the compiled library skip without it, so `pytest` is clean on a
fresh checkout. `mise run build:wheel` without one produces a pure-Python wheel
with a warning — fine for an editable install, not for a release.

### The native library

At import time it is resolved in three places, in order: `REACTOR_FFI_LIB`, then
next to the installed package (where the wheels put it), then `target/release/`
in an enclosing checkout. On a platform with no wheel, `cargo build -p
reactor-ffi --release` and an install from source is the whole story, and
`REACTOR_FFI_LIB` points the installed SDK at a local build.

Rebuild it after pulling changes under `crates/`: a signature that moved in the
FFI but not in your build fails at the call rather than at load, so it looks like
a hang, not a version error.

See the repo-wide [`CONTRIBUTING.md`](https://github.com/reactor-team/reactor-client-sdks/blob/main/CONTRIBUTING.md) for the rest (DCO,
commit conventions, opening a PR).

## Documentation

The [full documentation](https://docs.reactor.inc/sdk-reference/using-the-sdk#python)
covers platform concepts and the other language SDKs.

## License

Apache-2.0 — see [`LICENSE`](https://github.com/reactor-team/reactor-client-sdks/blob/main/LICENSE).
