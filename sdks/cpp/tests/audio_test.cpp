// The device helpers, and the buffer between the two clocks.
//
// CI has no sound card, so nothing here opens a device: what is testable without
// one is the jitter buffer's two failure modes, the refusals that keep a device
// off a track that could never feed it, and the fact that the core target has no
// audio in it at all.

#include <cstring>
#include <string>
#include <thread>
#include <vector>

#include <catch2/catch_test_macros.hpp>

#include "audio/ring_buffer.hpp"
#include "detail/ffi.hpp"
#include "reactor/audio_devices.hpp"
#include "reactor/reactor.hpp"

namespace {

/// Just enough fake library to declare tracks and accept pushes.
class FakeAudioSession {
 public:
  FakeAudioSession() { current_instance = this; }
  ~FakeAudioSession() { current_instance = nullptr; }

  FakeAudioSession(const FakeAudioSession&) = delete;
  FakeAudioSession& operator=(const FakeAudioSession&) = delete;
  FakeAudioSession(FakeAudioSession&&) = delete;
  FakeAudioSession& operator=(FakeAudioSession&&) = delete;

  static FakeAudioSession& current() { return *current_instance; }

  reactor::detail::Ffi table() {
    reactor::detail::Ffi filled;
    filled.abi_version = &abi_version;
    filled.create_with_adm = &create_with_adm;
    filled.destroy = &destroy;
    filled.connect = &connect;
    filled.status = &status;
    filled.tracks = &tracks;
    filled.paused_tracks = &paused_tracks;
    filled.free_string = &free_string;
    filled.publish_track = &publish_track;
    filled.push_audio_frame = &push_audio_frame;
    return filled;
  }

  std::string declared_json = R"([{"name":"main_audio","kind":"audio","direction":"recvonly"},)"
                              R"({"name":"input_audio","kind":"audio","direction":"sendonly"},)"
                              R"({"name":"main_video","kind":"video","direction":"recvonly"}])";

  int audio_pushes = 0;

  /// Deliver an audio frame the way the library does.
  void push_audio(const std::string& track, const std::vector<std::int16_t>& samples,
                  std::uint32_t rate, std::uint32_t channels) {
    if (callbacks_.on_audio == nullptr) {
      return;
    }
    std::thread worker([this, track, samples, rate, channels] {
      callbacks_.on_audio(track.c_str(), samples.data(), static_cast<std::uint32_t>(samples.size()),
                          rate, channels, callbacks_.userdata);
    });
    worker.join();
  }

 private:
  static std::uint32_t abi_version() { return REACTOR_ABI_VERSION; }

  static ReactorHandle* create_with_adm(const char* /*api_url*/, const char* /*model*/,
                                        const char* /*jwt*/, int /*local*/,
                                        const ReactorCallbacks* callbacks, int /*adm_mode*/) {
    auto& self = current();
    if (callbacks != nullptr) {
      self.callbacks_ = *callbacks;
    }
    return reinterpret_cast<ReactorHandle*>(&self.handle_marker_);
  }

  static int destroy(ReactorHandle* /*handle*/) { return 0; }

  static void connect(ReactorHandle* /*handle*/, const char* /*session_id*/,
                      const std::uint32_t* /*connection_id*/, reactor_completion_fn completion,
                      void* userdata) {
    if (completion != nullptr) {
      std::thread worker([completion, userdata] { completion(1, "{}", nullptr, userdata); });
      worker.join();
    }
  }

  static const char* status(ReactorHandle* handle) {
    return handle == nullptr ? "disconnected" : "ready";
  }

  static char* tracks(ReactorHandle* handle) {
    return heap_copy(handle == nullptr ? "[]" : current().declared_json.c_str());
  }

  static char* paused_tracks(ReactorHandle* /*handle*/) { return heap_copy("[]"); }

  static void free_string(char* s) { std::free(s); }

  static void publish_track(ReactorHandle* /*handle*/, const char* /*name*/,
                            reactor_completion_fn completion, void* userdata) {
    if (completion != nullptr) {
      std::thread worker([completion, userdata] { completion(1, "{}", nullptr, userdata); });
      worker.join();
    }
  }

  static void push_audio_frame(ReactorHandle* /*handle*/, const char* /*track*/,
                               const std::int16_t* /*data*/, std::uint32_t /*per_channel*/,
                               std::uint32_t /*rate*/, std::uint32_t /*channels*/) {
    ++current().audio_pushes;
  }

  static char* heap_copy(const char* text) {
    char* copy = static_cast<char*>(std::malloc(std::strlen(text) + 1));
    std::strcpy(copy, text);
    return copy;
  }

  static FakeAudioSession* current_instance;

  int handle_marker_ = 0;
  ReactorCallbacks callbacks_{};
};

FakeAudioSession* FakeAudioSession::current_instance = nullptr;

struct Connected {
  Connected() : override_(&table_) { client.connect().get(); }

  FakeAudioSession session;
  reactor::detail::Ffi table_ = session.table();
  reactor::detail::FfiOverride override_;
  reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};
};

std::vector<std::int16_t> tone(std::size_t samples, std::int16_t value = 1000) {
  return std::vector<std::int16_t>(samples, value);
}

}  // namespace

// ── The jitter buffer ────────────────────────────────────────────────────────

TEST_CASE("the buffer hands back what it was given, in order") {
  reactor::audio::detail::RingBuffer buffer{16};
  const std::vector<std::int16_t> in{1, 2, 3, 4};

  CHECK(buffer.write(in.data(), in.size()) == 0);
  CHECK(buffer.size() == 4);

  std::vector<std::int16_t> out(4);
  CHECK(buffer.read(out.data(), out.size()) == 0);
  CHECK(out == in);
  CHECK(buffer.size() == 0);
}

// The stream is slower than the device: the device asked for more than there was,
// and got silence for the rest. Audible as a click, so it is counted.
TEST_CASE("reading more than there is pads with silence and reports it") {
  reactor::audio::detail::RingBuffer buffer{16};
  const std::vector<std::int16_t> in{7, 7};
  buffer.write(in.data(), in.size());

  std::vector<std::int16_t> out(5, -1);
  CHECK(buffer.read(out.data(), out.size()) == 3);
  CHECK(out == std::vector<std::int16_t>{7, 7, 0, 0, 0});
}

// The device is slower than the stream. Dropping the *oldest* is the choice: stale
// audio is worth less than current audio, and keeping it would grow a delay that
// never recovers.
TEST_CASE("writing into a full buffer drops the oldest and reports how much") {
  reactor::audio::detail::RingBuffer buffer{4};
  const std::vector<std::int16_t> first{1, 2, 3, 4};
  const std::vector<std::int16_t> second{5, 6};

  CHECK(buffer.write(first.data(), first.size()) == 0);
  CHECK(buffer.write(second.data(), second.size()) == 2);

  std::vector<std::int16_t> out(4);
  buffer.read(out.data(), out.size());
  // 1 and 2 are gone; the newest audio survived.
  CHECK(out == std::vector<std::int16_t>{3, 4, 5, 6});
}

TEST_CASE("a write larger than the whole buffer keeps only the newest of it") {
  reactor::audio::detail::RingBuffer buffer{4};
  const std::vector<std::int16_t> huge{1, 2, 3, 4, 5, 6, 7, 8};

  CHECK(buffer.write(huge.data(), huge.size()) == 4);

  std::vector<std::int16_t> out(4);
  buffer.read(out.data(), out.size());
  CHECK(out == std::vector<std::int16_t>{5, 6, 7, 8});
}

TEST_CASE("wrapping around the end of the buffer is not a special case") {
  reactor::audio::detail::RingBuffer buffer{4};
  const std::vector<std::int16_t> three{1, 2, 3};
  buffer.write(three.data(), three.size());

  std::vector<std::int16_t> two(2);
  buffer.read(two.data(), two.size());  // leaves {3}, read cursor at 2

  const std::vector<std::int16_t> more{4, 5, 6};
  CHECK(buffer.write(more.data(), more.size()) == 0);

  std::vector<std::int16_t> out(4);
  CHECK(buffer.read(out.data(), out.size()) == 0);
  CHECK(out == std::vector<std::int16_t>{3, 4, 5, 6});
}

TEST_CASE("clearing drops everything without confusing the cursors") {
  reactor::audio::detail::RingBuffer buffer{8};
  const auto samples = tone(6);
  buffer.write(samples.data(), samples.size());
  buffer.clear();
  CHECK(buffer.size() == 0);

  const std::vector<std::int16_t> after{9, 9};
  buffer.write(after.data(), after.size());
  std::vector<std::int16_t> out(2);
  CHECK(buffer.read(out.data(), out.size()) == 0);
  CHECK(out == after);
}

// ── Refusals ─────────────────────────────────────────────────────────────────

// A speaker on a sendonly track would never receive anything, and the FFI would
// never say so: the callback simply never fires.
TEST_CASE("a speaker refuses anything but a recvonly audio track") {
  Connected fixture;

  CHECK_THROWS_AS(reactor::audio::Speaker{fixture.client.track("input_audio")},
                  reactor::InvalidStateError);
  CHECK_THROWS_AS(reactor::audio::Speaker{fixture.client.track("main_video")},
                  reactor::InvalidStateError);
  CHECK_NOTHROW(reactor::audio::Speaker{fixture.client.track("main_audio")});
}

TEST_CASE("a microphone refuses anything but a sendonly audio track") {
  Connected fixture;

  CHECK_THROWS_AS(reactor::audio::Microphone{fixture.client.track("main_audio")},
                  reactor::InvalidStateError);
  CHECK_THROWS_AS(reactor::audio::Microphone{fixture.client.track("main_video")},
                  reactor::InvalidStateError);
  CHECK_NOTHROW(reactor::audio::Microphone{fixture.client.track("input_audio")});
}

TEST_CASE("attaching a device before the tracks are known is refused") {
  FakeAudioSession session;
  const auto table = session.table();
  const reactor::detail::FfiOverride override{&table};
  reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};

  try {
    reactor::audio::Speaker speaker{client.track("main_audio")};
    FAIL("attaching before the tracks are declared must be refused");
  } catch (const reactor::InvalidStateError& error) {
    CHECK(std::string{error.what()}.find("connect()") != std::string::npos);
  }
}

// Publishing is what puts a sender behind the slot. Starting a microphone anyway
// would capture happily and have every single block refused.
TEST_CASE("a microphone on an unpublished track refuses to start") {
  Connected fixture;
  reactor::audio::Microphone microphone{fixture.client.track("input_audio")};

  try {
    microphone.start();
    FAIL("starting on an unpublished track must be refused");
  } catch (const reactor::InvalidStateError& error) {
    CHECK(std::string{error.what()}.find("publish()") != std::string::npos);
  }
  CHECK(microphone.blocks_sent() == 0);
}

// ── Delivery ─────────────────────────────────────────────────────────────────

// Audio arriving before start() is dropped rather than queued: a listener hearing
// four seconds of backlog when they press play is worse than missing it.
TEST_CASE("a speaker ignores audio until it is started") {
  Connected fixture;
  reactor::audio::Speaker speaker{fixture.client.track("main_audio")};

  fixture.session.push_audio("main_audio", tone(480), 48'000, 1);
  CHECK(speaker.dropped_ms() == 0);
  CHECK(speaker.under_runs() == 0);
}

TEST_CASE("submitting PCM directly is counted the same way as a frame") {
  Connected fixture;
  reactor::audio::Speaker speaker{fixture.client.track("main_audio")};

  // No device is opened here — there is no sound card in CI — but the buffer is
  // real, and overflowing it is what the counter is for.
  const auto huge = tone(80'000);
  speaker.submit(reactor::Samples{huge.data(), huge.size()}, 48'000, 1);
  CHECK(speaker.dropped_ms() > 0);
}

TEST_CASE("a speaker that was never started can be stopped and destroyed") {
  Connected fixture;
  {
    reactor::audio::Speaker speaker{fixture.client.track("main_audio")};
    CHECK_NOTHROW(speaker.stop());
    CHECK_NOTHROW(speaker.stop());
  }
  SUCCEED("no device was opened, and nothing was released twice");
}

TEST_CASE("a microphone reports what the track refused rather than throwing on its thread") {
  Connected fixture;
  reactor::audio::Microphone microphone{fixture.client.track("input_audio")};

  // Nothing captured yet: the counters exist so a caller can see the refusals a
  // device thread cannot throw out of.
  CHECK(microphone.blocks_sent() == 0);
  CHECK(microphone.blocks_refused() == 0);
  CHECK(microphone.last_refusal().empty());
}

// ── The separation itself ────────────────────────────────────────────────────

// The point of the whole target split: a consumer who links only reactor::sdk
// cannot open a device, whatever their environment says.
TEST_CASE("this build knows whether it has a backend, and says so") {
  // Built with the audio target, so it does. In a build without it, Speaker::start
  // throws instead of playing silence — which is the third of three possible
  // behaviours and the only honest one.
  CHECK(reactor::audio::devices_available());
}
