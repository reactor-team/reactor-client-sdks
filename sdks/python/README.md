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

Neither has an example yet — the samples below are video-only. Until one lands,
the docstrings on `Speaker` and `Microphone` are the reference.

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
await reactor.download_clip(10, "clip.mp4")   # last 10 seconds, streamed to disk
await reactor.download_recording("full.mp4")  # whole session, streamed to disk

data = await reactor.download_clip(10)        # no path: the assembled bytes
```

- With a path the download streams straight to the file. Without one the whole clip
  is held in memory — fine for seconds, not for a session.
- The bytes are a fragmented MP4: the playlist's `#EXT-X-MAP` init segment, then
  its `.m4s` fragments. `ffplay`, VLC and mpv play the result as-is; a player
  wanting a faststart moov takes
  `ffmpeg -i clip.mp4 -c copy -movflags +faststart out.mp4`.
- A clip cut from mid-session keeps its original timestamps, so its reported
  duration runs from the session's start rather than from the clip's.
- `on_progress=lambda done, total: ...` follows the download, counting the init
  segment as its first part.

`download_clip()` is `request_clip(seconds)` plus the download. Use the two-step
form to inspect the `Clip` first — its markers, `session_id`,
`predicted_ready_at_ms` — or to decide whether to download at all:

```python
clip = await reactor.request_clip(10)
await download_clip(clip, "clip.mp4")         # the module-level function
```

## Connection statistics

```python
stats = await reactor.get_stats()
print(f"{stats.rtt_ms:.0f} ms, {stats.incoming_bitrate_bps or 0:,.0f} bps in")
```

- The two measured bitrates are derived **against the previous call**, so the first
  one after connecting reports `None` for them, and so does a call made less than
  200 ms after the last. Everything else is on every call. Poll a couple of seconds
  apart for a continuous reading.
- Every field that the engine has not measured yet is `None`, never zero — no RTT
  yet is not a zero-latency link.
- `stats.candidate_type` is `"relay"` when the session is going through TURN,
  which is the first thing worth knowing when latency is bad.
- `stats.inbound`, `stats.outbound` and `stats.candidate_pairs` carry the engine's
  own per-stream report: SSRCs, stream kinds, frame counters, NACK counts,
  retransmissions, decode time, and each candidate pair's own totals.
- Raises `InvalidStateError` unless the session is ready.

These are the same numbers the
[JS SDK](https://docs.reactor.inc/sdk-reference/types#connectionstats) reports for
the same connection: the same candidate pair (the one ICE nominated), the same
byte counters, the same video stream. Two deliberate differences:

- **An audio-only session still reports `jitter_s` and `packet_loss_ratio`.** Both
  come from the received video stream, as in the browser; with no video stream
  this falls back to the receive streams there are, where the browser reports
  nothing.
- **`inbound`, `outbound`, `candidate_pairs` and `relay_protocol` are extra.** The
  browser's own report has most of that; its SDK does not surface it.

## Samples

Seven minimal examples in [`examples/`](https://github.com/reactor-team/reactor-client-sdks/tree/main/sdks/python/examples),
one per capability. Each adds exactly one call to the same spine — connect, give
the model what it needs, receive frames — so the diff against the first one is
the lesson. The same seven exist in every Reactor SDK.

| # | Script | Teaches |
|---|---|---|
| 01 | `01_connect_and_receive.py` | Connect, send the model's first command, read the reply, count frames. |
| 02 | `02_upload_image.py` | Upload a file and pass the `FileRef` into a command. |
| 03 | `03_pause_and_resume.py` | Pause and resume a track — nothing is generated while it is paused. |
| 04 | `04_publish_track.py` | Publish a track and push tagged frames into it. |
| 05 | `05_multi_connection.py` | Two clients on one session, the second adopting it by id. |
| 06 | `06_record_clip.py` | Request a clip and download it. |
| 07 | `07_frame_metadata.py` | Read the per-frame trailer: frame id, the sender's timestamp, `user_data`. |

Running them from a checkout needs the native library built first — see
[Before the first run](https://github.com/reactor-team/reactor-client-sdks/blob/main/sdks/python/examples/README.md#before-the-first-run).

```bash
export REACTOR_API_KEY=rk_...
uv run python examples/01_connect_and_receive.py
uv run python examples/06_record_clip.py 5 clip.mp4

pip install pygame                                        # for the window
REACTOR_SHOW=1 uv run python examples/04_publish_track.py  # sent | received
```

`REACTOR_SHOW=1` puts the stream in a window, which is the only way to see that a
model did the right thing rather than merely produce frames.

Configuration is environment-only — `REACTOR_API_KEY` / `REACTOR_JWT` /
`REACTOR_MODEL` / `REACTOR_API_URL` / `REACTOR_LOCAL` / `REACTOR_SECONDS` /
`REACTOR_SHOW` — and each example reads what it needs at the top of the file. See
the [examples README](https://github.com/reactor-team/reactor-client-sdks/blob/main/sdks/python/examples/README.md).

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
