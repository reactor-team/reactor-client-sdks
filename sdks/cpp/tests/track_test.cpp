// Receiving: tracks by name, the filters, the frame trailer — and every refusal.
//
// The refusals are what this file is really about. Each one covers a case the
// native layer accepts and then does nothing about, which from the outside is
// indistinguishable from a model that sends nothing at all.

#include <atomic>
#include <chrono>
#include <cstring>
#include <optional>
#include <string>
#include <thread>
#include <type_traits>
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
    filled.publish_track = &publish_track;
    filled.unpublish_track = &unpublish_track;
    filled.pause_track = &pause_track;
    filled.resume_track = &resume_track;
    filled.push_video_frame = &push_video_frame;
    filled.push_video_frame_with_metadata = &push_video_frame_with_metadata;
    filled.push_video_frame_with_metadata_at = &push_video_frame_with_metadata_at;
    filled.push_audio_frame = &push_audio_frame;
    filled.time_micros = &time_micros;
    return filled;
  }

  // ── What the SDK sent ─────────────────────────────────────────────────────

  /// One recorded push. Which entry point was used matters: the plain one drops
  /// the tag, and the tagged one stamps the time as it arrives.
  struct Push {
    std::string track;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::string user_data;
    std::optional<std::int64_t> capture_time_us;
    bool via_metadata_entry_point = false;
  };
  std::vector<Push> pushes;

  struct AudioPush {
    std::string track;
    std::uint32_t samples_per_channel = 0;
    std::uint32_t sample_rate = 0;
    std::uint32_t channels = 0;
  };
  std::vector<AudioPush> audio_pushes;

  std::vector<std::string> published;
  std::vector<std::string> unpublished;
  std::vector<std::string> paused_calls;
  std::vector<std::string> resumed_calls;

  /// What `publish_track` answers with. Empty means success.
  std::string publish_error;

  /// What the sync `unpublish_track` returns. Empty means success (null).
  std::string unpublish_error;

  /// What `reactor_status` answers. A test can move it without an event, which is
  /// what a reconnect in flight looks like from the SDK's side.
  std::string current_status = "ready";

  /// Report a new status the way the library does: change it, then say so.
  void set_status(const std::string& status) {
    current_status = status;
    if (callbacks_.on_status == nullptr) {
      return;
    }
    spawn([this, status] { callbacks_.on_status(status.c_str(), callbacks_.userdata); });
    join_all();
  }

  /// What `reactor_tracks` answers. The shape the runtime declares: an array of
  /// {name, kind, direction}.
  std::string declared_json = R"([{"name":"main_video","kind":"video","direction":"recvonly"},)"
                              R"({"name":"main_audio","kind":"audio","direction":"recvonly"},)"
                              R"({"name":"input_video","kind":"video","direction":"sendonly"},)"
                              R"({"name":"second_video","kind":"video","direction":"recvonly"}])";

  std::string paused_json = "[]";

  /// Whether `publish_track` answers at all. False leaves the publish in flight,
  /// which is the window where there is no sender behind the slot yet.
  bool answer_publish = true;
  reactor_completion_fn held_publish = nullptr;
  void* held_publish_userdata = nullptr;

  /// Called from `tracks()` after its answer is taken and before it is handed
  /// back. Lets a test invalidate the SDK's cache *during* a read, which is the
  /// race a generation counter is for.
  std::function<void()> while_reading_tracks;

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

  /// Announce a new set of capabilities, as the runtime does when the session is
  /// accepted and again after a renegotiation. What the SDK reads from it is that
  /// the answer changed — the entries themselves it takes from `reactor_tracks`.
  void push_capabilities() {
    if (callbacks_.on_capabilities == nullptr) {
      return;
    }
    spawn([this] {
      const std::string caps = R"({"protocol_version":1,"tracks":[],"commands":[]})";
      callbacks_.on_capabilities(caps.c_str(), callbacks_.userdata);
    });
    join_all();
  }

  /// Change what the session declares, the way it really changes: a new list plus
  /// the event that says so.
  void redeclare(std::string tracks_json) {
    declared_json = std::move(tracks_json);
    push_capabilities();
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
    return handle == nullptr ? "disconnected" : current().current_status.c_str();
  }

  static char* session_id(ReactorHandle* handle) {
    return handle == nullptr ? nullptr : heap_copy("session-abc");
  }

  static char* tracks(ReactorHandle* handle) {
    auto& self = current();
    // The answer is taken first and the hook runs after it: that is what a read
    // racing an invalidation looks like — an answer built from the state before the
    // change, handed back after it. Answering with the *new* list would be a race
    // with nothing stale in it.
    // "[]" before the session is accepted, which is what lets a caller tell "no
    // tracks yet" from a name that does not exist.
    std::string answer = handle == nullptr ? "[]" : self.declared_json;
    if (self.while_reading_tracks) {
      const auto hook = self.while_reading_tracks;
      self.while_reading_tracks = nullptr;
      hook();
    }
    return heap_copy(answer.c_str());
  }

  static char* paused_tracks(ReactorHandle* handle) {
    return heap_copy(handle == nullptr ? "[]" : current().paused_json.c_str());
  }

  static void free_string(char* s) { std::free(s); }

  static void publish_track(ReactorHandle* /*handle*/, const char* name,
                            reactor_completion_fn completion, void* userdata) {
    auto& self = current();
    self.published.emplace_back(name == nullptr ? "" : name);
    if (completion == nullptr) {
      return;
    }
    if (!self.answer_publish) {
      self.held_publish = completion;
      self.held_publish_userdata = userdata;
      return;
    }

    const std::string error = self.publish_error;
    self.spawn([completion, userdata, error] {
      if (error.empty()) {
        completion(1, "{}", nullptr, userdata);
      } else {
        completion(0, nullptr, error.c_str(), userdata);
      }
    });
  }

  static char* unpublish_track(ReactorHandle* /*handle*/, const char* name) {
    auto& self = current();
    self.unpublished.emplace_back(name == nullptr ? "" : name);
    if (self.unpublish_error.empty()) {
      return nullptr;
    }
    return heap_copy(self.unpublish_error.c_str());
  }

  static void pause_track(ReactorHandle* /*handle*/, const char* name,
                          reactor_completion_fn completion, void* userdata) {
    auto& self = current();
    self.paused_calls.emplace_back(name == nullptr ? "" : name);
    if (completion != nullptr) {
      self.spawn([completion, userdata] { completion(1, "{}", nullptr, userdata); });
    }
  }

  static void resume_track(ReactorHandle* /*handle*/, const char* name,
                           reactor_completion_fn completion, void* userdata) {
    auto& self = current();
    self.resumed_calls.emplace_back(name == nullptr ? "" : name);
    if (completion != nullptr) {
      self.spawn([completion, userdata] { completion(1, "{}", nullptr, userdata); });
    }
  }

  static void push_video_frame(ReactorHandle* /*handle*/, const char* track,
                               const std::uint8_t* /*data*/, std::uint32_t width,
                               std::uint32_t height) {
    current().pushes.push_back(
        Push{track == nullptr ? "" : track, width, height, "", std::nullopt, false});
  }

  static void push_video_frame_with_metadata(ReactorHandle* /*handle*/, const char* track,
                                             const std::uint8_t* /*data*/, std::uint32_t width,
                                             std::uint32_t height, const std::uint8_t* user_data,
                                             std::uint32_t user_data_len) {
    current().pushes.push_back(Push{
        track == nullptr ? "" : track, width, height,
        user_data == nullptr ? std::string{}
                             : std::string{reinterpret_cast<const char*>(user_data), user_data_len},
        std::nullopt, true});
  }

  static void push_video_frame_with_metadata_at(ReactorHandle* /*handle*/, const char* track,
                                                const std::uint8_t* /*data*/, std::uint32_t width,
                                                std::uint32_t height, const std::uint8_t* user_data,
                                                std::uint32_t user_data_len,
                                                std::int64_t capture_time_us) {
    current().pushes.push_back(Push{
        track == nullptr ? "" : track, width, height,
        user_data == nullptr ? std::string{}
                             : std::string{reinterpret_cast<const char*>(user_data), user_data_len},
        capture_time_us, true});
  }

  /// A clock that moves, which is all the contract is: a stamp only means anything
  /// against the value the next frame gets.
  static std::int64_t time_micros() { return ++current().clock_; }

  static void push_audio_frame(ReactorHandle* /*handle*/, const char* track,
                               const std::int16_t* /*data*/, std::uint32_t samples_per_channel,
                               std::uint32_t sample_rate, std::uint32_t channels) {
    current().audio_pushes.push_back(
        AudioPush{track == nullptr ? "" : track, samples_per_channel, sample_rate, channels});
  }

  static char* heap_copy(const char* text) {
    char* copy = static_cast<char*>(std::malloc(std::strlen(text) + 1));
    std::strcpy(copy, text);
    return copy;
  }

  static FakeSession* current_instance;

  int handle_marker_ = 0;
  std::int64_t clock_ = 1'700'000'000'000'000;
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

/// Wait for a track's mid to settle on `wanted`. The event crosses a thread, so
/// there is no deterministic point at which to assert.
bool eventually_mid(Connected& fixture, const std::string& name,
                    const std::optional<std::string>& wanted) {
  const auto deadline = std::chrono::steady_clock::now() + 2000ms;
  while (std::chrono::steady_clock::now() < deadline) {
    if (fixture.client.track(name).mid() == wanted) {
      return true;
    }
    std::this_thread::sleep_for(1ms);
  }
  return fixture.client.track(name).mid() == wanted;
}

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

// tracks() is indexed by position, and every SDK promises that position is the
// order the session declared them in. Collecting them by name sorted them
// alphabetically, which silently renumbered what tracks()[0] means: here the
// declaration order and the alphabetical order disagree on all four.
TEST_CASE("tracks() keeps the order the session declared them in") {
  Connected fixture;

  std::vector<std::string> names;
  for (const auto& track : fixture.client.tracks()) {
    names.push_back(track.name());
  }

  CHECK(names ==
        std::vector<std::string>{"main_video", "main_audio", "input_video", "second_video"});
  CHECK(fixture.client.tracks()[0].name() == "main_video");
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

  fixture.session.redeclare("[]");
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
  fixture.session.redeclare("[]");
  auto guessed = fixture.client.track("main_vidoe");

  // Now the session says what it really has.
  fixture.session.redeclare(R"([{"name":"main_video","kind":"video","direction":"recvonly"}])");

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

  // A declared track with no handler: dropped, and *silently*. Not caring about
  // one of several outputs is a choice, and there is always a gap between connect
  // resolving and a handler being registered — warning there put a line in the
  // output of every correct program.
  CHECK_NOTHROW(fixture.session.push_video("main_video", 2, 2));
  CHECK_NOTHROW(fixture.session.push_video("main_video", 2, 2));

  // A name nothing declared is different: nothing can route it, so it is worth a
  // line — once.
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

// A renegotiation can report a track that no longer has a transceiver. Keeping the
// MID it had last time hands the caller a string from a session that is gone —
// worse than none, because they cannot tell it is stale.
TEST_CASE("a track reported without a mid loses the one it had") {
  Connected fixture;

  fixture.session.push_track("main_video", "0");
  CHECK(eventually_mid(fixture, "main_video", std::optional<std::string>{"0"}));

  fixture.session.push_track("main_video", nullptr);
  CHECK(eventually_mid(fixture, "main_video", std::nullopt));
}

// Not cached at all, unlike the declared list: this one changes without any event
// to announce it, so every read asks.
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

// A publish in flight is not a publish. Until the request is answered there is no
// sender behind the slot, so counting it as published let a push through to the
// FFI, which takes the frame and drops it — and the caller is told nothing.
TEST_CASE("pushing into a track whose publish is still in flight is refused") {
  Connected fixture;
  fixture.session.answer_publish = false;

  auto track = fixture.client.track("input_video");
  auto publishing = track.publish();

  CHECK_FALSE(track.published());
  const std::vector<std::uint8_t> pixels(static_cast<std::size_t>(4U) * 4U * 4U, 0);
  REQUIRE_THROWS_AS(track.push_frame(reactor::Bytes{pixels.data(), pixels.size()}, 4, 4),
                    reactor::InvalidStateError);
  // And the refusal says what to wait for rather than telling them to do what they
  // are already doing.
  try {
    track.push_frame(reactor::Bytes{pixels.data(), pixels.size()}, 4, 4);
  } catch (const reactor::InvalidStateError& error) {
    CHECK(std::string{error.what()}.find("still being published") != std::string::npos);
  }

  // Answered, and now it is a publish.
  fixture.session.held_publish(1, "{}", nullptr, fixture.session.held_publish_userdata);
  publishing.get();
  CHECK(track.published());
  CHECK_NOTHROW(track.push_frame(reactor::Bytes{pixels.data(), pixels.size()}, 4, 4));
}

// The declared list is read from the FFI without the media lock — a JSON parse is
// not something to hold one across — so an event can invalidate the cache while
// that read is in flight. Storing the read afterwards put the *older* list back
// with nothing left to invalidate it, and a newly declared track stayed invisible.
TEST_CASE("a list invalidated mid-read does not become the cached answer") {
  Connected fixture;

  // Warm nothing: the first read is the one that races. While the FFI is answering
  // with the old list, the session declares a new one and says so.
  fixture.session.while_reading_tracks = [&fixture] {
    fixture.session.redeclare(R"([{"name":"main_video","kind":"video","direction":"recvonly"},)"
                              R"({"name":"late_video","kind":"video","direction":"recvonly"}])");
  };

  // This read answers from the list that was current when it started.
  (void)fixture.client.tracks();

  // The next one must see the new list rather than a cache written after the
  // invalidation.
  std::vector<std::string> names;
  for (const auto& track : fixture.client.tracks()) {
    names.push_back(track.name());
  }
  CHECK(names == std::vector<std::string>{"main_video", "late_video"});
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

// ── Sending ──────────────────────────────────────────────────────────────────

namespace {

/// A frame of the right size for `width` x `height`.
std::vector<std::uint8_t> bgra_frame(std::uint32_t width, std::uint32_t height) {
  return std::vector<std::uint8_t>(
      static_cast<std::size_t>(width) * static_cast<std::size_t>(height) * 4U, 0x40);
}

reactor::Bytes as_bytes(const std::vector<std::uint8_t>& buffer) {
  return reactor::Bytes{buffer.data(), buffer.size()};
}

}  // namespace

TEST_CASE("publishing activates the slot, and the SDK remembers it") {
  Connected fixture;
  auto input = fixture.client.track("input_video");

  CHECK_FALSE(input.published());
  input.publish().get();
  CHECK(input.published());
  CHECK(fixture.session.published == std::vector<std::string>{"input_video"});
}

// The flag goes on before the request is sent, so a frame pushed in between is
// refused rather than dropped — which means a refused publish has to take it back
// off, or the next push would be accepted onto a slot with nothing behind it.
TEST_CASE("a refused publish leaves the track unpublished") {
  Connected fixture;
  fixture.session.publish_error =
      R"({"code":"CONFLICT","message":"the model refused the slot","status":409})";

  auto input = fixture.client.track("input_video");
  CHECK_THROWS_AS(input.publish().get(), reactor::ConflictError);
  CHECK_FALSE(input.published());
}

TEST_CASE("pushing a frame reaches the plain entry point when there is nothing to add") {
  Connected fixture;
  auto input = fixture.client.track("input_video");
  input.publish().get();

  const auto frame = bgra_frame(4, 2);
  input.push_frame(as_bytes(frame), 4, 2);

  REQUIRE(fixture.session.pushes.size() == 1);
  const auto& push = fixture.session.pushes.front();
  CHECK(push.track == "input_video");
  CHECK(push.width == 4);
  CHECK(push.height == 2);
  CHECK_FALSE(push.via_metadata_entry_point);
  CHECK(push.user_data.empty());
  CHECK_FALSE(push.capture_time_us.has_value());
}

TEST_CASE("a tag and a capture time each reach the entry point that carries them") {
  Connected fixture;
  auto input = fixture.client.track("input_video");
  input.publish().get();
  const auto frame = bgra_frame(2, 2);

  const std::vector<std::uint8_t> tag{'t', 'a', 'g'};

  reactor::Track::FrameOptions tagged;
  tagged.user_data = as_bytes(tag);
  input.push_frame(as_bytes(frame), 2, 2, tagged);

  reactor::Track::FrameOptions stamped;
  stamped.capture_time_us = 1'234'567;
  input.push_frame(as_bytes(frame), 2, 2, stamped);

  reactor::Track::FrameOptions both;
  both.user_data = as_bytes(tag);
  both.capture_time_us = 7'654'321;
  input.push_frame(as_bytes(frame), 2, 2, both);

  REQUIRE(fixture.session.pushes.size() == 3);
  CHECK(fixture.session.pushes[0].user_data == "tag");
  CHECK_FALSE(fixture.session.pushes[0].capture_time_us.has_value());
  // Stamping and tagging are independent: a stamped frame with no tag still goes
  // through the entry point that takes both.
  CHECK(fixture.session.pushes[1].user_data.empty());
  CHECK(fixture.session.pushes[1].capture_time_us == std::int64_t{1'234'567});
  CHECK(fixture.session.pushes[2].user_data == "tag");
  CHECK(fixture.session.pushes[2].capture_time_us == std::int64_t{7'654'321});
}

// Tracks are synchronised by *sharing* a capture time, not by reaching the encoder
// together. One read, stamped on every track, has to arrive unchanged on both.
TEST_CASE("one capture time stamped on two tracks reaches both pushes unchanged") {
  Connected fixture;
  fixture.session.redeclare(R"([{"name":"input_video","kind":"video","direction":"sendonly"},)"
                            R"({"name":"input_alpha","kind":"video","direction":"sendonly"}])");

  auto first = fixture.client.track("input_video");
  auto second = fixture.client.track("input_alpha");
  first.publish().get();
  second.publish().get();

  const auto frame = bgra_frame(2, 2);
  reactor::Track::FrameOptions options;
  options.capture_time_us = reactor::time_micros();

  first.push_frame(as_bytes(frame), 2, 2, options);
  second.push_frame(as_bytes(frame), 2, 2, options);

  REQUIRE(fixture.session.pushes.size() == 2);
  CHECK(fixture.session.pushes[0].capture_time_us == options.capture_time_us);
  CHECK(fixture.session.pushes[1].capture_time_us == options.capture_time_us);
}

TEST_CASE("pushing audio divides the interleaved samples by the channel count") {
  Connected fixture;
  fixture.session.redeclare(R"([{"name":"input_audio","kind":"audio","direction":"sendonly"}])");

  auto input = fixture.client.track("input_audio");
  input.publish().get();

  const std::vector<std::int16_t> pcm(960, 7);
  input.push_audio(reactor::Samples{pcm.data(), pcm.size()}, 48'000, 2);

  REQUIRE(fixture.session.audio_pushes.size() == 1);
  const auto& push = fixture.session.audio_pushes.front();
  CHECK(push.track == "input_audio");
  CHECK(push.samples_per_channel == 480);
  CHECK(push.sample_rate == 48'000);
  CHECK(push.channels == 2);
}

TEST_CASE("pausing and resuming reach the session") {
  Connected fixture;
  auto video = fixture.client.track("main_video");

  video.pause().get();
  video.resume().get();

  CHECK(fixture.session.paused_calls == std::vector<std::string>{"main_video"});
  CHECK(fixture.session.resumed_calls == std::vector<std::string>{"main_video"});
}

TEST_CASE("unpublishing is synchronous and clears the flag") {
  Connected fixture;
  auto input = fixture.client.track("input_video");
  input.publish().get();

  input.unpublish();

  CHECK_FALSE(input.published());
  CHECK(fixture.session.unpublished == std::vector<std::string>{"input_video"});
}

// A failed unpublish that cleared the flag would be unretryable: the slot is
// still activated as far as the session knows, and the SDK would refuse to try
// again.
TEST_CASE("a failed unpublish leaves the track published, so it can be retried") {
  Connected fixture;
  auto input = fixture.client.track("input_video");
  input.publish().get();

  fixture.session.unpublish_error =
      R"({"code":"NETWORK_ERROR","message":"the data channel dropped","recoverable":true})";
  CHECK_THROWS_AS(input.unpublish(), reactor::NetworkError);
  CHECK(input.published());

  fixture.session.unpublish_error.clear();
  CHECK_NOTHROW(input.unpublish());
  CHECK_FALSE(input.published());
}

// ── The refusals ─────────────────────────────────────────────────────────────

TEST_CASE("pushing into a recvonly track is refused, naming the direction") {
  Connected fixture;
  const auto frame = bgra_frame(2, 2);

  try {
    fixture.client.track("main_video").push_frame(as_bytes(frame), 2, 2);
    FAIL("a push into a recvonly track must be refused");
  } catch (const reactor::InvalidStateError& error) {
    const std::string message = error.what();
    CHECK(message.find("recvonly") != std::string::npos);
    CHECK(message.find("on_frame") != std::string::npos);
  }
  CHECK(fixture.session.pushes.empty());
}

TEST_CASE("pushing before publish is refused") {
  Connected fixture;
  const auto frame = bgra_frame(2, 2);

  try {
    fixture.client.track("input_video").push_frame(as_bytes(frame), 2, 2);
    FAIL("a push before publish must be refused");
  } catch (const reactor::InvalidStateError& error) {
    CHECK(std::string{error.what()}.find("publish()") != std::string::npos);
  }
  CHECK(fixture.session.pushes.empty());
}

// C++ hands the FFI a pointer and a size, and the FFI reads what it is told to
// read — so this check is the only thing between a typo and a read past the end
// of the caller's buffer.
TEST_CASE("a frame whose length does not match its dimensions is refused") {
  Connected fixture;
  auto input = fixture.client.track("input_video");
  input.publish().get();

  const auto too_small = bgra_frame(2, 2);
  try {
    input.push_frame(as_bytes(too_small), 4, 4);
    FAIL("a buffer that does not match must be refused");
  } catch (const reactor::BadRequestError& error) {
    const std::string message = error.what();
    CHECK(message.find("4x4") != std::string::npos);
    CHECK(message.find("64") != std::string::npos);  // expected
    CHECK(message.find("16") != std::string::npos);  // actual
  }

  CHECK_THROWS_AS(input.push_frame(reactor::Bytes{}, 2, 2), reactor::BadRequestError);
  CHECK(fixture.session.pushes.empty());
}

// A reconnect resumes recvonly tracks and nothing else, so a slot published before
// one is not published after it. Remembering otherwise is exactly the silent
// failure the refusals exist to prevent.
TEST_CASE("a status leaving ready un-publishes everything") {
  Connected fixture;
  auto input = fixture.client.track("input_video");
  input.publish().get();
  CHECK(input.published());

  fixture.session.set_status("connecting");
  CHECK_FALSE(input.published());

  const auto frame = bgra_frame(2, 2);
  try {
    input.push_frame(as_bytes(frame), 2, 2);
    FAIL("a push after the session left ready must be refused");
  } catch (const reactor::InvalidStateError& error) {
    CHECK(std::string{error.what()}.find("publish()") != std::string::npos);
  }
}

TEST_CASE("a push on a session that is no longer ready is refused even when published") {
  Connected fixture;
  auto input = fixture.client.track("input_video");
  input.publish().get();

  // The status changes without an event — a reconnect in flight, say. The push
  // still has to be refused, because the FFI would take the frame and drop it.
  fixture.session.current_status = "waiting";
  const auto frame = bgra_frame(2, 2);
  try {
    input.push_frame(as_bytes(frame), 2, 2);
    FAIL("a push while not ready must be refused");
  } catch (const reactor::InvalidStateError& error) {
    CHECK(std::string{error.what()}.find("waiting") != std::string::npos);
  }
}

TEST_CASE("audio into a video track and frames into an audio track are both refused") {
  Connected fixture;
  fixture.session.redeclare(R"([{"name":"input_video","kind":"video","direction":"sendonly"},)"
                            R"({"name":"input_audio","kind":"audio","direction":"sendonly"}])");

  auto video = fixture.client.track("input_video");
  auto audio = fixture.client.track("input_audio");
  video.publish().get();
  audio.publish().get();

  const std::vector<std::int16_t> pcm(480, 0);
  const auto frame = bgra_frame(2, 2);

  CHECK_THROWS_AS(video.push_audio(reactor::Samples{pcm.data(), pcm.size()}),
                  reactor::InvalidStateError);
  CHECK_THROWS_AS(audio.push_frame(as_bytes(frame), 2, 2), reactor::InvalidStateError);
}

TEST_CASE("PCM that does not divide by the channel count is refused") {
  Connected fixture;
  fixture.session.redeclare(R"([{"name":"input_audio","kind":"audio","direction":"sendonly"}])");
  auto input = fixture.client.track("input_audio");
  input.publish().get();

  const std::vector<std::int16_t> odd(481, 0);
  CHECK_THROWS_AS(input.push_audio(reactor::Samples{odd.data(), odd.size()}, 48'000, 2),
                  reactor::BadRequestError);
  CHECK_THROWS_AS(input.push_audio(reactor::Samples{}, 48'000, 1), reactor::BadRequestError);
  CHECK(fixture.session.audio_pushes.empty());
}

TEST_CASE("publishing a recvonly track is refused") {
  Connected fixture;
  try {
    fixture.client.track("main_video").publish().get();
    FAIL("publishing a recvonly track must be refused");
  } catch (const reactor::InvalidStateError& error) {
    CHECK(std::string{error.what()}.find("recvonly") != std::string::npos);
  }
  CHECK(fixture.session.published.empty());
}

TEST_CASE("publishing before there is a session is refused") {
  FakeSession session;
  const auto table = session.table();
  const reactor::detail::FfiOverride override{&table};
  reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};

  try {
    client.track("input_video").publish().get();
    FAIL("publishing without a session must be refused");
  } catch (const reactor::InvalidStateError& error) {
    CHECK(std::string{error.what()}.find("declared") != std::string::npos);
  }
}

// There is no push_frame(name, …) on the client, and there must not be: a second
// way to say the same thing, and the one that cannot check what it was asked.
TEST_CASE("the client has no name-based twins of the track methods") {
  static_assert(!std::is_invocable_v<decltype(&reactor::Reactor::track), reactor::Reactor&,
                                     const std::string&, int>,
                "Reactor::track takes a name and nothing else");
  SUCCEED("asserted at compile time");
}
