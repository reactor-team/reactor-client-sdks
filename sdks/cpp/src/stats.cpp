// Connection statistics: asking for a snapshot, and reading the answer.
//
// The numbers are computed in the Rust core — see `crates/reactor-core/src/stats.rs`
// — so that this SDK and the Python one cannot disagree about what "outgoing
// bitrate" means. What is here is the decode, and the decode's one rule: a field
// that will not parse is a `DecodeError`, never a zero. A snapshot is read to
// decide whether a connection is healthy, and a substituted zero reads as
// "healthy" — the one answer worth never guessing at.

#include "reactor/stats.hpp"

#include <stdexcept>
#include <string>
#include <utility>

#include "detail/client_impl.hpp"
#include "detail/ffi.hpp"
#include "reactor/errors.hpp"
#include "reactor/json.hpp"

namespace reactor {

namespace {

/// A field that may legitimately be absent or null.
///
/// The core writes every unknown scalar as `null` rather than omitting it, so a
/// binding can tell "not measured" from "not reported". Both read as empty here;
/// what must not read as empty is a value of the wrong type, so anything present
/// and non-null is converted and throws if it cannot be.
template <typename T>
std::optional<T> optional_field(const Json& object, const char* key) {
  const auto found = object.find(key);
  if (found == object.end() || found->is_null()) {
    return std::nullopt;
  }
  return found->get<T>();
}

/// A field that must be there.
///
/// `Json::value(key, fallback)` would substitute the fallback for an absent key,
/// which is exactly the silent zero this file exists to avoid. `at` throws, and
/// the caller turns that into a decode failure naming the payload.
template <typename T>
T required_field(const Json& object, const char* key) {
  return object.at(key).get<T>();
}

std::vector<InboundStream> decode_inbound(const Json& stats) {
  std::vector<InboundStream> streams;
  const auto found = stats.find("inbound");
  if (found == stats.end() || !found->is_array()) {
    // An absent array is a connection with no receive streams, which is not a
    // failure. A *present* one that is not an array is, and `at` below never sees
    // it — so check the type here rather than iterating something that is not a
    // list.
    if (found != stats.end() && !found->is_null()) {
      throw std::runtime_error{"\"inbound\" is not an array"};
    }
    return streams;
  }
  streams.reserve(found->size());
  for (const auto& entry : *found) {
    InboundStream stream;
    stream.ssrc = required_field<std::uint32_t>(entry, "ssrc");
    stream.kind = optional_field<std::string>(entry, "kind");
    stream.packets_received = required_field<std::uint32_t>(entry, "packets_received");
    stream.packets_lost = required_field<std::int32_t>(entry, "packets_lost");
    stream.bytes_received = required_field<std::uint64_t>(entry, "bytes_received");
    stream.jitter_s = required_field<double>(entry, "jitter_s");
    stream.nack_count = required_field<std::uint32_t>(entry, "nack_count");
    stream.total_decode_time_s = required_field<double>(entry, "total_decode_time_s");
    stream.frames_per_second = required_field<double>(entry, "frames_per_second");
    stream.frames_decoded = required_field<std::uint32_t>(entry, "frames_decoded");
    stream.frames_dropped = required_field<std::uint32_t>(entry, "frames_dropped");
    stream.frame_width = required_field<std::uint32_t>(entry, "frame_width");
    stream.frame_height = required_field<std::uint32_t>(entry, "frame_height");
    streams.push_back(std::move(stream));
  }
  return streams;
}

std::vector<OutboundStream> decode_outbound(const Json& stats) {
  std::vector<OutboundStream> streams;
  const auto found = stats.find("outbound");
  if (found == stats.end() || !found->is_array()) {
    if (found != stats.end() && !found->is_null()) {
      throw std::runtime_error{"\"outbound\" is not an array"};
    }
    return streams;
  }
  streams.reserve(found->size());
  for (const auto& entry : *found) {
    OutboundStream stream;
    stream.ssrc = required_field<std::uint32_t>(entry, "ssrc");
    stream.kind = optional_field<std::string>(entry, "kind");
    // 64-bit: these wrapped at 2^32 before reactor-webrtc 0.15.
    stream.packets_sent = required_field<std::uint64_t>(entry, "packets_sent");
    stream.retransmitted_packets_sent =
        required_field<std::uint64_t>(entry, "retransmitted_packets_sent");
    stream.bytes_sent = required_field<std::uint64_t>(entry, "bytes_sent");
    stream.target_bitrate_bps = required_field<double>(entry, "target_bitrate_bps");
    stream.frames_per_second = required_field<double>(entry, "frames_per_second");
    stream.frames_sent = required_field<std::uint32_t>(entry, "frames_sent");
    stream.frame_width = required_field<std::uint32_t>(entry, "frame_width");
    stream.frame_height = required_field<std::uint32_t>(entry, "frame_height");
    stream.round_trip_time_s = required_field<double>(entry, "round_trip_time_s");
    stream.total_round_trip_time_s = required_field<double>(entry, "total_round_trip_time_s");
    stream.fraction_lost = required_field<double>(entry, "fraction_lost");
    stream.packets_lost = required_field<std::int32_t>(entry, "packets_lost");
    streams.push_back(std::move(stream));
  }
  return streams;
}

std::vector<CandidatePair> decode_candidate_pairs(const Json& stats) {
  std::vector<CandidatePair> pairs;
  const auto found = stats.find("candidate_pairs");
  if (found == stats.end() || !found->is_array()) {
    if (found != stats.end() && !found->is_null()) {
      throw std::runtime_error{"\"candidate_pairs\" is not an array"};
    }
    return pairs;
  }
  pairs.reserve(found->size());
  for (const auto& entry : *found) {
    CandidatePair pair;
    pair.current_round_trip_time_s = required_field<double>(entry, "current_round_trip_time_s");
    pair.total_round_trip_time_s = required_field<double>(entry, "total_round_trip_time_s");
    // A 64-bit priority, read as one. Through a double it would round, and two
    // pairs a few units apart would compare equal.
    pair.priority = required_field<std::uint64_t>(entry, "priority");
    pair.state = required_field<std::string>(entry, "state");
    pair.nominated = required_field<bool>(entry, "nominated");
    pair.writable = required_field<bool>(entry, "writable");
    pair.available_outgoing_bitrate_bps =
        required_field<double>(entry, "available_outgoing_bitrate_bps");
    pair.available_incoming_bitrate_bps =
        required_field<double>(entry, "available_incoming_bitrate_bps");
    pair.bytes_sent = required_field<std::uint64_t>(entry, "bytes_sent");
    pair.bytes_received = required_field<std::uint64_t>(entry, "bytes_received");
    pair.packets_sent = required_field<std::uint64_t>(entry, "packets_sent");
    pair.packets_received = required_field<std::uint64_t>(entry, "packets_received");
    pair.local_candidate_type = optional_field<std::string>(entry, "local_candidate_type");
    pair.local_relay_protocol = optional_field<std::string>(entry, "local_relay_protocol");
    pairs.push_back(std::move(pair));
  }
  return pairs;
}

}  // namespace

namespace detail {

void PendingStats::deliver(Json result) {
  if (!result.is_object()) {
    promise.set_exception(as_exception_ptr(ErrorDetails{
        std::string{codes::DECODE_FAILED},
        "connection statistics arrived as something other than an object: " + result.dump(),
        false,
        {},
        operation,
        {},
        {}}));
    return;
  }

  ConnectionStats stats;
  // Converted before the promise is touched, and the whole reason this is not
  // written the other way round: `settle` has already claimed this operation, so a
  // throw from here would make the trampoline's fallback `fail` a no-op and leave
  // the caller waiting on a promise nobody can fulfil — a hang, where the
  // documented answer is a typed decode failure.
  try {
    stats.rtt_ms = optional_field<double>(result, "rtt_ms");
    stats.jitter_s = optional_field<double>(result, "jitter_s");
    stats.packet_loss_ratio = optional_field<double>(result, "packet_loss_ratio");
    stats.incoming_bitrate_bps = optional_field<double>(result, "incoming_bitrate_bps");
    stats.outgoing_bitrate_bps = optional_field<double>(result, "outgoing_bitrate_bps");
    stats.available_incoming_bitrate_bps =
        optional_field<double>(result, "available_incoming_bitrate_bps");
    stats.available_outgoing_bitrate_bps =
        optional_field<double>(result, "available_outgoing_bitrate_bps");
    stats.target_bitrate_bps = optional_field<double>(result, "target_bitrate_bps");
    stats.frames_per_second = optional_field<double>(result, "frames_per_second");
    stats.candidate_type = optional_field<std::string>(result, "candidate_type");
    stats.relay_protocol = optional_field<std::string>(result, "relay_protocol");
    stats.candidate_pair_state = optional_field<std::string>(result, "candidate_pair_state");
    stats.packets_received = required_field<std::uint64_t>(result, "packets_received");
    stats.packets_lost = required_field<std::int64_t>(result, "packets_lost");
    stats.packets_sent = required_field<std::uint64_t>(result, "packets_sent");
    stats.bytes_received = required_field<std::uint64_t>(result, "bytes_received");
    stats.bytes_sent = required_field<std::uint64_t>(result, "bytes_sent");
    stats.timestamp_ms = required_field<double>(result, "timestamp_ms");
    stats.inbound = decode_inbound(result);
    stats.outbound = decode_outbound(result);
    stats.candidate_pairs = decode_candidate_pairs(result);
  } catch (const std::exception& error) {
    promise.set_exception(
        as_exception_ptr(ErrorDetails{std::string{codes::DECODE_FAILED},
                                      std::string{"connection statistics could not be read ("} +
                                          error.what() + "): " + result.dump(),
                                      false,
                                      {},
                                      operation,
                                      {},
                                      {}}));
    return;
  }
  promise.set_value(std::move(stats));
}

void ClientImpl::begin_get_stats(std::unique_ptr<Pending> op) {
  try {
    ReactorHandle* handle = require_ready_session("read statistics from this connection");
    auto* raw = track_pending(std::move(op));
    ffi().get_stats(handle, &completion_trampoline, raw);
  } catch (...) {
    op->fail(std::current_exception());
  }
}

}  // namespace detail

std::future<ConnectionStats> Reactor::get_stats() {
  auto op = std::make_unique<detail::PendingStats>();
  op->operation = "get_stats";
  auto future = op->promise.get_future();
  impl_->begin_get_stats(std::move(op));
  return future;
}

}  // namespace reactor
