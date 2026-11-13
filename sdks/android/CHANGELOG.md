# Changelog

All notable changes to the Reactor Android SDK are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

`release-android.yml` refuses to publish a version bump that has no matching
`## [x.y.z]` heading below, so the entry is written by hand in the same pull
request as the bump.

## [Unreleased]

### Fixed

- A model that declares no tracks can be connected to. Its session leaves the
  `tracks` key out of its capabilities, which the SDK required, so `connect()`
  failed to read the session; it now reads as a session with no tracks, and a
  stepped session connects over its data channels alone. Connecting to a
  model without tracks needs this release or later (1.2.0 and earlier fail).

## [1.2.0] - 2026-11-05

### Added

- **`ReactorOptions.maxMessageSize`** sets the largest data-channel message the
  client declares and accepts, from 256 KiB to 1 GiB. A stepped session
  returns each step's result in one message, so a model that raises its own
  limit can return steps larger than 64 MiB. A connection carries the smaller
  of the two sides' limits.

- **Client stats carry the same metrics as the JS SDK's**: `concealed_samples`
  and `total_samples_received` on a received audio track, `frames_encoded` on
  a sent video track, and `jitter_ms` on a sent track, the jitter the far end
  reports once its first receiver report arrives. They come from
  reactor-webrtc 0.26.0.

### Changed

- The WebRTC engine moves from reactor-webrtc 0.24.2 to 0.26.0, on the same
  libwebrtc (`webrtc-7907-a5ddff60-p13`).
- The client declares and accepts data-channel messages of up to 256 MiB, up
  from 64 MiB, so a model that raises its own limit
  (`runtime.webrtc.max_message_size` in its `reactor.yaml`) can return steps
  larger than 64 MiB. A connection carries the smaller of the two sides'
  limits, so against a runtime that keeps 64 MiB nothing changes. The memory
  for a message is only taken as it arrives.

### Fixed

- A session that closed before it was ready failed `connect()` with only
  "session entered terminal state Closed". The error now also names why the
  platform ended the session, when the platform recorded it: for example
  `Closed (START_REFUSED): generation 'stepped' needs a model that runs the
  default ReactorApp step loop ...` for a stepped connect to a model that
  cannot step. The error code is still `SESSION_TERMINAL`.

## [1.1.0] - 2026-10-28

### Added

- `Reactor.messages`: messages the model sends, as `{"type", "data"}` JSON
  objects, including the ones no call returns, such as state updates. 1.0.0
  delivered a command's reply only as the return value of `sendCommand`, so an
  Android app could not see anything the model sent on its own. Collect it
  before `connect()` to see every message.
- `Reactor.runtimeMessages`: what the platform sends about the session, such as
  recording lifecycle notices.

## [1.0.0] - 2026-10-27

First release: a Kotlin binding over the same native core as the other Reactor
SDKs, reached through JNI, for Android 8.0 (API 26) and newer, and for apps on
Kotlin 2.1 or newer.

- Published to Maven Central as `inc.reactor:reactor-sdk-android` and
  `inc.reactor:reactor-sdk-android-media`, with sources and javadoc, signed.
  The native libraries ride inside the AAR for `arm64-v8a` and `x86_64`, built
  for 16 KB page sizes.
- `Reactor`: connect to a model by creating a session or adopting one, with
  `status`, `errors` and `sessionId` as flows, `reconnect()` and `disconnect()`.
  Authenticate with a `jwt` from your backend, or with an `apiKey` that
  `connect()` exchanges for a token scoped to what it reaches.
  `Reactor.fetchJwt(apiUrl, apiKey, options, local)` is the same exchange on its
  own, for minting tokens to hand to clients you do not control.
- Tracks as the session declares them: receive video and audio frames from
  recvonly tracks, pause and resume them, and publish sendonly tracks and push
  frames into them. A push the native layer would silently drop (an undeclared
  track, the wrong direction, one not yet published) throws instead, naming the
  fix.
- Correlated commands and their replies, the model's command schema, and an
  on-demand connection statistics snapshot.
- Stepped generation: `ReactorOptions(generation = Generation.STEPPED)` and
  `reactor.step(state, commands)`, which returns what the model produced on
  each output track as arrays, with the files it kept and its error. Frames
  pushed into an input track ride the next step.
- Uploads from a file, from bytes in memory, or from a `content://` URI, as a
  `FileRef` to pass into a command. Recording and clip requests, and clip
  downloads that outlive the client.
- Typed errors, one class per code, each carrying `code`, `message`,
  `recoverable`, `status`, `operation` and `retryAfterMs`.
- `reactor-sdk-android-media`: camera, microphone, speaker and renderer
  adapters on the platform's own classes, so the core artifact opens no
  hardware and declares no permissions.
- `Reactor.SDK_VERSION` is the AAR's own version, which is what the coordinator
  records as `client_info.sdk_version`.
