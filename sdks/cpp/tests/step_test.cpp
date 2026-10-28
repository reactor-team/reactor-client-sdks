// Stepped generation, against a fake library.
//
// The case worth reading first is the result's ownership: `reactor_step` hands
// the SDK a native result it must free exactly once, on every path — copied out,
// refused as undecodable, or arriving for a call teardown already settled — and
// what the caller gets must own its bytes, readable after the client is gone.

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <future>
#include <limits>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <type_traits>
#include <utility>
#include <vector>

#include <catch2/catch_test_macros.hpp>

#include "detail/ffi.hpp"
#include "reactor/reactor.hpp"
#include "reactor/version.hpp"

namespace {

using namespace std::chrono_literals;

/// What the fake hands over as a `ReactorStepResult*`.
struct FakeFrame {
  std::string encoding = "raw";
  std::string dtype = "uint8";
  std::vector<std::uint32_t> shape;
  std::vector<std::uint8_t> data;
  std::optional<std::vector<std::uint8_t>> metadata;
  std::optional<std::int64_t> capture_time_us;
};

struct FakeTrack {
  std::string name;
  std::string kind = "video";
  std::vector<FakeFrame> frames;
  double rate = 0.0;
};

struct FakeFile {
  std::string name;
  std::string content_type;
  std::uint64_t size = 0;
};

struct FakeResult {
  std::uint64_t step = 1;
  std::vector<FakeTrack> tracks;
  double elapsed = 0.0;
  std::vector<FakeFile> files{};
  std::optional<std::string> error{};
};

/// A stand-in for libreactor_ffi's stepped surface. Answers on its own thread.
class FakeStepped {
 public:
  FakeStepped() { current_instance = this; }

  ~FakeStepped() {
    join_all();
    current_instance = nullptr;
  }

  FakeStepped(const FakeStepped&) = delete;
  FakeStepped& operator=(const FakeStepped&) = delete;
  FakeStepped(FakeStepped&&) = delete;
  FakeStepped& operator=(FakeStepped&&) = delete;

  static FakeStepped& current() { return *current_instance; }

  reactor::detail::Ffi table() {
    reactor::detail::Ffi filled;
    filled.abi_version = &abi_version;
    filled.create_with_adm = &create_with_adm;
    filled.create_with_options = &create_with_options;
    filled.destroy = &destroy;
    filled.connect = &connect;
    filled.status = &status;
    filled.free_string = &free_string;
    filled.step = &step;
    filled.clear_step_inputs = &clear_step_inputs;
    filled.step_result_step = &result_step;
    filled.step_result_elapsed = &result_elapsed;
    filled.step_result_track_count = &track_count;
    filled.step_result_track_name = &track_name;
    filled.step_result_track_kind = &track_kind;
    filled.step_result_track_rate = &track_rate;
    filled.step_result_frame_count = &frame_count;
    filled.step_result_frame_encoding = &frame_encoding;
    filled.step_result_frame_dtype = &frame_dtype;
    filled.step_result_frame_shape = &frame_shape;
    filled.step_result_frame_data = &frame_data;
    filled.step_result_frame_metadata = &frame_metadata;
    filled.step_result_frame_capture_time_us = &frame_capture_time;
    filled.step_result_error = &result_error;
    filled.step_result_file_count = &file_count;
    filled.step_result_file_name = &file_name;
    filled.step_result_file_content_type = &file_content_type;
    filled.step_result_file_size = &file_size;
    filled.step_result_free = &result_free;
    return filled;
  }

  // ── What the SDK asked for ────────────────────────────────────────────────

  int adm_creates = 0;
  int options_creates = 0;
  std::string options_json;
  std::string created_with_jwt;
  int steps = 0;
  std::optional<std::string> state_json;
  std::optional<std::string> commands_json;
  int clears = 0;
  std::atomic<int> results_freed{0};
  std::atomic<int> strings_freed{0};

  // ── How it answers ────────────────────────────────────────────────────────

  /// Non-empty: create_with_options refuses with this error object.
  std::string create_error;
  std::string current_status = "ready";
  /// Whether a step answers at all; false leaves it to teardown.
  bool answer_step = true;
  /// Non-empty: the step fails with this error object.
  std::string step_error;
  /// Answer success with a null result.
  bool null_result = false;
  /// Keep the step's completion, unanswered, for the test to fire itself.
  bool hold_step = false;
  reactor_step_completion_fn held_completion = nullptr;
  void* held_userdata = nullptr;
  /// What destroy reports: 0 quiesced, non-zero a callback it could not wait for.
  int destroy_result = 0;
  FakeResult result;

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

  static ReactorHandle* handle() {
    return reinterpret_cast<ReactorHandle*>(&current().handle_marker_);
  }

  static const FakeResult* as_fake(const ReactorStepResult* result) {
    return reinterpret_cast<const FakeResult*>(result);
  }

  static const FakeTrack* track_at(const ReactorStepResult* result, std::uint32_t track) {
    if (result == nullptr || track >= as_fake(result)->tracks.size()) {
      return nullptr;
    }
    return &as_fake(result)->tracks[track];
  }

  static const FakeFrame* frame_at(const ReactorStepResult* result, std::uint32_t track,
                                   std::uint32_t frame) {
    const FakeTrack* found = track_at(result, track);
    if (found == nullptr || frame >= found->frames.size()) {
      return nullptr;
    }
    return &found->frames[frame];
  }

  // ── The C surface ─────────────────────────────────────────────────────────

  static std::uint32_t abi_version() { return REACTOR_ABI_VERSION; }

  static ReactorHandle* create_with_adm(const char* /*api_url*/, const char* /*model*/,
                                        const char* /*jwt*/, int /*local*/,
                                        int /*auto_resume_tracks*/,
                                        const ReactorCallbacks* callbacks, int /*adm_mode*/,
                                        const char* /*sdk_version*/, const char* /*sdk_type*/,
                                        const char* /*extra_headers_json*/) {
    auto& self = current();
    ++self.adm_creates;
    if (callbacks != nullptr) {
      self.callbacks_ = *callbacks;
    }
    return handle();
  }

  static ReactorHandle* create_with_options(const char* /*api_url*/, const char* /*model*/,
                                            const char* jwt, const ReactorCallbacks* callbacks,
                                            const char* options_json, char** error_out) {
    auto& self = current();
    ++self.options_creates;
    self.options_json = options_json == nullptr ? "" : options_json;
    self.created_with_jwt = jwt == nullptr ? "" : jwt;
    if (!self.create_error.empty()) {
      if (error_out != nullptr) {
        *error_out = heap_copy(self.create_error.c_str());
      }
      return nullptr;
    }
    if (callbacks != nullptr) {
      self.callbacks_ = *callbacks;
    }
    return handle();
  }

  static int destroy(ReactorHandle* destroyed) {
    REQUIRE(destroyed == handle());
    current().join_all();
    return current().destroy_result;
  }

  static void connect(ReactorHandle* /*handle*/, const char* /*session_id*/,
                      const std::uint32_t* /*connection_id*/, reactor_completion_fn completion,
                      void* userdata) {
    current().spawn([completion, userdata] { completion(1, "{}", nullptr, userdata); });
  }

  static const char* status(ReactorHandle* handle) {
    return handle == nullptr ? "disconnected" : current().current_status.c_str();
  }

  static void free_string(char* s) {
    ++current().strings_freed;
    std::free(s);  // NOLINT(cppcoreguidelines-no-malloc)
  }

  static void step(ReactorHandle* /*handle*/, const char* state_json, const char* commands_json,
                   reactor_step_completion_fn completion, void* userdata) {
    auto& self = current();
    ++self.steps;
    self.state_json = state_json == nullptr ? std::nullopt : std::optional<std::string>{state_json};
    self.commands_json =
        commands_json == nullptr ? std::nullopt : std::optional<std::string>{commands_json};
    if (!self.answer_step) {
      return;
    }
    if (self.hold_step) {
      self.held_completion = completion;
      self.held_userdata = userdata;
      return;
    }
    const std::string error = self.step_error;
    const bool null_result = self.null_result;
    auto* result = new FakeResult{self.result};  // NOLINT(cppcoreguidelines-owning-memory)
    self.spawn([completion, userdata, error, null_result, result] {
      if (!error.empty()) {
        delete result;  // NOLINT(cppcoreguidelines-owning-memory)
        completion(0, nullptr, error.c_str(), userdata);
      } else if (null_result) {
        delete result;  // NOLINT(cppcoreguidelines-owning-memory)
        completion(1, nullptr, nullptr, userdata);
      } else {
        completion(1, reinterpret_cast<ReactorStepResult*>(result), nullptr, userdata);
      }
    });
  }

  static void clear_step_inputs(ReactorHandle* /*handle*/) { ++current().clears; }

  static std::uint64_t result_step(const ReactorStepResult* result) {
    return result == nullptr ? 0 : as_fake(result)->step;
  }

  static double result_elapsed(const ReactorStepResult* result) {
    return result == nullptr ? 0.0 : as_fake(result)->elapsed;
  }

  static const char* result_error(const ReactorStepResult* result) {
    if (result == nullptr) {
      return nullptr;
    }
    const auto& error = as_fake(result)->error;
    return error ? error->c_str() : nullptr;
  }

  static const FakeFile* file_at(const ReactorStepResult* result, std::uint32_t file) {
    if (result == nullptr || file >= as_fake(result)->files.size()) {
      return nullptr;
    }
    return &as_fake(result)->files[file];
  }

  static std::uint32_t file_count(const ReactorStepResult* result) {
    return result == nullptr ? 0 : static_cast<std::uint32_t>(as_fake(result)->files.size());
  }

  static const char* file_name(const ReactorStepResult* result, std::uint32_t file) {
    const FakeFile* found = file_at(result, file);
    return found == nullptr ? nullptr : found->name.c_str();
  }

  static const char* file_content_type(const ReactorStepResult* result, std::uint32_t file) {
    const FakeFile* found = file_at(result, file);
    return found == nullptr ? nullptr : found->content_type.c_str();
  }

  static std::uint64_t file_size(const ReactorStepResult* result, std::uint32_t file) {
    const FakeFile* found = file_at(result, file);
    return found == nullptr ? 0 : found->size;
  }

  static std::uint32_t track_count(const ReactorStepResult* result) {
    return result == nullptr ? 0 : static_cast<std::uint32_t>(as_fake(result)->tracks.size());
  }

  static const char* track_name(const ReactorStepResult* result, std::uint32_t track) {
    const FakeTrack* found = track_at(result, track);
    return found == nullptr ? nullptr : found->name.c_str();
  }

  static const char* track_kind(const ReactorStepResult* result, std::uint32_t track) {
    const FakeTrack* found = track_at(result, track);
    return found == nullptr ? nullptr : found->kind.c_str();
  }

  static double track_rate(const ReactorStepResult* result, std::uint32_t track) {
    const FakeTrack* found = track_at(result, track);
    return found == nullptr ? 0.0 : found->rate;
  }

  static std::uint32_t frame_count(const ReactorStepResult* result, std::uint32_t track) {
    const FakeTrack* found = track_at(result, track);
    return found == nullptr ? 0 : static_cast<std::uint32_t>(found->frames.size());
  }

  static const char* frame_encoding(const ReactorStepResult* result, std::uint32_t track,
                                    std::uint32_t frame) {
    const FakeFrame* found = frame_at(result, track, frame);
    return found == nullptr ? nullptr : found->encoding.c_str();
  }

  static const char* frame_dtype(const ReactorStepResult* result, std::uint32_t track,
                                 std::uint32_t frame) {
    const FakeFrame* found = frame_at(result, track, frame);
    return found == nullptr ? nullptr : found->dtype.c_str();
  }

  static const std::uint32_t* frame_shape(const ReactorStepResult* result, std::uint32_t track,
                                          std::uint32_t frame, std::uint32_t* ndim) {
    const FakeFrame* found = frame_at(result, track, frame);
    *ndim = found == nullptr ? 0 : static_cast<std::uint32_t>(found->shape.size());
    return found == nullptr ? nullptr : found->shape.data();
  }

  static const std::uint8_t* frame_data(const ReactorStepResult* result, std::uint32_t track,
                                        std::uint32_t frame, std::size_t* len) {
    const FakeFrame* found = frame_at(result, track, frame);
    *len = found == nullptr ? 0 : found->data.size();
    return found == nullptr ? nullptr : found->data.data();
  }

  static const std::uint8_t* frame_metadata(const ReactorStepResult* result, std::uint32_t track,
                                            std::uint32_t frame, std::size_t* len) {
    const FakeFrame* found = frame_at(result, track, frame);
    *len = 0;
    if (found == nullptr || !found->metadata) {
      return nullptr;
    }
    *len = found->metadata->size();
    // Non-null even when empty: an attached, empty tag.
    static const std::uint8_t empty = 0;
    return found->metadata->empty() ? &empty : found->metadata->data();
  }

  static int frame_capture_time(const ReactorStepResult* result, std::uint32_t track,
                                std::uint32_t frame, std::int64_t* capture_time_us) {
    const FakeFrame* found = frame_at(result, track, frame);
    if (found == nullptr || !found->capture_time_us) {
      return 0;
    }
    *capture_time_us = *found->capture_time_us;
    return 1;
  }

  static void result_free(ReactorStepResult* result) {
    if (result == nullptr) {
      return;
    }
    ++current().results_freed;
    delete reinterpret_cast<FakeResult*>(result);  // NOLINT(cppcoreguidelines-owning-memory)
  }

  static char* heap_copy(const char* text) {
    // NOLINTNEXTLINE(cppcoreguidelines-no-malloc)
    char* copy = static_cast<char*>(std::malloc(std::strlen(text) + 1));
    std::strcpy(copy, text);  // NOLINT(clang-analyzer-security.insecureAPI.strcpy)
    return copy;
  }

  static FakeStepped* current_instance;

  int handle_marker_ = 0;
  ReactorCallbacks callbacks_{};
  std::mutex mutex_;
  std::vector<std::thread> threads_;
};

FakeStepped* FakeStepped::current_instance = nullptr;

struct Fixture {
  Fixture() : override_(&table_) {}

  FakeStepped library;
  reactor::detail::Ffi table_ = library.table();
  reactor::detail::FfiOverride override_;
};

using Metadata = std::optional<std::vector<std::uint8_t>>;

reactor::Options stepped() {
  reactor::Options options;
  options.generation = reactor::Generation::Stepped;
  return options;
}

template <typename Predicate>
bool eventually(Predicate predicate, std::chrono::milliseconds limit = 2000ms) {
  const auto deadline = std::chrono::steady_clock::now() + limit;
  while (std::chrono::steady_clock::now() < deadline) {
    if (predicate()) {
      return true;
    }
    std::this_thread::sleep_for(1ms);
  }
  return predicate();
}

FakeFrame video_frame(std::uint32_t height, std::uint32_t width) {
  FakeFrame frame;
  frame.dtype = "uint8";
  frame.shape = {height, width, 3};
  frame.data.resize(static_cast<std::size_t>(height) * width * 3U);
  for (std::size_t i = 0; i < frame.data.size(); ++i) {
    frame.data[i] = static_cast<std::uint8_t>(i & 0xFFU);
  }
  return frame;
}

}  // namespace

// ── Creating the client ──────────────────────────────────────────────────────

TEST_CASE("a stepped client is created with its options, never through create_with_adm") {
  Fixture fixture;
  auto options = stepped();
  options.step_timeout_seconds = 12.5;
  options.auto_resume_tracks = false;

  {
    reactor::Reactor client{"owner/model", reactor::Jwt{"a-token"}, options};
    CHECK(client.generation() == reactor::Generation::Stepped);
    client.connect().get();
  }

  CHECK(fixture.library.options_creates == 1);
  CHECK(fixture.library.adm_creates == 0);
  CHECK(fixture.library.created_with_jwt == "a-token");
  const auto sent = reactor::Json::parse(fixture.library.options_json);
  CHECK(sent == reactor::Json{{"local", false},
                              {"auto_resume_tracks", false},
                              {"adm", "synthetic"},
                              {"sdk_version", REACTOR_SDK_VERSION},
                              {"sdk_type", "cpp"},
                              {"generation", "stepped"},
                              {"step_timeout_seconds", 12.5}});
}

TEST_CASE("a stepped client sends extra_headers in its create options") {
  Fixture fixture;
  auto options = stepped();
  options.extra_headers = {{"X-Client-Id", "my-app"}};

  {
    reactor::Reactor client{"owner/model", reactor::Jwt{"a-token"}, options};
    client.connect().get();
  }

  const auto sent = reactor::Json::parse(fixture.library.options_json);
  CHECK(sent["extra_headers"] == reactor::Json{{"X-Client-Id", "my-app"}});
}

TEST_CASE("a streamed client still creates through create_with_adm") {
  Fixture fixture;
  {
    reactor::Reactor client{"owner/model", reactor::Jwt{"token"}};
    CHECK(client.generation() == reactor::Generation::Streamed);
    client.connect().get();
  }
  CHECK(fixture.library.adm_creates == 1);
  CHECK(fixture.library.options_creates == 0);
}

TEST_CASE("options the library refuses fail connect with the typed error, freed once") {
  Fixture fixture;
  fixture.library.create_error =
      R"({"code":"INVALID_STATE","message":"unknown option","operation":"create"})";

  reactor::Reactor client{"owner/model", reactor::Jwt{"token"}, stepped()};
  CHECK_THROWS_AS(client.connect().get(), reactor::InvalidStateError);
  CHECK(fixture.library.strings_freed == 1);
}

TEST_CASE("a step timeout that could not mean anything is refused at construction") {
  Fixture fixture;
  reactor::Options options;

  SECTION("on a streamed client") { options.step_timeout_seconds = 10.0; }
  SECTION("zero") {
    options = stepped();
    options.step_timeout_seconds = 0.0;
  }
  SECTION("negative") {
    options = stepped();
    options.step_timeout_seconds = -1.0;
  }
  SECTION("NaN") {
    options = stepped();
    options.step_timeout_seconds = std::nan("");
  }
  SECTION("infinity") {
    options = stepped();
    options.step_timeout_seconds = std::numeric_limits<double>::infinity();
  }

  CHECK_THROWS_AS((reactor::Reactor{"owner/model", reactor::Jwt{"token"}, options}),
                  reactor::BadRequestError);
}

TEST_CASE("generation spells itself the way the wire does") {
  CHECK(reactor::to_string(reactor::Generation::Streamed) == "streamed");
  CHECK(reactor::to_string(reactor::Generation::Stepped) == "stepped");
}

// ── Running a step ───────────────────────────────────────────────────────────

TEST_CASE("step passes the state and the commands through as JSON") {
  Fixture fixture;
  reactor::Reactor client{"owner/model", reactor::Jwt{"token"}, stepped()};
  client.connect().get();

  SECTION("state and commands") {
    const auto result =
        client.step({{"prompt", "a red fox"}}, {{"advance", {{"frames", 4}}}, {"reset"}}).get();
    CHECK(result.step == 1);
    CHECK(reactor::Json::parse(fixture.library.state_json.value_or("null")) ==
          reactor::Json{{"prompt", "a red fox"}});
    CHECK(reactor::Json::parse(fixture.library.commands_json.value_or("null")) ==
          reactor::Json::parse(
              R"([{"command":"advance","data":{"frames":4}},{"command":"reset","data":null}])"));
  }

  SECTION("nothing at all") {
    const auto result = client.step().get();
    CHECK(result.step == 1);
    CHECK_FALSE(fixture.library.state_json.has_value());
    CHECK(fixture.library.commands_json == std::optional<std::string>{"[]"});
  }

  SECTION("a FileRef in the state goes as its upload reference") {
    const reactor::FileRef ref{"up-1", "photo.png", "image/png", 3};
    const auto result = client.step({{"image", ref}}).get();
    CHECK(result.step == 1);
    CHECK(reactor::Json::parse(fixture.library.state_json.value_or("null"))["image"]["upload_id"] ==
          "up-1");
  }
}

TEST_CASE("a malformed step is refused before anything is sent") {
  Fixture fixture;
  reactor::Reactor client{"owner/model", reactor::Jwt{"token"}, stepped()};
  client.connect().get();

  std::future<reactor::StepResult> future;
  SECTION("state that is not an object") { future = client.step(reactor::Json::array({1, 2})); }
  SECTION("a command with no name") { future = client.step(nullptr, {{""}}); }
  SECTION("command data that is not an object") {
    future = client.step(nullptr, {{"advance", "four"}});
  }

  CHECK_THROWS_AS(future.get(), reactor::BadRequestError);
  CHECK(fixture.library.steps == 0);
}

TEST_CASE("step on a streamed client says how to make a stepped one") {
  Fixture fixture;
  reactor::Reactor client{"owner/model", reactor::Jwt{"token"}};
  client.connect().get();

  try {
    (void)client.step().get();
    FAIL("a streamed client must refuse step()");
  } catch (const reactor::InvalidStateError& error) {
    CHECK(std::string{error.what()}.find("Generation::Stepped") != std::string::npos);
  }
  CHECK(fixture.library.steps == 0);
}

TEST_CASE("step needs a ready session") {
  Fixture fixture;
  reactor::Reactor client{"owner/model", reactor::Jwt{"token"}, stepped()};

  SECTION("before connect") {}
  SECTION("while not ready") {
    client.connect().get();
    fixture.library.current_status = "waiting";
  }

  CHECK_THROWS_AS(client.step().get(), reactor::InvalidStateError);
  CHECK(fixture.library.steps == 0);
}

// ── The result ───────────────────────────────────────────────────────────────

TEST_CASE("a step's result is copied out once, owns its bytes, and the native one is freed") {
  Fixture fixture;
  FakeTrack video{"main_video", "video", {video_frame(2, 4), video_frame(2, 4)}, 24.0};
  video.frames[0].metadata = std::vector<std::uint8_t>{'t', 'a', 'g'};
  video.frames[0].capture_time_us = 1234;
  video.frames[1].metadata = std::vector<std::uint8_t>{};
  FakeFrame audio;
  audio.dtype = "int16";
  audio.shape = {2, 3};
  audio.data = std::vector<std::uint8_t>(12, 7);
  fixture.library.result =
      FakeResult{7, {video, FakeTrack{"main_audio", "audio", {audio}, 48000.0}}, 0.25};

  reactor::StepResult result;
  {
    reactor::Reactor client{"owner/model", reactor::Jwt{"token"}, stepped()};
    client.connect().get();
    result = client.step().get();
  }
  // The client and its handle are gone; the result is the caller's.
  CHECK(fixture.library.results_freed == 1);

  CHECK(result.step == 7);
  CHECK(result.elapsed == 0.25);
  REQUIRE(result.tracks.size() == 2);
  CHECK(result.tracks[0].name == "main_video");
  CHECK(result.tracks[0].kind == reactor::TrackKind::Video);
  CHECK(result.tracks[1].kind == reactor::TrackKind::Audio);
  CHECK(result.tracks[0].rate == 24.0);
  CHECK(result.tracks[1].rate == 48000.0);

  const auto& first = result.track("main_video").frames.at(0);
  CHECK(first.encoding == "raw");
  CHECK(first.dtype == "uint8");
  CHECK(first.shape == std::vector<std::uint32_t>{2, 4, 3});
  CHECK(first.data == video.frames[0].data);
  CHECK(first.metadata == Metadata{std::vector<std::uint8_t>{'t', 'a', 'g'}});
  CHECK(first.capture_time_us == std::optional<std::int64_t>{1234});

  const auto& second = result.track("main_video").frames.at(1);
  // Attached and empty is not the same as none.
  CHECK(second.metadata == Metadata{std::vector<std::uint8_t>{}});
  CHECK_FALSE(second.capture_time_us.has_value());

  const auto& samples = result.track("main_audio").frames.at(0);
  CHECK(samples.dtype == "int16");
  CHECK(samples.shape == std::vector<std::uint32_t>{2, 3});
  CHECK(samples.data.size() == 12);
  CHECK_FALSE(samples.metadata.has_value());
}

TEST_CASE("a step's files and error are copied out with it") {
  Fixture fixture;
  fixture.library.result.step = 4;
  fixture.library.result.files = {FakeFile{"depth.exr", "image/x-exr", 1048576},
                                  FakeFile{"log.txt", "text/plain", 0}};

  reactor::Reactor client{"owner/model", reactor::Jwt{"token"}, stepped()};
  client.connect().get();
  const auto result = client.step().get();

  REQUIRE(result.files.size() == 2);
  CHECK(result.files[0].name == "depth.exr");
  CHECK(result.files[0].content_type == "image/x-exr");
  CHECK(result.files[0].size == 1048576);
  CHECK(result.files[1].name == "log.txt");
  CHECK(result.files[1].content_type == "text/plain");
  CHECK(result.files[1].size == 0);
  CHECK_FALSE(result.error.has_value());
  CHECK(eventually([&] { return fixture.library.results_freed == 1; }));
}

TEST_CASE("a step that ran but that the model reported failing resolves, with its error") {
  Fixture fixture;
  fixture.library.result.step = 9;
  fixture.library.result.error = "out of memory";

  reactor::Reactor client{"owner/model", reactor::Jwt{"token"}, stepped()};
  client.connect().get();
  const auto result = client.step().get();

  CHECK(result.step == 9);
  CHECK(result.error == std::optional<std::string>{"out of memory"});
  CHECK(result.files.empty());
  CHECK(result.tracks.empty());
  CHECK(eventually([&] { return fixture.library.results_freed == 1; }));
}

TEST_CASE("asking a result for a track it has no output on names the ones it has") {
  reactor::StepResult result;
  result.step = 3;
  result.tracks.push_back(
      reactor::StepTrackOutput{"main_video", reactor::TrackKind::Video, 24.0, {}});

  try {
    (void)result.track("main_vdieo");
    FAIL("an unknown track must throw");
  } catch (const reactor::NotFoundError& error) {
    const std::string message = error.what();
    CHECK(message.find("\"main_vdieo\"") != std::string::npos);
    CHECK(message.find("\"main_video\"") != std::string::npos);
  }
}

TEST_CASE("a result that will not decode fails the step, and is still freed") {
  Fixture fixture;

  SECTION("bytes that disagree with the shape") {
    auto frame = video_frame(2, 2);
    frame.data.pop_back();
    fixture.library.result = FakeResult{1, {FakeTrack{"main_video", "video", {frame}}}};
  }
  SECTION("a kind this build does not know") {
    fixture.library.result = FakeResult{1, {FakeTrack{"main_video", "hologram", {}}}};
  }

  reactor::Reactor client{"owner/model", reactor::Jwt{"token"}, stepped()};
  client.connect().get();
  CHECK_THROWS_AS(client.step().get(), reactor::DecodeError);
  CHECK(eventually([&] { return fixture.library.results_freed == 1; }));
}

TEST_CASE("a success with no result is a decode failure, not an empty step") {
  Fixture fixture;
  fixture.library.null_result = true;

  reactor::Reactor client{"owner/model", reactor::Jwt{"token"}, stepped()};
  client.connect().get();
  CHECK_THROWS_AS(client.step().get(), reactor::DecodeError);
}

TEST_CASE("a failed step throws the typed error for its code") {
  Fixture fixture;
  reactor::Reactor client{"owner/model", reactor::Jwt{"token"}, stepped()};
  client.connect().get();

  SECTION("refused") {
    fixture.library.step_error = R"({"code":"step_refused","message":"busy","operation":"step"})";
    CHECK_THROWS_AS(client.step().get(), reactor::StepRefusedError);
  }
  SECTION("not ready") {
    fixture.library.step_error = R"({"code":"step_not_ready","message":"starting"})";
    CHECK_THROWS_AS(client.step().get(), reactor::StepNotReadyError);
  }
  SECTION("result too large") {
    fixture.library.step_error = R"({"code":"step_result_too_large","message":"too big"})";
    CHECK_THROWS_AS(client.step().get(), reactor::StepResultTooLargeError);
  }
  SECTION("a command failed") {
    fixture.library.step_error = R"({"code":"step_command_failed","message":"set_prompt failed"})";
    CHECK_THROWS_AS(client.step().get(), reactor::StepCommandFailedError);
  }
  SECTION("the session ended") {
    fixture.library.step_error = R"({"code":"step_session_ended","message":"stopped"})";
    CHECK_THROWS_AS(client.step().get(), reactor::StepSessionEndedError);
  }
  SECTION("request too large") {
    fixture.library.step_error = R"({"code":"MESSAGE_TOO_LARGE","message":"too big"})";
    CHECK_THROWS_AS(client.step().get(), reactor::MessageTooLargeError);
  }
  SECTION("an undecodable output frame") {
    fixture.library.step_error = R"({"code":"UNSUPPORTED_FRAME_ENCODING","message":"jpeg"})";
    CHECK_THROWS_AS(client.step().get(), reactor::UnsupportedFrameEncodingError);
  }
  CHECK(fixture.library.results_freed == 0);
}

TEST_CASE("destroying a client settles a step still in flight") {
  Fixture fixture;
  fixture.library.answer_step = false;

  std::future<reactor::StepResult> future;
  {
    reactor::Reactor client{"owner/model", reactor::Jwt{"token"}, stepped()};
    client.connect().get();
    future = client.step();
    CHECK(future.wait_for(50ms) == std::future_status::timeout);
  }

  REQUIRE(future.wait_for(2000ms) == std::future_status::ready);
  try {
    (void)future.get();
    FAIL("a step that never completed must not report success");
  } catch (const reactor::AbortedError& error) {
    CHECK(error.operation() == "step");
  }
}

TEST_CASE("a step result that arrives after teardown is freed once, and the step stays aborted") {
  Fixture fixture;
  fixture.library.hold_step = true;
  // The library could not wait for a callback still on its way: the pending step
  // goes to the orphan store rather than being freed under it.
  fixture.library.destroy_result = -1;

  std::future<reactor::StepResult> future;
  {
    reactor::Reactor client{"owner/model", reactor::Jwt{"token"}, stepped()};
    client.connect().get();
    future = client.step();
  }
  REQUIRE(future.wait_for(2000ms) == std::future_status::ready);
  CHECK_THROWS_AS(future.get(), reactor::AbortedError);

  REQUIRE(fixture.library.held_completion != nullptr);
  auto* late = new FakeResult{fixture.library.result};  // NOLINT(cppcoreguidelines-owning-memory)
  fixture.library.held_completion(1, reinterpret_cast<ReactorStepResult*>(late), nullptr,
                                  fixture.library.held_userdata);
  CHECK(fixture.library.results_freed == 1);
}

TEST_CASE("a failed step that still carries a result frees it") {
  Fixture fixture;
  fixture.library.hold_step = true;
  reactor::Reactor client{"owner/model", reactor::Jwt{"token"}, stepped()};
  client.connect().get();
  auto future = client.step();

  REQUIRE(fixture.library.held_completion != nullptr);
  auto* stray = new FakeResult{fixture.library.result};  // NOLINT(cppcoreguidelines-owning-memory)
  const char* error = R"({"code":"step_refused","message":"busy","operation":"step"})";
  fixture.library.held_completion(0, reinterpret_cast<ReactorStepResult*>(stray), error,
                                  fixture.library.held_userdata);
  CHECK_THROWS_AS(future.get(), reactor::StepRefusedError);
  CHECK(fixture.library.results_freed == 1);
}

// ── Events and inputs ────────────────────────────────────────────────────────

// The runtime answers a step with its result alone: there is no separate
// "accepted" event to subscribe to.
template <typename T, typename = void>
struct HasOnStepAccepted : std::false_type {};
template <typename T>
struct HasOnStepAccepted<T, std::void_t<decltype(std::declval<T&>().on_step_accepted(
                                std::function<void(std::uint64_t)>{}))>> : std::true_type {};
static_assert(!HasOnStepAccepted<reactor::Reactor>::value,
              "a step is answered by its result alone; there is no accepted event");

TEST_CASE("clear_step_inputs reaches the library only for a stepped session") {
  Fixture fixture;

  SECTION("stepped, connected") {
    reactor::Reactor client{"owner/model", reactor::Jwt{"token"}, stepped()};
    client.clear_step_inputs();  // no handle yet: nothing to clear
    CHECK(fixture.library.clears == 0);
    client.connect().get();
    client.clear_step_inputs();
    CHECK(fixture.library.clears == 1);
  }
  SECTION("streamed") {
    reactor::Reactor client{"owner/model", reactor::Jwt{"token"}};
    client.connect().get();
    client.clear_step_inputs();
    CHECK(fixture.library.clears == 0);
  }
}
