# Changelog

All notable changes to the Reactor Android SDK are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

`release-android.yml` refuses to publish a version bump that has no matching
`## [x.y.z]` heading below, so the entry is written by hand in the same pull
request as the bump.

## [Unreleased]

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
