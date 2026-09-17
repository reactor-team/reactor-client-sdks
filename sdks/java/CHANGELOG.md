# Changelog

All notable changes to the Reactor Java SDK (`inc.reactor:reactor-sdk`) are
documented here. It starts empty, before there is anything to release, so the
habit exists before the pressure to skip it does.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/);
versioning follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.0.0] - 2026-09-17

Initial release. Published to Maven Central as `inc.reactor:reactor-sdk`,
built on the [Foreign Function & Memory API][ffm] — no JNI, no JNA, and no
native code of its own. Requires JDK 22 or newer; runs on Linux (x86_64,
aarch64), macOS (arm64, x86_64) and Windows (x86_64). Every platform's
native library comes with it and the SDK loads the one it needs, so one
dependency line is the whole setup on Gradle and on Maven alike.

### Added

- `Reactor`: connect, disconnect, reconnect and close, with status and error
  events. Commands (`sendCommand`), the request/reply message channel, the
  model's request schema, session statistics, and file and byte uploads.
- `Track` / `TrackList`: publish, unpublish, pause and resume; receiving
  frames and the end-of-track trailer; `pushFrame` for a sendonly track and
  every refusal that goes with it — a name the session never declared, the
  wrong direction, a frame pushed before `publish()` or while paused, and
  raw bytes whose length does not match `width * height * 4`. One
  `onFrame`/`pushFrame` pair covers both media kinds, overloaded on
  `VideoFrame`/`AudioFrame`, matching Python, C++ and Swift.
- Recording: request a clip, watch it become ready, and download it. A
  download outlives the client that started it, as the FFI documents, and a
  client closed mid-download settles the caller's future rather than leaving
  it unresolved.
- Auth: exchange an API key for a session JWT.
- `JsonValue`, a sealed interface for the arbitrary payloads commands and
  messages carry. The SDK's public API depends on no JSON library, because
  Jackson's live 2.x/3.x split — different group IDs and different packages —
  makes embedding either one in a client library's API a choice imposed on
  every consumer.
- `reactor-sdk-audio`, optional: `Microphone` and `Speaker`. Nothing that
  opens audio hardware sits on the mandatory import path, so a server-side
  consumer never pulls in a capture device.
- `reactor-sdk-jackson`, optional: `JsonValue` ↔ Jackson `JsonNode`
  adapters, for consumers that already have Jackson on the classpath.
- `reactor-sdk-natives`: one jar carrying every platform's library, which
  `reactor-sdk` depends on, plus one classified jar per platform for a build
  that wants exactly one. One dependency line is enough on Gradle and on
  Maven — no plugin, no classifier, no profile — at the cost of about 50 MB.
- `reactor-sdk-kotlin`, optional: an idiomatic Kotlin facade over the same
  binding. `suspend` in place of every `CompletableFuture`, a `Flow` per
  control event, `CoroutineDispatcher.asReactorDispatcher()`, tracks as a
  plain `List<ReactorTrack>`, and a builder block for the options. It holds
  no native symbol — `scripts/check-abi-parity.py` fails the build if one
  appears there — so it cannot drift from the binding it forwards to.
  Frames stay a callback, deliberately; `videoFrames()` and `audioFrames()`
  exist for callers who want a flow anyway and say what that costs.
- Typed errors, one class per code in the shared hierarchy, each carrying
  `code`, `message`, `recoverable`, `status`, `operation` and
  `retryAfterMs`. Recoverability is derived from the code rather than stored
  per throw site, so no two SDKs can disagree about whether a failure is
  worth retrying. The same object is what `onError` delivers and what a
  failed call throws.
- `-sources` and `-javadoc` jars for every published jar coordinate, GPG
  signed. The Kotlin facade's `-javadoc` is rendered KDoc rather than an
  empty jar.

### Versioning

`reactor-sdk` and `reactor-sdk-kotlin` are versioned in lockstep: one version
number covers both, and a release of either publishes both. There is
therefore no supported-pairing table for anyone to work out, and no release
in which the facade lags the binding it forwards to.

[ffm]: https://docs.oracle.com/en/java/javase/22/core/foreign-function-and-memory-api.html
