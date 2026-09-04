# Changelog

All notable changes to `reactor-sdk` are documented here. This file starts
after 1.2.0 — 1.2.0 and every release before it (1.0.0 through 1.2.0) predate
this file and aren't backfilled; see their GitHub release notes instead.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/);
versioning follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.3.0] - 2026-09-04

### Added

- `Reactor.get_stats()` — a statistics snapshot for the live connection: RTT,
  jitter, packet loss, bitrates, and the WebRTC engine's own per-stream
  counters (`inbound`, `outbound`, `candidate_pairs`), as frozen
  `ConnectionStats` / `InboundStream` / `OutboundStream` / `CandidatePair`
  objects.

  The two measured bitrates are derived against the previous call, so the first
  call after connecting reports `None` for them, as does a call made less than
  200 ms after the last one. A field the engine has not measured yet is `None`,
  never zero — no RTT yet is not a zero-latency link. Raises
  `InvalidStateError` unless the session is ready.

  Four fields the JS SDK reports are absent — `candidateType`,
  `availableIncomingBitrate`, `availableOutgoingBitrate` and
  `framesPerSecond` — because they are missing at the WebRTC engine rather than
  dropped on the way. See REA-6019.

### Fixed

- `Reactor.close()` now settles any operation still in flight (e.g. a
  `send_command()` whose reply hadn't arrived yet) with an `AbortedError`,
  instead of leaving the caller's `await` hung for the life of the process.
  `disconnect()` was never affected — only the synchronous `close()` path.
