# Changelog

All notable changes to the Reactor C++ SDK (`reactor-sdk`) are documented
here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/);
versioning follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.0.0] - 2026-09-04

The first release of the SDK, so this entry is the surface itself rather than
a delta: nothing before it was published, and there is nothing to upgrade
from.

A C++17 client for real-time generative video models over WebRTC, built on
`libreactor_ffi` — the same Rust core the Python SDK binds, so the two agree
on the protocol, the error codes and what a failure means.

### Added

- **Sessions.** `Reactor{model, ApiKey}` or `Reactor{model, Jwt}`, then
  `connect()`, `reconnect()` and `disconnect()`. Async calls return
  `std::future` and failures arrive as exceptions from `.get()`, so a caller
  can catch a specific one or a whole class of them. `status()`,
  `session_id()` and `on_status()` say where the session is.

- **Commands.** `send_command()` resolves to the model's own reply, so a
  command can be read and acted on rather than fire-and-forgotten.
  `request_schema()` returns what the model declares it accepts, and
  `upload_file()` / `upload_bytes()` hand the platform a file to pass into a
  command by reference, so the bytes cross the wire once.

- **Multiple connections on one session.** `connect({.session_id = …})`
  attaches to a session that already exists instead of creating one;
  `connection_id` adopts a connection a backend registered for it.

- **Tracks, asked for by name.** `client.track("main_video")`, with
  `client.tracks()` and its `with_kind` / `with_direction` filters for
  discovering what a session declared. `on_frame()` delivers BGRA video and
  `on_audio()` interleaved int16 PCM, inline on the library's delivery thread,
  and blocking in a handler *is* the backpressure, and the FFI then keeps the
  newest video frame rather than growing a queue. `pause()` and `resume()`
  stop and restart generation on a track.

- **Frame metadata.** Every incoming frame carries a trailer beside the
  pixels: `frame_id`, the capture `timestamp_us`, and the `user_data` the
  model tagged it with.

- **Sending media.** `publish()` puts a sender behind an input slot, then
  `push_frame()` and `push_audio()` feed it. Tracks are synchronised by
  sharing a capture time, so `time_micros()` is read once per produced moment
  and stamped on each of them; `unpublish()` needs no round trip.

- **Events on a thread the SDK owns.** Control events run one at a time, so a
  handler never runs on a library thread and never races another handler. A
  host with a loop of its own takes them through `Options::executor`, and
  futures are never settled through it, so `connect().get()` on that same loop
  cannot deadlock. Every subscription is RAII: hold the `Subscription` for as
  long as the handler should live, or `detach()` it.

- **Real audio devices**, opt-in through a `reactor::sdk_audio` target of its
  own: `Speaker` plays a received track and reports `dropped_ms()` and
  `under_runs()` separately, because a device slower than the stream and a
  stream slower than the device are different problems. The core stays pinned
  to the synthetic audio module, so nothing on the mandatory path can open a
  device.

- **Recordings.** `request_clip(seconds)` and `request_recording()` return a
  `Clip` once the platform accepts the request; `download()` is the call that
  waits, and what bounds it is the session still being alive rather than a
  number of seconds — pass `DownloadOptions::ready_timeout_seconds` for a
  wall-clock bound anyway.

- **Bandwidth control.** `set_bitrate()` on the session sets the connection's
  budget — the one that lifts WebRTC's 2.5 Mbps video default — and on a track
  caps that track. The bounds outlive a reconnect.

- **Connection statistics.** `get_stats()` reports RTT, jitter, loss, the two
  measured bitrates and `candidate_type` (`"relay"` says the session is going
  through TURN), plus the engine's own per-stream `inbound`, `outbound` and
  `candidate_pairs`. The shared fields are measured the way the JS SDK
  measures them — the same candidate pair, the one carrying media, and the
  same video stream — so the two SDKs report the same number for the same
  connection. An empty `std::optional` means the engine has not measured that
  field yet, which is not zero.

- **Errors as types.** One `reactor::ReactorError` base and seventeen
  subclasses over the codes the core defines, and the same type is what a
  failed call throws *and* what `on_error()` delivers. `recoverable()` is the
  property to branch on when the specific code does not matter; codes are
  open-ended, so a command the model refuses arrives on `ReactorError` itself
  with `code()` set to the model's own.

- **Refusals where the native layer would no-op.** Pushing into a track that
  does not exist, or that points the other way, or that was never published
  reaches the FFI, finds nothing to do, and returns — leaving a caller pushing
  at 30fps into nothing. Each of those throws instead, with the fix in the
  message, as does a pixel buffer that is not `width * height * 4` and
  anything attempted after the session leaves `Ready`.

- **Prebuilt archives for five platforms**: Linux x86_64 and aarch64 (glibc
  2.34+), macOS arm64 (11+) and x86_64 (13+), and Windows x86_64. Each is an
  install tree — the headers, `libreactor_sdk`, a CMake package config, and
  `libreactor_ffi` beside them — so `find_package(reactor-sdk)` and one linked
  target carry the include paths, the C++17 requirement, `nlohmann_json` and
  the native library. Every platform's floor is verified at release time
  rather than promised.
