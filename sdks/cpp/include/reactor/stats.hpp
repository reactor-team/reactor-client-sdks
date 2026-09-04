// Connection statistics.
//
//     const auto stats = reactor.get_stats().get();
//     if (stats.rtt_ms) {
//       std::cout << *stats.rtt_ms << " ms\n";
//     }
//
// The arithmetic is not in this SDK. Counters come from the WebRTC engine and the
// rates are derived in the Rust core, so every FFI-based binding reports the same
// numbers under the same names — see `crates/reactor-core/src/stats.rs`. What is
// here is the shape, and the decode behind `Reactor::get_stats`.
//
// Four fields the browser SDK's `getStats()` reports are absent: `candidateType`,
// `availableIncomingBitrate`, `availableOutgoingBitrate` and `framesPerSecond`.
// All four are missing at the engine rather than dropped on the way — libwebrtc's
// report reaches us across `reactor-webrtc`'s C ABI, which carries no
// local-candidate reference, no available-bitrate estimate and no frame counters.
// Going the other way, `inbound`, `outbound` and `candidate_pairs` are here and
// not there.
#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace reactor {

/// One receive stream's counters, as the engine reports them.
///
/// There is no `kind`, because the engine does not report one: a video stream and
/// an audio one are told apart by `ssrc` and by nothing else. That is also why
/// `ConnectionStats::jitter_s` is the worst of these rather than the video one.
struct InboundStream {
  std::uint32_t ssrc = 0;
  std::uint32_t packets_received = 0;
  /// Signed, and the sign is meaningful: RFC 3550 allows a negative count when
  /// duplicates arrive.
  std::int32_t packets_lost = 0;
  std::uint64_t bytes_received = 0;
  /// Jitter in seconds.
  double jitter_s = 0.0;
  std::uint32_t nack_count = 0;
  /// Cumulative decode time in seconds.
  double total_decode_time_s = 0.0;
};

/// One send stream's counters, as the engine reports them.
struct OutboundStream {
  std::uint32_t ssrc = 0;
  std::uint32_t packets_sent = 0;
  std::uint32_t retransmitted_packets_sent = 0;
  std::uint64_t bytes_sent = 0;
  /// What the encoder is aiming at, in bits per second.
  double target_bitrate_bps = 0.0;
  /// Round-trip time in seconds; zero when not yet measured.
  double round_trip_time_s = 0.0;
};

/// One ICE candidate pair.
///
/// Thin, because the engine's report is: no pair id, no `nominated` flag, and no
/// reference to the local candidate — so a pair cannot say whether it was host,
/// STUN-reflexive or relayed.
struct CandidatePair {
  /// Current RTT in seconds; zero when not yet measured.
  double current_round_trip_time_s = 0.0;
  std::uint64_t priority = 0;
  /// `"succeeded"`, `"waiting"`, `"in-progress"`, `"failed"` or `"cancelled"`.
  std::string state;
};

/// A statistics snapshot for the live connection.
///
/// The scalars are the summary — what a health check or an overlay reads. The
/// three vectors at the bottom are the engine's per-stream report, for when the
/// summary is not enough.
///
/// An empty optional means the engine has not measured that field yet, which is a
/// different thing from zero: no RTT yet is not a zero-latency link, and no
/// incoming bitrate yet is not an idle one. Hence the optionals rather than a
/// sentinel.
struct ConnectionStats {
  /// Round-trip time in milliseconds, from the selected ICE candidate pair.
  ///
  /// "Selected" is inferred — the engine reports no `nominated` flag, so it is the
  /// highest-priority `succeeded` pair, falling back to the largest RTT any send
  /// stream measured.
  std::optional<double> rtt_ms;

  /// The worst jitter across the receive streams, in seconds.
  ///
  /// The maximum rather than a particular stream's, for the reason
  /// `InboundStream` gives: nothing here says which stream is video. Per-stream
  /// values are in `inbound`.
  std::optional<double> jitter_s;

  /// Fraction of inbound packets lost since the connection came up, 0..1.
  /// Cumulative, not per-window.
  std::optional<double> packet_loss_ratio;

  /// Receive rate over the window since the previous `get_stats()`, in bits per
  /// second.
  ///
  /// Empty on the first call after connecting, on a call less than 200 ms after
  /// the last one, and on the first call after a reconnect — a rate takes two
  /// samples of the same streams, and a reconnect brings up new ones.
  ///
  /// RTP payload only, where the browser SDK's `incomingBitrate` counts
  /// everything the candidate pair carried, RTCP and data channel included. Expect
  /// this to read slightly lower than the browser's for the same traffic.
  std::optional<double> incoming_bitrate_bps;

  /// Send rate over the same window, on the same terms.
  std::optional<double> outgoing_bitrate_bps;

  /// What the encoders are aiming at, summed across send streams, in bits per
  /// second. The target, not the achieved rate — compare `outgoing_bitrate_bps`,
  /// which is measured.
  std::optional<double> target_bitrate_bps;

  /// State of the pair `rtt_ms` was read from. Empty when the engine reported no
  /// candidate pairs at all, which is what an unconnected transport looks like.
  std::optional<std::string> candidate_pair_state;

  /// Cumulative counters, summed across streams. Present on every call, even when
  /// the derived rates above are not, so a caller can do its own arithmetic over
  /// whatever window it likes.
  std::uint64_t packets_received = 0;
  /// Signed, for the reason `InboundStream::packets_lost` gives.
  /// `packet_loss_ratio` floors it at zero instead.
  std::int64_t packets_lost = 0;
  std::uint64_t packets_sent = 0;
  std::uint64_t bytes_received = 0;
  std::uint64_t bytes_sent = 0;

  /// When the sample was taken, in Unix milliseconds.
  double timestamp_ms = 0.0;

  /// The engine's per-stream report, unaggregated.
  std::vector<InboundStream> inbound;
  std::vector<OutboundStream> outbound;
  std::vector<CandidatePair> candidate_pairs;
};

}  // namespace reactor
