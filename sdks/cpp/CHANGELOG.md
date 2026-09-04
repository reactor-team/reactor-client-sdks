# Changelog

All notable changes to the Reactor C++ SDK (`reactor-sdk`) are documented
here.

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
