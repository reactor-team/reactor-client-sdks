// Clips: asking for one, and turning it into a file.
//
// The assembly itself lives in the Rust core, so what is left to test here is the
// part this SDK owns: the arguments it passes down, the bound on waiting, and the
// refusals around both.

#include <cstring>
#include <optional>
#include <string>
#include <thread>
#include <vector>

#include <catch2/catch_test_macros.hpp>

#include "detail/ffi.hpp"
#include "reactor/reactor.hpp"

namespace {

class FakeRecorder {
 public:
  FakeRecorder() { current_instance = this; }

  ~FakeRecorder() {
    join_all();
    current_instance = nullptr;
  }

  FakeRecorder(const FakeRecorder&) = delete;
  FakeRecorder& operator=(const FakeRecorder&) = delete;
  FakeRecorder(FakeRecorder&&) = delete;
  FakeRecorder& operator=(FakeRecorder&&) = delete;

  static FakeRecorder& current() { return *current_instance; }

  reactor::detail::Ffi table() {
    reactor::detail::Ffi filled;
    filled.abi_version = &abi_version;
    filled.create_with_adm = &create_with_adm;
    filled.destroy = &destroy;
    filled.connect = &connect;
    filled.status = &status;
    filled.free_string = &free_string;
    filled.request_clip = &request_clip;
    filled.request_recording = &request_recording;
    filled.download_clip = &download_clip;
    return filled;
  }

  struct ClipRequest {
    double duration_seconds = 0.0;
  };
  std::vector<ClipRequest> clip_requests;
  int recording_requests = 0;

  struct Download {
    bool had_handle = false;
    std::string playlist_url;
    std::string jwt;
    std::string out_path;
    double ready_timeout_seconds = 0.0;
    bool had_progress = false;
  };
  std::vector<Download> downloads;

  std::string clip_result =
      R"({"playlist_url":"https://api.reactor.inc/clips/abc.m3u8","session_id":"sess_1",)"
      R"("kind":"snap","start_marker":10.0,"end_marker":20.0,"now_marker":20.0,)"
      R"("predicted_ready_at_ms":1700000000000.0})";
  std::string clip_error;
  std::string download_error;
  std::string current_status = "ready";

  /// How many progress callbacks the fake download reports before finishing.
  std::uint32_t progress_steps = 0;

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
    if (completion != nullptr) {
      current().spawn([completion, userdata] { completion(1, "{}", nullptr, userdata); });
    }
  }

  static const char* status(ReactorHandle* handle) {
    return handle == nullptr ? "disconnected" : current().current_status.c_str();
  }

  static void free_string(char* s) { std::free(s); }

  static void request_clip(ReactorHandle* /*handle*/, double duration_seconds,
                           reactor_completion_fn completion, void* userdata) {
    auto& self = current();
    self.clip_requests.push_back(ClipRequest{duration_seconds});
    self.answer_clip(completion, userdata);
  }

  static void request_recording(ReactorHandle* /*handle*/, reactor_completion_fn completion,
                                void* userdata) {
    auto& self = current();
    ++self.recording_requests;
    self.answer_clip(completion, userdata);
  }

  void answer_clip(reactor_completion_fn completion, void* userdata) {
    if (completion == nullptr) {
      return;
    }
    const std::string result = clip_result;
    const std::string error = clip_error;
    spawn([completion, userdata, result, error] {
      if (error.empty()) {
        completion(1, result.c_str(), nullptr, userdata);
      } else {
        completion(0, nullptr, error.c_str(), userdata);
      }
    });
  }

  static void download_clip(ReactorHandle* handle, const char* playlist_url, const char* jwt,
                            const char* out_path, double ready_timeout_seconds, int /*local*/,
                            reactor_progress_fn progress, reactor_completion_fn completion,
                            void* userdata) {
    auto& self = current();
    self.downloads.push_back(Download{
        handle != nullptr, playlist_url == nullptr ? "" : playlist_url, jwt == nullptr ? "" : jwt,
        out_path == nullptr ? "" : out_path, ready_timeout_seconds, progress != nullptr});

    const std::string error = self.download_error;
    const std::uint32_t steps = self.progress_steps;
    self.spawn([completion, userdata, error, steps, progress] {
      for (std::uint32_t done = 1; done <= steps; ++done) {
        if (progress != nullptr) {
          progress(done, steps, userdata);
        }
      }
      if (completion == nullptr) {
        return;
      }
      if (error.empty()) {
        completion(1, R"({"path":"out.mp4","bytes":1024,"segments":3})", nullptr, userdata);
      } else {
        completion(0, nullptr, error.c_str(), userdata);
      }
    });
  }

  static FakeRecorder* current_instance;

  int handle_marker_ = 0;
  ReactorCallbacks callbacks_{};
  std::mutex mutex_;
  std::vector<std::thread> threads_;
};

FakeRecorder* FakeRecorder::current_instance = nullptr;

struct Connected {
  Connected() : override_(&table_) { client.connect().get(); }

  FakeRecorder session;
  reactor::detail::Ffi table_ = session.table();
  reactor::detail::FfiOverride override_;
  reactor::Reactor client{"reactor/helios", reactor::Jwt{"a-token"}};
};

}  // namespace

TEST_CASE("a clip request carries its duration and comes back described") {
  Connected fixture;

  const auto clip = fixture.client.request_clip(10.0).get();

  REQUIRE(fixture.session.clip_requests.size() == 1);
  CHECK(fixture.session.clip_requests.front().duration_seconds == 10.0);

  CHECK(clip.playlist_url() == "https://api.reactor.inc/clips/abc.m3u8");
  CHECK(clip.session_id() == "sess_1");
  CHECK(clip.kind() == "snap");
  CHECK(clip.start_marker() == 10.0);
  CHECK(clip.end_marker() == 20.0);
  CHECK(clip.predicted_ready_at_ms() == 1'700'000'000'000.0);
}

TEST_CASE("a full-session recording takes no duration") {
  Connected fixture;
  const auto clip = fixture.client.request_recording().get();

  CHECK(fixture.session.recording_requests == 1);
  CHECK(fixture.session.clip_requests.empty());
  CHECK_FALSE(clip.playlist_url().empty());
}

TEST_CASE("downloading passes the playlist, the token and the path down") {
  Connected fixture;
  const auto clip = fixture.client.request_clip(5.0).get();

  clip.download("clip.mp4").get();

  REQUIRE(fixture.session.downloads.size() == 1);
  const auto& download = fixture.session.downloads.front();
  CHECK(download.playlist_url == "https://api.reactor.inc/clips/abc.m3u8");
  CHECK(download.jwt == "a-token");
  CHECK(download.out_path == "clip.mp4");
  // The handle goes too: it is what lets the downloader stop asking once the
  // session can no longer produce the clip.
  CHECK(download.had_handle);
  CHECK_FALSE(download.had_progress);
}

// A clip becomes ready *because* the model keeps generating. Bounding the wait on
// a number would give up on a model generating slower than real time, and waiting
// forever would hang on a session that has gone.
TEST_CASE("the default wait is unbounded in seconds, and bounded by the session") {
  Connected fixture;
  const auto clip = fixture.client.request_clip(5.0).get();

  clip.download("clip.mp4").get();
  REQUIRE(fixture.session.downloads.size() == 1);
  CHECK(fixture.session.downloads.front().ready_timeout_seconds < 0.0);

  reactor::Clip::DownloadOptions bounded;
  bounded.ready_timeout_seconds = 30.0;
  clip.download("clip2.mp4", bounded).get();
  REQUIRE(fixture.session.downloads.size() == 2);
  CHECK(fixture.session.downloads.back().ready_timeout_seconds == 30.0);
}

TEST_CASE("progress is reported when a handler asks for it") {
  Connected fixture;
  fixture.session.progress_steps = 3;
  const auto clip = fixture.client.request_clip(5.0).get();

  std::vector<std::pair<std::uint32_t, std::uint32_t>> seen;
  std::mutex mutex;

  reactor::Clip::DownloadOptions options;
  options.on_progress = [&](std::uint32_t done, std::uint32_t total) {
    const std::lock_guard<std::mutex> lock(mutex);
    seen.emplace_back(done, total);
  };
  clip.download("clip.mp4", options).get();

  CHECK(fixture.session.downloads.front().had_progress);
  const std::lock_guard<std::mutex> lock(mutex);
  REQUIRE(seen.size() == 3);
  CHECK(seen.front() == std::make_pair(std::uint32_t{1}, std::uint32_t{3}));
  CHECK(seen.back() == std::make_pair(std::uint32_t{3}, std::uint32_t{3}));
}

TEST_CASE("no progress handler means no progress callback is installed") {
  Connected fixture;
  fixture.session.progress_steps = 2;
  const auto clip = fixture.client.request_clip(5.0).get();

  clip.download("clip.mp4").get();
  CHECK_FALSE(fixture.session.downloads.front().had_progress);
}

TEST_CASE("a failed download reports what went wrong") {
  Connected fixture;
  const auto clip = fixture.client.request_clip(5.0).get();
  fixture.session.download_error =
      R"({"code":"NOT_FOUND","message":"the playlist has expired","status":404})";

  try {
    clip.download("clip.mp4").get();
    FAIL("a failed download must throw");
  } catch (const reactor::NotFoundError& error) {
    CHECK(error.status() == 404);
    CHECK(std::string{error.what()}.find("expired") != std::string::npos);
  }
}

// ── Refusals ─────────────────────────────────────────────────────────────────

TEST_CASE("a clip with no positive duration is refused") {
  Connected fixture;
  CHECK_THROWS_AS(fixture.client.request_clip(0.0).get(), reactor::BadRequestError);
  CHECK_THROWS_AS(fixture.client.request_clip(-5.0).get(), reactor::BadRequestError);
  CHECK(fixture.session.clip_requests.empty());
}

TEST_CASE("requesting a clip before there is a session is refused") {
  FakeRecorder session;
  const auto table = session.table();
  const reactor::detail::FfiOverride override{&table};
  reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};

  CHECK_THROWS_AS(client.request_clip(5.0).get(), reactor::InvalidStateError);
  CHECK(session.clip_requests.empty());
}

TEST_CASE("requesting a clip while the session is not ready is refused") {
  Connected fixture;
  fixture.session.current_status = "waiting";
  CHECK_THROWS_AS(fixture.client.request_clip(5.0).get(), reactor::InvalidStateError);
}

TEST_CASE("a download with nowhere to write is refused before anything is attempted") {
  Connected fixture;
  const auto clip = fixture.client.request_clip(5.0).get();

  CHECK_THROWS_AS(clip.download("").get(), reactor::BadRequestError);
  CHECK(fixture.session.downloads.empty());
}

// The playlist would still be fetchable, but the wait for readiness is bounded on
// the session being alive — and there is no session left to ask about. Saying so
// beats a download that waits on something nobody can answer for.
TEST_CASE("downloading after the client is gone is refused, with what to do instead") {
  FakeRecorder session;
  const auto table = session.table();
  const reactor::detail::FfiOverride override{&table};

  std::optional<reactor::Clip> orphan;
  {
    reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};
    client.connect().get();
    orphan = client.request_clip(5.0).get();
  }

  // The description survives: it is plain data.
  CHECK(orphan->session_id() == "sess_1");

  try {
    orphan->download("clip.mp4").get();
    FAIL("downloading without a client must be refused");
  } catch (const reactor::InvalidStateError& error) {
    const std::string message = error.what();
    CHECK(message.find("destroyed") != std::string::npos);
    CHECK(message.find("before disconnecting") != std::string::npos);
  }
  CHECK(session.downloads.empty());
}

// An accepted request that names no playlist is unusable: there is nothing to
// download. Better a decode failure here than an empty Clip that fails later.
TEST_CASE("an accepted request that names no playlist is a decode failure") {
  Connected fixture;
  fixture.session.clip_result = R"({"session_id":"sess_1","kind":"snap"})";

  CHECK_THROWS_AS(fixture.client.request_clip(5.0).get(), reactor::DecodeError);
}

TEST_CASE("a rejected clip request throws the platform's own code") {
  Connected fixture;
  fixture.session.clip_error =
      R"({"code":"RECORDING_DISABLED","message":"this model does not record"})";

  try {
    fixture.client.request_clip(5.0).get();
    FAIL("a rejected clip request must throw");
  } catch (const reactor::ReactorError& error) {
    CHECK(error.code() == "RECORDING_DISABLED");
  }
}
