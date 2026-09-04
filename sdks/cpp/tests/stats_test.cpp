// Connection statistics.
//
// The numbers themselves are computed in `reactor-core` and tested there. What is
// testable here is the binding's half, and the case worth reading first is the
// decode: a snapshot is read to decide whether a connection is healthy, so a
// field that quietly became a zero says "healthy" — and every row of
// "refuses rather than guessing" below was that.
//
// The other one is `deliver`'s ordering. A future settles once. Claiming the
// operation and *then* converting the payload leaves a type error with nowhere to
// go: the trampoline's fallback finds the promise already claimed, and the caller
// gets a broken promise — a hang — instead of the typed error this SDK documents.

#include <chrono>
#include <cstdlib>
#include <future>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <catch2/catch_test_macros.hpp>

#include "detail/ffi.hpp"
#include "reactor/errors.hpp"
#include "reactor/reactor.hpp"

namespace {

using namespace std::chrono_literals;

/// What the core serializes for a healthy connection, with both derived bitrates
/// present — i.e. not the first sample.
constexpr const char* FULL_PAYLOAD = R"({
  "rtt_ms": 21.5,
  "jitter_s": 0.004,
  "packet_loss_ratio": 0.002,
  "incoming_bitrate_bps": 1843200.0,
  "outgoing_bitrate_bps": 96000.0,
  "target_bitrate_bps": 2500000.0,
  "candidate_pair_state": "succeeded",
  "packets_received": 4021,
  "packets_lost": 8,
  "packets_sent": 512,
  "bytes_received": 5123456,
  "bytes_sent": 65536,
  "timestamp_ms": 1757000000000.0,
  "inbound": [
    {"ssrc": 111, "packets_received": 4021, "packets_lost": 8, "bytes_received": 5123456,
     "jitter_s": 0.004, "nack_count": 2, "total_decode_time_s": 1.25}
  ],
  "outbound": [
    {"ssrc": 222, "packets_sent": 512, "retransmitted_packets_sent": 1, "bytes_sent": 65536,
     "target_bitrate_bps": 2500000.0, "round_trip_time_s": 0.0215}
  ],
  "candidate_pairs": [
    {"current_round_trip_time_s": 0.0215, "priority": 9115038255631187199, "state": "succeeded"}
  ]
})";

/// What the core serializes on the first sample after connecting: counters, but
/// nothing to have derived a rate from yet.
constexpr const char* FIRST_SAMPLE_PAYLOAD = R"({
  "rtt_ms": null,
  "jitter_s": null,
  "packet_loss_ratio": null,
  "incoming_bitrate_bps": null,
  "outgoing_bitrate_bps": null,
  "target_bitrate_bps": null,
  "candidate_pair_state": null,
  "packets_received": 0,
  "packets_lost": 0,
  "packets_sent": 0,
  "bytes_received": 0,
  "bytes_sent": 0,
  "timestamp_ms": 1757000000000.0,
  "inbound": [],
  "outbound": [],
  "candidate_pairs": []
})";

/// A fake library that answers `reactor_get_stats` with whatever it is told to.
class FakeStats {
 public:
  FakeStats() { current_instance = this; }

  ~FakeStats() {
    join_all();
    current_instance = nullptr;
  }

  FakeStats(const FakeStats&) = delete;
  FakeStats& operator=(const FakeStats&) = delete;
  FakeStats(FakeStats&&) = delete;
  FakeStats& operator=(FakeStats&&) = delete;

  static FakeStats& current() { return *current_instance; }

  reactor::detail::Ffi table() {
    reactor::detail::Ffi filled;
    filled.abi_version = &abi_version;
    filled.create_with_adm = &create_with_adm;
    filled.destroy = &destroy;
    filled.connect = &connect;
    filled.status = &status;
    filled.free_string = &free_string;
    filled.get_stats = &get_stats;
    return filled;
  }

  /// What the next call answers with. `result` empty and `error` empty means a
  /// success carrying no payload at all.
  std::string result = FULL_PAYLOAD;
  std::string error;
  std::string current_status = "ready";
  int calls = 0;

  void join_all() {
    std::vector<std::thread> threads;
    {
      const std::lock_guard<std::mutex> lock(mutex_);
      threads = std::move(threads_);
      threads_.clear();
    }
    for (auto& thread : threads) {
      if (thread.joinable()) {
        thread.join();
      }
    }
  }

 private:
  template <typename F>
  void spawn(F&& work) {
    const std::lock_guard<std::mutex> lock(mutex_);
    threads_.emplace_back([work = std::forward<F>(work)]() noexcept {
      try {
        work();
      } catch (...) {
        FAIL_CHECK("an exception escaped a fake library thread");
      }
    });
  }

  static std::uint32_t abi_version() { return REACTOR_ABI_VERSION; }

  static ReactorHandle* create_with_adm(const char* /*api_url*/, const char* /*model*/,
                                        const char* /*jwt*/, int /*local*/,
                                        const ReactorCallbacks* /*callbacks*/, int /*adm_mode*/,
                                        const char* /*sdk_version*/, const char* /*sdk_type*/) {
    auto& self = current();
    return reinterpret_cast<ReactorHandle*>(&self.handle_marker_);
  }

  static int destroy(ReactorHandle* /*handle*/) {
    current().join_all();
    return 0;
  }

  static void connect(ReactorHandle* /*handle*/, const char* /*session_id*/,
                      const std::uint32_t* /*connection_id*/, reactor_completion_fn completion,
                      void* userdata) {
    if (completion != nullptr) {
      current().spawn([completion, userdata] { completion(1, "{}", nullptr, userdata); });
    }
  }

  static const char* status(ReactorHandle* handle) {
    return handle == nullptr ? "disconnected" : current().current_status.c_str();
  }

  static void free_string(char* s) { std::free(s); }

  static void get_stats(ReactorHandle* /*handle*/, reactor_completion_fn completion,
                        void* userdata) {
    auto& self = current();
    self.calls += 1;
    if (completion == nullptr) {
      return;
    }
    const std::string result = self.result;
    const std::string error = self.error;
    self.spawn([completion, userdata, result, error] {
      if (!error.empty()) {
        completion(0, nullptr, error.c_str(), userdata);
      } else if (result.empty()) {
        completion(1, nullptr, nullptr, userdata);
      } else {
        completion(1, result.c_str(), nullptr, userdata);
      }
    });
  }

  static FakeStats* current_instance;

  int handle_marker_ = 0;
  std::mutex mutex_;
  std::vector<std::thread> threads_;
};

FakeStats* FakeStats::current_instance = nullptr;

struct Connected {
  Connected() : override_(&table_) { client.connect().get(); }

  FakeStats session;
  reactor::detail::Ffi table_ = session.table();
  reactor::detail::FfiOverride override_;
  reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};
};

}  // namespace

TEST_CASE("a snapshot comes back with its scalars and its per-stream detail") {
  Connected fixture;

  const auto stats = fixture.client.get_stats().get();

  CHECK(fixture.session.calls == 1);
  REQUIRE(stats.rtt_ms.has_value());
  CHECK(stats.rtt_ms.value_or(0.0) == 21.5);
  REQUIRE(stats.incoming_bitrate_bps.has_value());
  CHECK(stats.incoming_bitrate_bps.value_or(0.0) == 1843200.0);
  REQUIRE(stats.candidate_pair_state.has_value());
  CHECK(stats.candidate_pair_state.value_or("") == "succeeded");
  CHECK(stats.bytes_received == 5123456);
  CHECK(stats.packets_lost == 8);

  REQUIRE(stats.inbound.size() == 1);
  CHECK(stats.inbound.front().ssrc == 111);
  CHECK(stats.inbound.front().nack_count == 2);
  CHECK(stats.inbound.front().total_decode_time_s == 1.25);

  REQUIRE(stats.outbound.size() == 1);
  CHECK(stats.outbound.front().retransmitted_packets_sent == 1);

  REQUIRE(stats.candidate_pairs.size() == 1);
  CHECK(stats.candidate_pairs.front().state == "succeeded");
}

// Through a double this would round to 9115038255631187456, and two pairs a few
// units apart would compare equal.
TEST_CASE("a 64-bit candidate-pair priority survives the crossing exactly") {
  Connected fixture;

  const auto stats = fixture.client.get_stats().get();

  REQUIRE(stats.candidate_pairs.size() == 1);
  CHECK(stats.candidate_pairs.front().priority == 9115038255631187199ULL);
}

// Zero would read as an idle connection, which is the opposite of what "no
// previous sample to compare against" means.
TEST_CASE("the first sample leaves the rates empty rather than zero") {
  Connected fixture;
  fixture.session.result = FIRST_SAMPLE_PAYLOAD;

  const auto stats = fixture.client.get_stats().get();

  CHECK_FALSE(stats.incoming_bitrate_bps.has_value());
  CHECK_FALSE(stats.outgoing_bitrate_bps.has_value());
  CHECK_FALSE(stats.rtt_ms.has_value());
  CHECK_FALSE(stats.candidate_pair_state.has_value());
  CHECK(stats.inbound.empty());
}

// Both branches of the refusal, because they are different situations with
// different fixes: one has not connected, the other has and lost it. And both must
// refuse *before* asking the library — a report of zeroes cannot be told from a
// connection carrying nothing.
TEST_CASE("statistics on a client that never connected are refused") {
  FakeStats session;
  const reactor::detail::Ffi table = session.table();
  const reactor::detail::FfiOverride override{&table};
  reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};

  try {
    client.get_stats().get();
    FAIL("statistics before connecting must be refused");
  } catch (const reactor::InvalidStateError& error) {
    CHECK(error.details().message.find("has not connected") != std::string::npos);
    // The subject is the connection, not a "track" called reactor/helios — which
    // is what the track-shaped refusal would have said.
    CHECK(error.details().message.find("track") == std::string::npos);
  }
  CHECK(session.calls == 0);
}

TEST_CASE("statistics on a session that dropped are refused, naming the status") {
  Connected fixture;
  fixture.session.current_status = "disconnected";

  try {
    fixture.client.get_stats().get();
    FAIL("statistics on a session that is not ready must be refused");
  } catch (const reactor::InvalidStateError& error) {
    // The status is named, so a caller polling too early can see why.
    CHECK(error.details().message.find("disconnected") != std::string::npos);
    CHECK(error.details().message.find("track") == std::string::npos);
  }
  CHECK(fixture.session.calls == 0);
}

TEST_CASE("a failed call throws the typed error the platform reported") {
  Connected fixture;
  fixture.session.error =
      R"({"code":"DISCONNECTED","message":"the transport dropped","recoverable":true,
          "operation":"get_stats"})";

  CHECK_THROWS_AS(fixture.client.get_stats().get(), reactor::DisconnectedError);
}

TEST_CASE("statistics that cannot be read fail the call instead of reading as healthy") {
  // Each of these used to produce a ConnectionStats full of zeroes, which is
  // indistinguishable from a connection that is up and carrying nothing.
  SECTION("a success with no payload at all") {
    Connected fixture;
    fixture.session.result.clear();

    CHECK_THROWS_AS(fixture.client.get_stats().get(), reactor::DecodeError);
  }

  SECTION("a payload that is not an object") {
    Connected fixture;
    fixture.session.result = "[1, 2, 3]";

    CHECK_THROWS_AS(fixture.client.get_stats().get(), reactor::DecodeError);
  }

  SECTION("a missing counter") {
    Connected fixture;
    fixture.session.result = R"({"rtt_ms": 1.0, "timestamp_ms": 1.0})";

    CHECK_THROWS_AS(fixture.client.get_stats().get(), reactor::DecodeError);
  }

  SECTION("a counter of the wrong type") {
    Connected fixture;
    fixture.session.result = R"({
      "packets_received": "4021", "packets_lost": 0, "packets_sent": 0,
      "bytes_received": 0, "bytes_sent": 0, "timestamp_ms": 1.0
    })";

    CHECK_THROWS_AS(fixture.client.get_stats().get(), reactor::DecodeError);
  }

  SECTION("a malformed per-stream entry") {
    Connected fixture;
    fixture.session.result = R"({
      "packets_received": 0, "packets_lost": 0, "packets_sent": 0,
      "bytes_received": 0, "bytes_sent": 0, "timestamp_ms": 1.0,
      "inbound": [{"ssrc": 111}]
    })";

    CHECK_THROWS_AS(fixture.client.get_stats().get(), reactor::DecodeError);
  }

  SECTION("an array field that is not an array") {
    Connected fixture;
    fixture.session.result = R"({
      "packets_received": 0, "packets_lost": 0, "packets_sent": 0,
      "bytes_received": 0, "bytes_sent": 0, "timestamp_ms": 1.0,
      "outbound": {"ssrc": 222}
    })";

    CHECK_THROWS_AS(fixture.client.get_stats().get(), reactor::DecodeError);
  }
}

// The ordering test. A `deliver` that claimed the promise before converting would
// leave the type error with nowhere to go, and this would hang until the future
// reported a broken promise — never the typed error.
TEST_CASE("a decode failure arrives as an error, not as a promise nobody can fulfil") {
  Connected fixture;
  fixture.session.result = R"({
    "packets_received": 0, "packets_lost": 0, "packets_sent": 0,
    "bytes_received": 0, "bytes_sent": 0, "timestamp_ms": "not a number"
  })";

  auto future = fixture.client.get_stats();
  REQUIRE(future.wait_for(2s) == std::future_status::ready);

  try {
    future.get();
    FAIL("a payload that cannot be read must not resolve");
  } catch (const reactor::DecodeError& error) {
    // The message carries the payload, so the failure can be diagnosed from a log
    // line rather than from a debugger.
    CHECK(error.details().message.find("timestamp_ms") != std::string::npos);
    CHECK(error.details().operation == "get_stats");
  }
}

TEST_CASE("an absent per-stream array is a connection with no such streams, not a failure") {
  Connected fixture;
  fixture.session.result = R"({
    "rtt_ms": null, "jitter_s": null, "packet_loss_ratio": null,
    "incoming_bitrate_bps": null, "outgoing_bitrate_bps": null,
    "target_bitrate_bps": null, "candidate_pair_state": null,
    "packets_received": 0, "packets_lost": 0, "packets_sent": 0,
    "bytes_received": 0, "bytes_sent": 0, "timestamp_ms": 1.0
  })";

  const auto stats = fixture.client.get_stats().get();

  CHECK(stats.inbound.empty());
  CHECK(stats.outbound.empty());
  CHECK(stats.candidate_pairs.empty());
}

// RFC 3550 allows it when duplicates arrive, and the core reports the signed
// count deliberately — clamping it here would hide the duplicates the ratio
// already accounts for.
TEST_CASE("a negative loss count survives as negative") {
  Connected fixture;
  fixture.session.result = R"({
    "packet_loss_ratio": 0.0,
    "packets_received": 100, "packets_lost": -3, "packets_sent": 0,
    "bytes_received": 0, "bytes_sent": 0, "timestamp_ms": 1.0,
    "inbound": [{"ssrc": 1, "packets_received": 100, "packets_lost": -3,
                 "bytes_received": 0, "jitter_s": 0.0, "nack_count": 0,
                 "total_decode_time_s": 0.0}]
  })";

  const auto stats = fixture.client.get_stats().get();

  CHECK(stats.packets_lost == -3);
  CHECK(stats.inbound.front().packets_lost == -3);
  REQUIRE(stats.packet_loss_ratio.has_value());
  CHECK(stats.packet_loss_ratio.value_or(1.0) == 0.0);
}

// serde_json writes a whole f64 as `21.0`, but nothing stops a producer writing
// `21`. Both are the same number, and refusing one of them would be refusing the
// core's own output on a version that formatted it differently.
TEST_CASE("an integer where a double is documented is accepted") {
  Connected fixture;
  fixture.session.result = R"({
    "rtt_ms": 21, "jitter_s": 0,
    "packets_received": 0, "packets_lost": 0, "packets_sent": 0,
    "bytes_received": 0, "bytes_sent": 0, "timestamp_ms": 1757000000000
  })";

  const auto stats = fixture.client.get_stats().get();

  REQUIRE(stats.rtt_ms.has_value());
  CHECK(stats.rtt_ms.value_or(0.0) == 21.0);
  REQUIRE(stats.jitter_s.has_value());
  CHECK(stats.jitter_s.value_or(1.0) == 0.0);
}
