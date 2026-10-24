# Changelog

All notable changes to the Reactor Swift SDK are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/);
versioning follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.7.0] - 2026-10-23

### Added

- `Reactor(..., extraHeaders: [...])` sends extra HTTP headers on every
  coordinator and signaling request the client makes, including those of a
  stepped client. Reserved header names are rejected when the client connects.
- `Reactor.TokenOptions(extraHeaders: [...])` sends extra HTTP headers on the
  token request. Reserved header names fail the call.

## [1.6.0] - 2026-10-23

### Added

- Stepped generation. `Reactor(..., generation: .stepped)` asks for a session
  that generates one step at a time, and `try await reactor.step(state: ...,
  commands: [...])` runs one and returns a `StepResult`: per output track, its
  `rate` (frames per second for video, samples per second for audio, 0 when
  unspecified) and the frames it produced, each as `Data` with its `dtype`,
  `shape`, metadata and capture time; `elapsed`, the `Duration` the step's
  `generate()` took (or the time the model reported for the step); `files`, the
  `StepFile`s (name, content type, size) the model kept with the step, fetched
  over HTTP from the session's step results; and `error`, set when the step ran
  but the model reported a failure — such a step returns rather than throws.
  Every argument of `step` is optional: `step()` runs on the model's current
  state. `Track.pushFrame` buffers into the next step in such a session, up to
  one data-channel message's worth of frame bytes (the oldest frames drop first,
  across tracks); a push to a track the session does not take as input is
  dropped with a warning. A step's buffered frames belong to it whatever its
  outcome, sent or not. `clearStepInputs()` drops what is buffered now, and
  `stepTimeout:` bounds a step. `connect()` throws `generationModeMismatch` when
  the runtime did not start the session stepped, and `dataChannelChunkingRequired`
  when the data channel did not negotiate chunking; a step can throw `stepRefused`,
  `stepNotReady` (the session cannot take a step yet; the step did not run),
  `stepCommandFailed` (a state key or command handler failed; the step did not
  run), `stepSessionEnded` (the session ended first; whether the step ran is
  unknown), `stepResultTooLarge`, `messageTooLarge` or
  `unsupportedFrameEncoding`. New example: `09_stepped_generation`.
- Client stats now report where each command spends its time, stage by
  stage, on the way out (the SDK's own stages, then the data channel's
  `enqueue`, `queue_wait`, `send` and `stall`) and on the way back
  (`reassembly`, then the SDK's), each reading carrying the stages' time and
  command count since the previous one. They also name the encoder and decoder
  each video track runs, and whether it is the hardware one. Nothing changes in
  the public API.

### Changed

- The WebRTC engine moves from reactor-webrtc 0.22.1 to 0.24.2, on libwebrtc
  `webrtc-7907-a5ddff60-p13`. It does less work per SCTP packet, and a chunked
  data-channel frame reaches libwebrtc in one trip to the network thread
  instead of three, so commands leave sooner.

### Fixed

- `Track.pushFrame` without a `captureTimeUs` no longer stamps the frame with a
  capture time of 0. It now reaches the library unstamped, so a streamed session
  stamps it as it is pushed and a stepped session sends it with no capture time,
  rather than telling the model it was captured at 0.

## [1.5.0] - 2026-10-13

### Added

- `Reactor.TokenOptions(sessions: [...])` mints a token bound to sessions that
  already exist. A client that adopts a session it did not create needs such a
  token, because a scoped token reaches only the sessions it created or was
  bound to. Without `models` and `maxSessions`, the token can act only on those
  sessions and cannot create new ones.

### Fixed

- Command replies no longer stall for 100–600 ms every five minutes. The
  client gathered ICE candidates continually, so libwebrtc regathered on failed
  networks every five minutes, and the client waited on posting each new
  candidate to the coordinator before it delivered the next reply. The client
  now gathers candidates once, as browsers do.

## [1.4.0] - 2026-10-08

### Added

- `Reactor(..., autoResumeTracks: ...)` chooses whether the SDK resumes every receive-only track when
  it connects and reconnects. It defaults to on, which is what the SDK always
  did, so nothing changes unless you turn it off. The JS SDK already had it.

### Fixed

- A connection no longer loses every command and message it sends when the
  runtime negotiates data-channel chunking. A channel that opened while the
  session description was still being applied could decide it was not chunked
  while the runtime's end of it was, and from then on nothing sent on it
  arrived, with no error on either side. Both ends now always agree. Requires
  reactor-webrtc 0.22.1.

## [1.3.0] - 2026-10-06

### Added

- `Reactor.stats()`, an on-demand statistics snapshot for the live connection:
  RTT, jitter, packet loss, measured and estimated bitrates, the candidate pair
  carrying the media, and the engine's per-stream inbound, outbound and
  candidate-pair counters, as a `ConnectionStats` value. Same fields and rules
  as the other native SDKs: anything the engine has not measured yet is `nil`
  rather than zero, the two measured bitrates are `nil` on the first call and on
  one made less than 200 ms after the last, and the call throws `invalidState`
  unless the session is ready.
- While connected, the SDK reports its own view of connection quality to the
  runtime every 5 seconds over the control channel: one reading per track
  (audio and video, sent and received) with bitrate, packet loss, jitter,
  frame rate and size, keyframe requests and similar values, plus
  connection-wide readings such as the available bandwidth estimate,
  round-trip time and time to connect. It runs on its own timer, needs no
  configuration, and doesn't change the public API. A runtime that doesn't read these
  reports ignores them.

### Fixed

- Per-frame metadata stays on the frame it was sent with. When a frame was
  dropped before it was shown (a decode error, the wait for a keyframe, or a
  late frame), every later frame was handed its predecessor's metadata; it is
  now matched to the decoded frame itself. Requires reactor-webrtc 0.21.

## [1.2.0] - 2026-10-05

### Changed

- Commands larger than 256 KiB are sent instead of failing with
  `MESSAGE_TOO_LARGE`, up to 64 MiB, when the runtime negotiates data-channel
  chunking. The offer asks for it (`a=x-reactor-dc-chunking`); a runtime that
  does not answer it keeps today's 256 KiB limit, so nothing changes against
  one that has not been updated. Messages of every size also leave in fewer
  round trips (dcsctp's `max_burst` goes from 4 to 256). Requires
  reactor-webrtc 0.19.1.

## [1.1.0] - 2026-09-15

### Added

- Connection setup now asks for the two WARP opt-ins, SNAP
  (draft-hancke-tsvwg-snap) and SPED (draft-hancke-webrtc-sped), which together
  take up to three round trips off the time to the first frame. The SDK sends
  the offer, so it is the side that asks: the offer carries this side's SCTP
  INIT parameters, letting the data channel skip the cookie exchange, and the
  DTLS handshake rides inside the ICE binding requests instead of waiting for
  ICE to settle. A server that does not answer them negotiates the ordinary
  handshake, so nothing changes against one that has not been updated. Requires
  reactor-webrtc 0.18.

## [1.0.1] - 2026-09-10

### Fixed

- `Track.pushFrame()` now preserves the PCM capture rate and channel count through
  the shared FFI instead of treating every buffer as 48 kHz mono. Supports
  8, 16, 24, 32, 44.1 and 48 kHz input in mono or stereo, with local WebRTC
  resampling. Arbitrary chunks are assembled into 10 ms blocks; format changes
  and disconnect discard partial blocks (REA-6193).
- Unsupported capture rates and channel counts now raise a bad-request error
  in the SDK before reaching the FFI, instead of returning successfully while
  the native layer drops the audio.

## [1.0.0] - 2026-09-09

Initial release. Distributed as a SwiftPM binary target — an XCFramework
attached to this release, resolved automatically via
`.package(url: "https://github.com/reactor-team/reactor-client-sdks", from: "1.0.0")` —
for macOS (arm64, x86_64) and iOS 16+ (arm64 device and simulator).

### Added

- `Reactor`: connect/disconnect/reconnect/close, with status and error
  events. Commands (`sendCommand`), the request/reply message channel, and
  file/byte uploads.
- `Track` / `TrackList`: publish, unpublish, pause, and resume; receiving
  frames and the end-of-track trailer; `pushFrame` for a sendonly track,
  including its refusal cases (paused, unpublished, wrong direction). One
  `onFrame`/`pushFrame` pair for both media kinds, overloaded for
  `VideoFrame`/`AudioFrame` and for BGRA/`Samples` — `Track.kind` decides
  which overload applies, matching Python and C++.
- Recording: request a clip and download it once ready.
- Auth: exchange an API key for a session JWT.
- `ReactorMedia`, a separate product from `Reactor` so an app that only
  receives video needs no camera/microphone entitlement: `Camera`,
  `Microphone`, `Speaker`, and `ReactorVideoView`. Handles iOS capture
  orientation, the `visionOS` compatibility-mode no-camera case, and the
  capture-vs-render lock-ordering asymmetry documented in
  `sdks/swift/README.md`.
- Seven runnable examples under `Examples/`, matching the other three SDKs'
  capability matrix: connect and receive, upload an image, pause and
  resume, publish a track, multiple connections, record a clip, and frame
  metadata.
- An integration-test suite against a live `reactor/echo` model, run in CI
  both for the development build and, separately, against the exact
  XCFramework a release ships (including on the iOS Simulator).
