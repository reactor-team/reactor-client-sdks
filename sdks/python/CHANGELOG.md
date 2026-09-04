# Changelog

All notable changes to `reactor-sdk` are documented here. This file starts
after 1.2.0 — 1.2.0 and every release before it (1.0.0 through 1.2.0) predate
this file and aren't backfilled; see their GitHub release notes instead.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/);
versioning follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `get_stats()` now reports the four fields the JS SDK reported and this SDK
  could not: `candidate_type` (`"relay"` says the session is going through
  TURN), `available_incoming_bitrate_bps`, `available_outgoing_bitrate_bps` and
  `frames_per_second`. Plus `relay_protocol`, which the JS SDK does not report.

  Per-stream entries gained `kind` (`"audio"` / `"video"`) and the video frame
  counters; send streams gained the far end's own report about us
  (`total_round_trip_time_s`, `fraction_lost`, `packets_lost`); candidate pairs
  gained `nominated`, `writable`, their own byte and packet totals, and the
  congestion controller's estimates.

  All of it came from `reactor-webrtc` 0.15 (REA-6019), which is what this
  release takes.

### Changed

- **`jitter_s` and `packet_loss_ratio` now come from the received video
  stream**, which is the stream the JS SDK reads — so the two SDKs report the
  same number for the same connection. They used to aggregate across every
  receive stream, because nothing said which one was video. An audio-only
  session still gets both, falling back to the streams there are, where the JS
  SDK reports nothing.

- **The bitrates are now measured on the candidate pair ICE nominated**, again
  matching the JS SDK: they cover everything that pair carried, RTCP and data
  channel included, where before they summed RTP payload and read slightly low.
  A consequence: they are empty until ICE has nominated a pair.

- **`rtt_ms` comes from the nominated pair** rather than from the
  highest-priority succeeded one, which was an inference standing in for the
  flag the engine now reports.

### Fixed

- An outbound stream's `round_trip_time_s` was always `0.0`. libwebrtc moved the
  send path's RTT into the receiver's RTCP report about us in M7907 and nothing
  followed it; `reactor-webrtc` 0.15 does.

- A send stream's `packets_sent` and `retransmitted_packets_sent` were 32-bit
  and wrapped after ~4.3 billion packets — about seven weeks at a thousand a
  second — after which a cumulative counter appeared to go backwards. Both are
  64-bit now, as are the candidate pair's.

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
