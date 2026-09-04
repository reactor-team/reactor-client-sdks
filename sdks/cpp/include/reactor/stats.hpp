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
// This reports what the browser SDK's `getStats()` reports, field for field:
// the same candidate pair (the one carrying media), the same byte counters, the same
// video stream. `reactor-webrtc` 0.15 was what made that possible — before it,
// `candidate_type`, the available-bitrate estimates and `frames_per_second` did
// not cross the C ABI at all, and nothing said which stream was video.
//
// Going the other way, `inbound`, `outbound` and `candidate_pairs` are here and
// not in the browser, as is `relay_protocol`.
#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace reactor {

/// One receive stream's counters, as the engine reports them.
struct InboundStream {
  std::uint32_t ssrc = 0;
  /// `"audio"`, `"video"`, or empty when the engine reported no kind. What makes
  /// `ConnectionStats::jitter_s` a question about the video stream rather than
  /// about whichever stream happened to be worst.
  std::optional<std::string> kind;
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
  /// Video only; zero until the engine has measured a window's worth.
  double frames_per_second = 0.0;
  std::uint32_t frames_decoded = 0;
  std::uint32_t frames_dropped = 0;
  /// Decoded frame size; zero for audio and before the first frame.
  std::uint32_t frame_width = 0;
  std::uint32_t frame_height = 0;
};

/// One send stream's counters, as the engine reports them.
///
/// The last four come from the far end's RTCP report about us, so they stay at
/// zero until it has sent one — a zero there is "not measured yet", not a
/// zero-latency link with no loss.
struct OutboundStream {
  std::uint32_t ssrc = 0;
  /// `"audio"`, `"video"`, or empty when the engine reported no kind.
  std::optional<std::string> kind;
  /// 64-bit: libwebrtc reports these that way, and a 32-bit counter wrapped
  /// after ~4.3 billion packets — about seven weeks at a thousand a second.
  std::uint64_t packets_sent = 0;
  std::uint64_t retransmitted_packets_sent = 0;
  std::uint64_t bytes_sent = 0;
  /// What the encoder is aiming at, in bits per second.
  double target_bitrate_bps = 0.0;
  /// Video only; zero until the engine has measured a window's worth.
  double frames_per_second = 0.0;
  std::uint32_t frames_sent = 0;
  /// Encoded frame size; zero for audio and before the first frame.
  std::uint32_t frame_width = 0;
  std::uint32_t frame_height = 0;
  /// Round-trip time in seconds; zero when not yet measured.
  double round_trip_time_s = 0.0;
  /// Cumulative round-trip time in seconds.
  double total_round_trip_time_s = 0.0;
  /// Fraction of this stream the receiver reports as lost, 0..1.
  double fraction_lost = 0.0;
  /// Packets the receiver reports as lost. Signed, per RFC 3550.
  std::int32_t packets_lost = 0;
};

/// One ICE candidate pair.
///
/// A connection gathers many — a plain loopback produces eighteen — and exactly
/// one is `nominated`. Only that one carries traffic; the rest report zeroes, so
/// anything aggregating across pairs averages in candidates that carried nothing.
struct CandidatePair {
  /// Current RTT in seconds; zero when not yet measured.
  double current_round_trip_time_s = 0.0;
  /// Cumulative RTT in seconds across every check on this pair.
  double total_round_trip_time_s = 0.0;
  std::uint64_t priority = 0;
  /// `"succeeded"`, `"waiting"`, `"in-progress"`, `"failed"` or `"cancelled"`.
  std::string state;
  /// Whether ICE selected this pair. Read this rather than inferring the
  /// selected pair from `state` and `priority`.
  bool nominated = false;
  bool writable = false;
  /// The congestion controller's estimates, in bits per second; zero when it has
  /// none yet.
  double available_outgoing_bitrate_bps = 0.0;
  double available_incoming_bitrate_bps = 0.0;
  /// Everything this pair carried — RTCP and data channel included, so wider
  /// than the per-stream RTP counters.
  std::uint64_t bytes_sent = 0;
  std::uint64_t bytes_received = 0;
  std::uint64_t packets_sent = 0;
  std::uint64_t packets_received = 0;
  /// `"host"`, `"srflx"`, `"prflx"`, `"relay"`, or empty before ICE selected
  /// anything. `"relay"` means this pair goes through TURN.
  std::optional<std::string> local_candidate_type;
  /// `"udp"`, `"tcp"`, `"tls"`, or empty when not relayed.
  std::optional<std::string> local_relay_protocol;
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
  /// Round-trip time in milliseconds, from the candidate pair carrying the
  /// media — the one ICE nominated and that succeeded.
  ///
  /// Falls back to the largest RTT any send stream measured — which comes from
  /// the far end's RTCP report about us, so it too takes a moment to appear.
  std::optional<double> rtt_ms;

  /// Jitter on the received video stream, in seconds.
  ///
  /// The same stream the browser SDK reads. With no video stream, the worst
  /// across the receive streams there are. Per-stream values are in `inbound`.
  std::optional<double> jitter_s;

  /// Fraction of inbound packets lost since the connection came up, 0..1.
  ///
  /// Cumulative, not per-window, and from the video stream for the same reason
  /// `jitter_s` is.
  std::optional<double> packet_loss_ratio;

  /// Receive rate over the window since the previous `get_stats()`, in bits per
  /// second.
  ///
  /// Measured on the candidate pair carrying the media, so it covers everything it
  /// carried — RTP, RTCP and the data channel — which is what the browser SDK's
  /// `incomingBitrate` measures.
  ///
  /// Empty on the first call after connecting, on a call less than 200 ms after
  /// the last one, before ICE has nominated a pair, and on the first call after a
  /// reconnect — a reconnect nominates a different pair whose counters restart
  /// from zero.
  std::optional<double> incoming_bitrate_bps;

  /// Send rate over the same window, on the same terms.
  std::optional<double> outgoing_bitrate_bps;

  /// The congestion controller's own estimate of what the path can carry, in
  /// bits per second — not what is flowing. Empty until it has one, which needs
  /// media on the wire: a data-channel-only connection never reports it.
  std::optional<double> available_incoming_bitrate_bps;
  /// As above, for the send direction.
  std::optional<double> available_outgoing_bitrate_bps;

  /// What the encoders are aiming at, summed across send streams, in bits per
  /// second. The target, not the achieved rate — compare `outgoing_bitrate_bps`,
  /// which is measured.
  std::optional<double> target_bitrate_bps;

  /// Frames per second on the received video stream. Empty with no video stream,
  /// and until the engine has measured a window's worth.
  std::optional<double> frames_per_second;

  /// Transport type of the local candidate on the pair carrying the media:
  /// `"host"`, `"srflx"`,
  /// `"prflx"` or `"relay"`.
  ///
  /// `"relay"` means the media is going through a TURN server, which is the first
  /// thing worth knowing when latency is bad. Empty before ICE has selected
  /// anything.
  std::optional<std::string> candidate_type;

  /// `"udp"`, `"tcp"` or `"tls"` when `candidate_type` is `"relay"`; empty when
  /// the path is not relayed. Not a field the browser SDK reports.
  std::optional<std::string> relay_protocol;

  /// State of the pair `rtt_ms` was read from. Empty when the engine reported no
  /// candidate pairs at all, which is what an unconnected transport looks like.
  std::optional<std::string> candidate_pair_state;

  /// Cumulative counters, summed across streams. Present on every call, even when
  /// the derived rates above are not, so a caller can do its own arithmetic over
  /// whatever window it likes.
  std::uint64_t packets_received = 0;
  /// Signed, for the reason `InboundStream::packets_lost` gives. The plain sum
  /// across receive streams, so one stream's duplicates do offset another's
  /// losses here; `packet_loss_ratio` is the field to read for "how bad is it".
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
