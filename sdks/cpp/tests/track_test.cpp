// Receiving: tracks by name, the filters, the frame trailer — and every refusal.
//
// The refusals are what this file is really about. Each one covers a case the
// native layer accepts and then does nothing about, which from the outside is
// indistinguishable from a model that sends nothing at all.

#include <atomic>
#include <chrono>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#include <catch2/catch_test_macros.hpp>

#include "detail/ffi.hpp"
#include "reactor/reactor.hpp"

namespace {

using namespace std::chrono_literals;

/// A fake library with a declared track list and a way to push frames.
class FakeSession {
 public:
  FakeSession() { current_instance = this; }

  ~FakeSession() {
    join_all();
    current_instance = nullptr;
  }

  FakeSession(const FakeSession&) = delete;
  FakeSession& operator=(const FakeSession&) = delete;
  FakeSession(FakeSession&&) = delete;
  FakeSession& operator=(FakeSession&&) = delete;

  static FakeSession& current() { return *current_instance; }

  reactor::detail::Ffi table() {
    reactor::detail::Ffi filled;
    filled.abi_version = &abi_version;
    filled.create_with_adm = &create_with_adm;
    filled.destroy = &destroy;
    filled.connect = &connect;
    filled.disconnect = &disconnect;
    filled.status = &status;
    filled.session_id = &session_id;
    filled.tracks = &tracks;
    filled.paused_tracks = &paused_tracks;
    filled.free_string = &free_string;
    return filled;
  }

  /// What `reactor_tracks` answers. The shape the runtime declares: an array of
  /// {name, kind, direction}.
  std::string declared_json = R"([{"name":"main_video","kind":"video","direction":"recvonly"},)"
                              R"({"name":"main_audio","kind":"audio","direction":"recvonly"},)"
                              R"({"name":"input_video","kind":"video","direction":"sendonly"},)"
                              R"({"name":"second_video","kind":"video","direction":"recvonly"}])";

  std::string paused_json = "[]";

  /// Deliver a video frame the way the library does: inline, on its own thread,
  /// with the buffer valid only for the call.
  void push_video(const std::string& track, std::uint32_t width, std::uint32_t height,
                  std::uint64_t frame_id = 0, std::uint64_t timestamp_us = 0,
                  const std::vector<std::uint8_t>& user_data = {}) {
    if (callbacks_.on_frame == nullptr) {
      return;
    }
    const std::vector<std::uint8_t> pixels(
        static_cast<std::size_t>(width) * static_cast<std::size_t>(height) * 4U, 0x7F);
    spawn([this, track, pixels, width, height, frame_id, timestamp_us, user_data] {
      callbacks_.on_frame(track.c_str(), pixels.data(), width, height, frame_id, timestamp_us,
                          user_data.empty() ? nullptr : user_data.data(),
                          static_cast<std::uint32_t>(user_data.size()), callbacks_.userdata);
    });
    join_all();
  }

  void push_audio(const std::string& track, std::uint32_t num_samples, std::uint32_t sample_rate,
                  std::uint32_t channels) {
    if (callbacks_.on_audio == nullptr) {
      return;
    }
    const std::vector<std::int16_t> samples(num_samples, 1234);
    spawn([this, track, samples, num_samples, sample_rate, channels] {
      callbacks_.on_audio(track.c_str(), samples.data(), num_samples, sample_rate, channels,
                          callbacks_.userdata);
    });
    join_all();
  }

  void push_track(const std::string& name, const char* mid) {
    if (callbacks_.on_track == nullptr) {
      return;
    }
    const std::string mid_value = mid == nullptr ? "" : mid;
    const bool has_mid = mid != nullptr;
    spawn([this, name, mid_value, has_mid] {
      callbacks_.on_track(name.c_str(), has_mid ? mid_value.c_str() : nullptr, callbacks_.userdata);
    });
    join_all();
  }

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
                                        const ReactorCallbacks* callbacks, int /*adm_mode*/) {
    auto& self = current();
    if (callbacks != nullptr) {
      self.callbacks_ = *callbacks;
    }
    return reinterpret_cast<ReactorHandle*>(&self.handle_marker_);
  }

  static int destroy(ReactorHandle* /*handle*/) {
    current().join_all();
    return 0;
  }

  static void connect(ReactorHandle* /*handle*/, const char* /*session_id*/,
                      const std::uint32_t* /*connection_id*/, reactor_completion_fn completion,
                      void* userdata) {
    if (completion == nullptr) {
      return;
    }
    current().spawn([completion, userdata] { completion(1, "{}", nullptr, userdata); });
  }

  static void disconnect(ReactorHandle* /*handle*/, reactor_completion_fn completion,
                         void* userdata) {
    if (completion == nullptr) {
      return;
    }
    current().spawn([completion, userdata] { completion(1, "{}", nullptr, userdata); });
  }

  static const char* status(ReactorHandle* handle) {
    return handle == nullptr ? "disconnected" : "ready";
  }

  static char* session_id(ReactorHandle* handle) {
    return handle == nullptr ? nullptr : heap_copy("session-abc");
  }

  static char* tracks(ReactorHandle* handle) {
    // "[]" before the session is accepted, which is what lets a caller tell "no
    // tracks yet" from a name that does not exist.
    return heap_copy(handle == nullptr ? "[]" : current().declared_json.c_str());
  }

  static char* paused_tracks(ReactorHandle* handle) {
    return heap_copy(handle == nullptr ? "[]" : current().paused_json.c_str());
  }

  static void free_string(char* s) { std::free(s); }

  static char* heap_copy(const char* text) {
    char* copy = static_cast<char*>(std::malloc(std::strlen(text) + 1));
    std::strcpy(copy, text);
    return copy;
  }

  static FakeSession* current_instance;

  int handle_marker_ = 0;
  ReactorCallbacks callbacks_{};
  std::mutex mutex_;
  std::vector<std::thread> threads_;
};

FakeSession* FakeSession::current_instance = nullptr;

/// A connected client against a fake session.
struct Connected {
  Connected() : override_(&table_) { client.connect().get(); }

  FakeSession session;
  reactor::detail::Ffi table_ = session.table();
  reactor::detail::FfiOverride override_;
  reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};
};

}  // namespace

TEST_CASE("a track is asked for by name") {
  Connected fixture;

  const auto video = fixture.client.track("main_video");
  CHECK(video.name() == "main_video");
  CHECK(video.kind() == reactor::TrackKind::Video);
  CHECK(video.direction() == reactor::TrackDirection::RecvOnly);
  CHECK_FALSE(video.paused());
}

// The FFI accepts any name and then does nothing with it, which looks exactly
// like a model that sends nothing. The message lists what the session does
// declare, because the usual cause is a typo or a model that renamed a slot.
TEST_CASE("a name the session never declared is refused, and the message lists the real ones") {
  Connected fixture;

  try {
    fixture.client.track("man_video");
    FAIL("an undeclared name must be refused");
  } catch (const reactor::NotFoundError& error) {
    const std::string message = error.what();
    CHECK(message.find("man_video") != std::string::npos);
    CHECK(message.find("main_video") != std::string::npos);
    CHECK(message.find("input_video") != std::string::npos);
  }
}

// Before the session has declared anything there is nothing to contradict, and a
// caller who knows their model is not wrong to ask early.
TEST_CASE("any name is allowed before the session declares its tracks") {
  FakeSession session;
  const auto table = session.table();
  const reactor::detail::FfiOverride override{&table};
  reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};

  const auto track = client.track("whatever");
  CHECK(track.name() == "whatever");
  CHECK_FALSE(track.kind().has_value());
  CHECK_FALSE(track.direction().has_value());
}

TEST_CASE("the track list filters in either order") {
  Connected fixture;

  const auto all = fixture.client.tracks();
  CHECK(all.size() == 4);

  const auto recv_video =
      all.with_direction(reactor::TrackDirection::RecvOnly).with_kind(reactor::TrackKind::Video);
  const auto video_recv =
      all.with_kind(reactor::TrackKind::Video).with_direction(reactor::TrackDirection::RecvOnly);
  CHECK(recv_video.size() == 2);
  CHECK(video_recv.size() == 2);

  CHECK(all.with_direction(reactor::TrackDirection::SendOnly).one().name() == "input_video");
  CHECK(all.with_kind(reactor::TrackKind::Audio).one().name() == "main_audio");
}

// Picking the first of several would answer a question the caller did not ask.
TEST_CASE("one() refuses both nothing and too much") {
  Connected fixture;
  const auto all = fixture.client.tracks();

  CHECK_THROWS_AS(all.with_kind(reactor::TrackKind::Video)
                      .with_direction(reactor::TrackDirection::RecvOnly)
                      .one(),
                  reactor::InvalidStateError);

  FakeSession& session = fixture.session;
  session.declared_json = "[]";
  CHECK_THROWS_AS(fixture.client.tracks().one(), reactor::NotFoundError);
}

TEST_CASE("frames arrive with the trailer the sender attached") {
  Connected fixture;

  struct Seen {
    std::string track;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::size_t bytes = 0;
    std::uint64_t frame_id = 0;
    std::uint64_t timestamp_us = 0;
    std::string user_data;
    bool has_metadata = false;
  };
  std::vector<Seen> seen;

  auto subscription =
      fixture.client.track("main_video").on_frame([&](const reactor::VideoFrame& frame) {
        Seen record;
        record.track = std::string{frame.track_name};
        record.width = frame.width;
        record.height = frame.height;
        record.bytes = frame.size_bytes();
        record.frame_id = frame.frame_id;
        record.timestamp_us = frame.timestamp_us;
        // Copied, because the frame is borrowed and gone when this returns.
        record.user_data.assign(frame.user_data.begin(), frame.user_data.end());
        record.has_metadata = frame.has_metadata();
        seen.push_back(std::move(record));
      });

  const std::vector<std::uint8_t> tag{'h', 'i'};
  fixture.session.push_video("main_video", 4, 2, 77, 1'700'000'000'000'000, tag);
  fixture.session.push_video("main_video", 4, 2);  // no trailer

  REQUIRE(seen.size() == 2);
  CHECK(seen[0].track == "main_video");
  CHECK(seen[0].width == 4);
  CHECK(seen[0].height == 2);
  CHECK(seen[0].bytes == std::size_t{4} * 2 * 4);
  CHECK(seen[0].frame_id == 77);
  CHECK(seen[0].timestamp_us == 1'700'000'000'000'000);
  CHECK(seen[0].user_data == "hi");
  CHECK(seen[0].has_metadata);

  // A frame with no trailer is ordinary, and reads as zeroes rather than as an
  // error: a model that does not tag is the common case.
  CHECK(seen[1].frame_id == 0);
  CHECK(seen[1].timestamp_us == 0);
  CHECK(seen[1].user_data.empty());
  CHECK_FALSE(seen[1].has_metadata);
}

// Every recvonly video track decodes into one callback, so the track name is the
// only thing that tells them apart — and each track's handler must see only its
// own frames.
TEST_CASE("two tracks receive their own frames and nobody else's") {
  Connected fixture;

  std::vector<std::string> main_frames;
  std::vector<std::string> second_frames;

  auto on_main = fixture.client.track("main_video").on_frame([&](const reactor::VideoFrame& frame) {
    main_frames.emplace_back(frame.track_name);
  });
  auto on_second =
      fixture.client.track("second_video").on_frame([&](const reactor::VideoFrame& frame) {
        second_frames.emplace_back(frame.track_name);
      });

  fixture.session.push_video("main_video", 2, 2);
  fixture.session.push_video("second_video", 2, 2);
  fixture.session.push_video("second_video", 2, 2);

  CHECK(main_frames == std::vector<std::string>{"main_video"});
  CHECK(second_frames == std::vector<std::string>{"second_video", "second_video"});
}

TEST_CASE("audio arrives as interleaved PCM with its rate and channel count") {
  Connected fixture;

  std::uint32_t num_samples = 0;
  std::uint32_t sample_rate = 0;
  std::uint32_t channels = 0;
  std::uint32_t frames = 0;
  std::int16_t first_sample = 0;

  auto subscription =
      fixture.client.track("main_audio").on_audio([&](const reactor::AudioFrame& frame) {
        num_samples = frame.num_samples;
        sample_rate = frame.sample_rate;
        channels = frame.channels;
        frames = frame.frames();
        first_sample = frame.samples[0];
      });

  fixture.session.push_audio("main_audio", 960, 48'000, 2);

  CHECK(num_samples == 960);
  CHECK(sample_rate == 48'000);
  CHECK(channels == 2);
  CHECK(frames == 480);
  CHECK(first_sample == 1234);
}

// Media runs inline on the library's thread, deliberately: blocking there is the
// backpressure. If the SDK queued frames onto the dispatcher instead, the handler
// would run on a different thread and the FFI would race ahead.
TEST_CASE("a frame handler runs on the thread the library delivered on") {
  Connected fixture;

  std::thread::id handler_thread;
  std::thread::id delivering_thread;

  auto subscription = fixture.client.track("main_video").on_frame([&](const reactor::VideoFrame&) {
    handler_thread = std::this_thread::get_id();
  });

  auto status = fixture.client.on_status([&](reactor::Status) {});
  fixture.session.push_video("main_video", 2, 2);

  CHECK(handler_thread != std::this_thread::get_id());
  CHECK(handler_thread != delivering_thread);  // it was set on the pushing thread
}

TEST_CASE("a removed frame subscription stops receiving") {
  Connected fixture;
  std::atomic<int> received{0};

  {
    auto subscription =
        fixture.client.track("main_video").on_frame([&](const reactor::VideoFrame&) {
          ++received;
        });
    fixture.session.push_video("main_video", 2, 2);
    CHECK(received.load() == 1);
  }

  fixture.session.push_video("main_video", 2, 2);
  CHECK(received.load() == 1);
}

// ── The refusals ─────────────────────────────────────────────────────────────

// A handler on a sendonly track never fires, and a handler that never fires is
// indistinguishable from a model that sends nothing.
TEST_CASE("a frame handler on a sendonly track is refused") {
  Connected fixture;

  try {
    fixture.client.track("input_video").on_frame([](const reactor::VideoFrame&) {});
    FAIL("a handler that could never fire must be refused");
  } catch (const reactor::InvalidStateError& error) {
    const std::string message = error.what();
    CHECK(message.find("sendonly") != std::string::npos);
    CHECK(message.find("push_frame") != std::string::npos);
  }
}

TEST_CASE("the wrong kind of handler is refused, and points at the right one") {
  Connected fixture;

  try {
    fixture.client.track("main_audio").on_frame([](const reactor::VideoFrame&) {});
    FAIL("a video handler on an audio track must be refused");
  } catch (const reactor::InvalidStateError& error) {
    CHECK(std::string{error.what()}.find("on_audio") != std::string::npos);
  }

  try {
    fixture.client.track("main_video").on_audio([](const reactor::AudioFrame&) {});
    FAIL("an audio handler on a video track must be refused");
  } catch (const reactor::InvalidStateError& error) {
    CHECK(std::string{error.what()}.find("on_frame") != std::string::npos);
  }
}

// Registering before the session has said what it sends cannot be validated, and
// a handler that silently never fires is the failure this SDK exists to prevent.
// So it is refused, with the fix in the message.
TEST_CASE("a frame handler registered before the tracks are known is refused") {
  FakeSession session;
  const auto table = session.table();
  const reactor::detail::FfiOverride override{&table};
  reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};

  try {
    client.track("main_video").on_frame([](const reactor::VideoFrame&) {});
    FAIL("registering before the tracks are declared must be refused");
  } catch (const reactor::InvalidStateError& error) {
    CHECK(std::string{error.what()}.find("connect()") != std::string::npos);
  }
}

// A Track can be obtained before the session declares anything — the caller knows
// their model. If what arrives then does not include that name, the refusal has
// to happen at registration, which is the last moment before a handler starts
// silently never firing.
TEST_CASE("a handler on a name the declarations turned out not to include is refused") {
  Connected fixture;

  // Asked for while nothing was declared yet.
  fixture.session.declared_json = "[]";
  auto guessed = fixture.client.track("main_vidoe");

  // Now the session says what it really has.
  fixture.session.declared_json =
      R"([{"name":"main_video","kind":"video","direction":"recvonly"}])";

  try {
    guessed.on_frame([](const reactor::VideoFrame&) {});
    FAIL("a handler for a name the session does not declare must be refused");
  } catch (const reactor::NotFoundError& error) {
    const std::string message = error.what();
    CHECK(message.find("main_vidoe") != std::string::npos);
    CHECK(message.find("main_video") != std::string::npos);
  }

  // And the declared one is accepted, which is the other half of the assertion.
  CHECK_NOTHROW(fixture.client.track("main_video").on_frame([](const reactor::VideoFrame&) {}));
}

TEST_CASE("a frame for a track with no handler is dropped rather than crashing") {
  Connected fixture;

  // Nothing registered anywhere. This is a diagnostic, not an error: the frame is
  // dropped and the SDK says so once.
  CHECK_NOTHROW(fixture.session.push_video("main_video", 2, 2));
  CHECK_NOTHROW(fixture.session.push_video("main_video", 2, 2));

  // And a name nothing declared, which is the other half of the same message.
  CHECK_NOTHROW(fixture.session.push_video("ghost_video", 2, 2));
  // Including the empty name the FFI sends for an unresolved transceiver.
  CHECK_NOTHROW(fixture.session.push_video("", 2, 2));
}

// ── on_track ─────────────────────────────────────────────────────────────────

TEST_CASE("on_track hands over a Track, not a name") {
  Connected fixture;

  std::mutex mutex;
  std::vector<std::string> names;
  std::vector<std::optional<std::string>> mids;
  std::vector<std::optional<reactor::TrackKind>> kinds;

  auto subscription = fixture.client.on_track([&](reactor::Track track) {
    const std::lock_guard<std::mutex> lock(mutex);
    names.push_back(track.name());
    mids.push_back(track.mid());
    kinds.push_back(track.kind());
  });

  fixture.session.push_track("main_video", "0");

  const auto deadline = std::chrono::steady_clock::now() + 2000ms;
  while (std::chrono::steady_clock::now() < deadline) {
    const std::lock_guard<std::mutex> lock(mutex);
    if (!names.empty()) {
      break;
    }
    std::this_thread::sleep_for(1ms);
  }

  const std::lock_guard<std::mutex> lock(mutex);
  REQUIRE(names.size() == 1);
  CHECK(names.front() == "main_video");
  // The mid is recorded before the event is dispatched, so the Track a handler
  // receives can already answer for it.
  CHECK(mids.front() == std::optional<std::string>{"0"});
  CHECK(kinds.front() == reactor::TrackKind::Video);
}

TEST_CASE("a track with no mid yet still arrives") {
  Connected fixture;
  CHECK_NOTHROW(fixture.session.push_track("main_video", nullptr));
  CHECK_FALSE(fixture.client.track("main_video").mid().has_value());
}

TEST_CASE("paused reads from the session rather than from a cache") {
  Connected fixture;
  CHECK_FALSE(fixture.client.track("main_video").paused());

  fixture.session.paused_json = R"(["main_video"])";
  CHECK(fixture.client.track("main_video").paused());
  CHECK_FALSE(fixture.client.track("main_audio").paused());

  // A reconnect resumes recvonly tracks, and a Track holding a stale `true` would
  // go on claiming otherwise.
  fixture.session.paused_json = "[]";
  CHECK_FALSE(fixture.client.track("main_video").paused());
}

TEST_CASE("a track outliving its client refuses instead of dereferencing it") {
  FakeSession session;
  const auto table = session.table();
  const reactor::detail::FfiOverride override{&table};

  std::optional<reactor::Track> orphan;
  {
    reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};
    client.connect().get();
    orphan = client.track("main_video");
  }

  // The name is the one thing it still knows for certain.
  CHECK(orphan->name() == "main_video");
  CHECK_THROWS_AS(orphan->kind(), reactor::InvalidStateError);
  CHECK_THROWS_AS(orphan->on_frame([](const reactor::VideoFrame&) {}), reactor::InvalidStateError);
}

TEST_CASE("the kind and direction spellings round-trip") {
  for (const auto kind : {reactor::TrackKind::Video, reactor::TrackKind::Audio}) {
    CHECK(reactor::track_kind_from_string(reactor::to_string(kind)) == kind);
  }
  for (const auto direction :
       {reactor::TrackDirection::SendOnly, reactor::TrackDirection::RecvOnly}) {
    CHECK(reactor::track_direction_from_string(reactor::to_string(direction)) == direction);
  }
  // Nothing, rather than a guess: a track this build cannot classify is one it
  // must not claim to understand.
  CHECK_FALSE(reactor::track_kind_from_string("haptic").has_value());
  CHECK_FALSE(reactor::track_direction_from_string("sendrecv").has_value());
}
