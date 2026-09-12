# Publishing and sending media

Publish the model's named sendonly slot before pushing frames. Publication belongs to the
client's track state, so two `Track` references for the same declaration see the same state.

```kotlin
reactor.setBitrate(startBps = 4_000_000, maxBps = 12_000_000)
reactor.connect()
val camera = reactor.track("camera").publish()
camera.setBitrate(maxBps = 8_000_000)
val capture = timeMicros()
camera.pushFrame(VideoFrame(bgra, width, height, userData = metadata), capture)
camera.unpublish()
```

`publicationState` distinguishes `UNPUBLISHED`, `PUBLISHING` and `PUBLISHED`; `published`
is true only for the last. A pending publish rejects pushes and a second publish. Repeating
publish on an already-published slot is idempotent. Leaving `ready`, replacing the native
handle, or removing/changing a declaration clears its publication. A late completion cannot
restore it or affect a newer attempt. Publish again after reconnect.

Cancelling the coroutine removes its awaiter, not the native operation. The slot stays
`PUBLISHING` until completion or a connection reset; a successful completion updates it even
if its original caller stopped waiting. Closing the client settles pending awaiters and
clears publications. A failed unpublish preserves the published state so callers can retry;
an already-unpublished slot needs no native unpublish call.

- `pushFrame(VideoFrame, captureTimeMicros?)` sends exact BGRA bytes. Width and height must
  be positive, fit the JVM array limit without overflow, and match the byte length exactly.
  Optional `userData` is forwarded unchanged, including embedded zero bytes. The remote
  model must declare support for metadata to receive it.
- Read `timeMicros()` once per capture and use that value for every video track representing
  the same moment. It is the engine clock, not Unix time, `System.nanoTime()`, or a received
  frame's sender timestamp. Capture time is a nonnegative signed 64-bit value. Omitting it
  lets the native push stamp the frame. `frameId` and `timestampMicros` describe received
  frames and are not used to stamp outgoing frames.
- `pushFrame(AudioFrame)` sends interleaved signed PCM16 at 8000, 16000, 24000, 32000, 44100
  or 48000 Hz, with one or two channels. Complete interleaved sample groups are required;
  JNI derives samples per channel from the array length. Empty arrays contain no media.
  Audio has no metadata or explicit capture-time parameter. Pace pushes at the capture rate;
  native buffering, resampling and shared synthetic audio-device behavior follow the
  [FFI contract](../../../crates/reactor-ffi/include/reactor_ffi.h).
- Direction, kind, publication state, format and timestamp mistakes are rejected before a
  push reaches the permissive native API. JNI repeats buffer-size/format checks before any
  native read and copies Java arrays instead of pinning them across FFI calls. Do not mutate
  arrays concurrently with a push.
- Connection and sender bitrate bounds are separate ceilings; the lower ceiling wins.
  Values are signed 32-bit bits/second: zero is a value, `-1` selects the engine default,
  and every other negative value is rejected. Connection bounds set before `connect` are
  applied before peer setup and reapplied when credentials cause handle replacement.
  Native code retains connection and per-track bounds across reconnects on the same handle.
  Reapply per-track bounds if a new native handle is created.

Tests use the production JNI entry points against a fake C FFI, driving completions on
foreign threads and examining the exact buffers and scalars sent to C. They cover delayed
publication, cancellation, stale completions, unpublish failures, all supported PCM formats,
metadata, timestamp boundaries and refusals. They run on desktop CheckJNI, Android arm64
and Linux ASan. Real-library smoke tests verify loading and lifecycle; negotiated production
media remains a later live-integration gate.
