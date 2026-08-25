# Changelog

All notable changes to `@reactor-team/js-sdk` are documented here. This file
starts at 3.0.0 — earlier releases (0.1.0 through 2.13.0, this SDK's initial
build-out against `reactor-wasm`) predate this file and aren't backfilled.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/);
versioning follows [Semantic Versioning](https://semver.org/).

## [3.0.0]

A major bump, not a 2.x-compatible minor: two behavior changes from the
2.x line (the legacy `@reactor-team/js-sdk`, built directly on
`RTCPeerConnection`) are breaking, forced by moving onto `reactor-wasm`'s
binding over `reactor-core`.

### Breaking changes

- **`sendCommand()` now actually waits for the reply, and resolves with it.**
  In 2.x, `sendCommand()` was `async` but never awaited anything in its
  body — the returned promise resolved on the next microtask, roughly
  bytes-on-wire time, and the method resolved to `void`. It now awaits
  `reactor-core`'s correlated reply, bounded by `controlRequestTimeoutMs`
  (not infinite), and resolves with the reply payload. Code that awaits
  `sendCommand()` (or otherwise consumes its promise) now waits for the real
  round trip instead of resolving almost immediately; a caller that never
  awaited it in the first place still fires and moves on exactly as before.
- **`ReactorError` is a typed class hierarchy, not a plain object.** 2.x's
  `ReactorError` was a flat interface (`code`, `message`, `timestamp`,
  `recoverable`, `component: "api" | "gpu"`, `retryAfter`) with a small,
  hand-picked set of string codes per call site. It's now a class hierarchy
  (`UnauthorizedError`, `NotFoundError`, `RateLimitedError`, ...) keyed
  directly by `reactor-core`'s own canonical `code`, carrying `status`,
  `operation`, `retry_after_ms`, and `timestamp_ms` instead of `retryAfter`
  and `timestamp`. `component` is dropped outright — 2.x populated it
  locally per call site; `reactor-core` never reports it.
