// Commands, messages and uploads.
//
// The case worth reading first is the empty reply: a handler that ran and
// returned no message is a *success with no value*, and folding that into a
// failure is how a caller comes to treat a working setter as a broken one.

#include <atomic>
#include <chrono>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <vector>

#include <catch2/catch_test_macros.hpp>

#include "detail/ffi.hpp"
#include "reactor/reactor.hpp"

namespace {

using namespace std::chrono_literals;

/// A fake library that records commands and answers them.
class FakeCommands {
 public:
  FakeCommands() { current_instance = this; }

  ~FakeCommands() {
    join_all();
    current_instance = nullptr;
  }

  FakeCommands(const FakeCommands&) = delete;
  FakeCommands& operator=(const FakeCommands&) = delete;
  FakeCommands(FakeCommands&&) = delete;
  FakeCommands& operator=(FakeCommands&&) = delete;

  static FakeCommands& current() { return *current_instance; }

  reactor::detail::Ffi table() {
    reactor::detail::Ffi filled;
    filled.abi_version = &abi_version;
    filled.create_with_adm = &create_with_adm;
    filled.destroy = &destroy;
    filled.connect = &connect;
    filled.status = &status;
    filled.free_string = &free_string;
    filled.send_command = &send_command;
    filled.request_schema = &request_schema;
    filled.upload_file = &upload_file;
    filled.upload_bytes = &upload_bytes;
    return filled;
  }

  struct Command {
    std::string name;
    std::string args_json;
    std::string uploads_json;  // empty when the SDK sent none at all
    bool uploads_were_null = false;
  };
  std::vector<Command> commands;

  struct Upload {
    std::string path_or_name;
    std::string mime_type;
    std::size_t byte_count = 0;
  };
  std::vector<Upload> uploads;

  /// What the next command answers with. `result` empty means "no message".
  std::string command_result = R"({"type":"ack","data":{"ok":true}})";
  std::string command_error;
  std::string upload_result =
      R"({"upload_id":"up_123","name":"photo.jpg","mime_type":"image/jpeg","size":42})";
  std::string upload_error;
  std::string schema_result = R"({"openapi":"3.1.0","paths":{}})";
  std::string current_status = "ready";

  void push_message(const std::string& json) {
    if (callbacks_.on_message == nullptr) {
      return;
    }
    spawn([this, json] { callbacks_.on_message(json.c_str(), callbacks_.userdata); });
    join_all();
  }

  void push_runtime_message(const std::string& json) {
    if (callbacks_.on_runtime_message == nullptr) {
      return;
    }
    spawn([this, json] { callbacks_.on_runtime_message(json.c_str(), callbacks_.userdata); });
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
                                        const ReactorCallbacks* callbacks, int /*adm_mode*/,
                                        const char* /*sdk_version*/, const char* /*sdk_type*/) {
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

  static void send_command(ReactorHandle* /*handle*/, const char* name, const char* args_json,
                           const char* uploads_json, reactor_completion_fn completion,
                           void* userdata) {
    auto& self = current();
    self.commands.push_back(
        Command{name == nullptr ? "" : name, args_json == nullptr ? "" : args_json,
                uploads_json == nullptr ? "" : uploads_json, uploads_json == nullptr});
    if (completion == nullptr) {
      return;
    }
    const std::string result = self.command_result;
    const std::string error = self.command_error;
    self.spawn([completion, userdata, result, error] {
      if (!error.empty()) {
        completion(0, nullptr, error.c_str(), userdata);
      } else if (result.empty()) {
        // A handler that acknowledged and returned nothing: the FFI reports
        // success with no result at all.
        completion(1, nullptr, nullptr, userdata);
      } else {
        completion(1, result.c_str(), nullptr, userdata);
      }
    });
  }

  static void request_schema(ReactorHandle* /*handle*/, reactor_completion_fn completion,
                             void* userdata) {
    auto& self = current();
    if (completion == nullptr) {
      return;
    }
    const std::string result = self.schema_result;
    self.spawn(
        [completion, userdata, result] { completion(1, result.c_str(), nullptr, userdata); });
  }

  static void upload_file(ReactorHandle* /*handle*/, const char* path,
                          reactor_completion_fn completion, void* userdata) {
    auto& self = current();
    self.uploads.push_back(Upload{path == nullptr ? "" : path, "", 0});
    self.answer_upload(completion, userdata);
  }

  static void upload_bytes(ReactorHandle* /*handle*/, const std::uint8_t* data, std::size_t len,
                           const char* name, const char* mime_type,
                           reactor_completion_fn completion, void* userdata) {
    auto& self = current();
    REQUIRE(data != nullptr);
    self.uploads.push_back(
        Upload{name == nullptr ? "" : name, mime_type == nullptr ? "" : mime_type, len});
    self.answer_upload(completion, userdata);
  }

  void answer_upload(reactor_completion_fn completion, void* userdata) {
    if (completion == nullptr) {
      return;
    }
    const std::string result = upload_result;
    const std::string error = upload_error;
    spawn([completion, userdata, result, error] {
      if (error.empty()) {
        completion(1, result.c_str(), nullptr, userdata);
      } else {
        completion(0, nullptr, error.c_str(), userdata);
      }
    });
  }

  static FakeCommands* current_instance;

  int handle_marker_ = 0;
  ReactorCallbacks callbacks_{};
  std::mutex mutex_;
  std::vector<std::thread> threads_;
};

FakeCommands* FakeCommands::current_instance = nullptr;

struct Connected {
  Connected() : override_(&table_) { client.connect().get(); }

  FakeCommands session;
  reactor::detail::Ffi table_ = session.table();
  reactor::detail::FfiOverride override_;
  reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};
};

/// A temporary file that removes itself.
struct TempFile {
  explicit TempFile(const std::string& contents) {
    static std::atomic<int> counter{0};
    path = std::filesystem::temp_directory_path() /
           ("reactor-cpp-upload-" + std::to_string(++counter) + ".bin");
    std::ofstream out(path, std::ios::binary);
    out << contents;
  }
  ~TempFile() { std::filesystem::remove(path); }

  TempFile(const TempFile&) = delete;
  TempFile& operator=(const TempFile&) = delete;
  TempFile(TempFile&&) = delete;
  TempFile& operator=(TempFile&&) = delete;

  std::filesystem::path path;
};

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

}  // namespace

TEST_CASE("a command carries its name and arguments, and its reply comes back") {
  Connected fixture;

  const auto reply =
      fixture.client.send_command("set_prompt", reactor::Json{{"prompt", "a red fox"}}).get();

  REQUIRE(fixture.session.commands.size() == 1);
  CHECK(fixture.session.commands.front().name == "set_prompt");
  CHECK(reactor::Json::parse(fixture.session.commands.front().args_json) ==
        reactor::Json{{"prompt", "a red fox"}});

  REQUIRE(reply.has_value());
  CHECK(reply.value_or(reactor::Json::object())["type"] == "ack");
}

// A handler that ran and returned no message is a success with no value. Reported
// as a failure, a working `set_<field>` setter looks broken.
TEST_CASE("a command that returns no message resolves to nothing, not to an error") {
  Connected fixture;
  fixture.session.command_result.clear();

  const auto reply = fixture.client.send_command("start").get();
  CHECK_FALSE(reply.has_value());
}

TEST_CASE("a command with no arguments still sends an object") {
  Connected fixture;
  fixture.client.send_command("start").get();

  REQUIRE(fixture.session.commands.size() == 1);
  CHECK(fixture.session.commands.front().args_json == "{}");
  // No uploads means the pointer is null, not an empty object: the FFI documents
  // null as "no uploads", and `{}` would be a claim about parameters.
  CHECK(fixture.session.commands.front().uploads_were_null);
}

// The platform's own code for a command it refuses is more specific than anything
// this SDK could invent, so it travels through untouched.
TEST_CASE("a command the model rejects throws with the platform's own code") {
  Connected fixture;
  fixture.session.command_error =
      R"({"code":"MODEL_BUSY","message":"still rendering the last one","recoverable":true})";

  try {
    fixture.client.send_command("start").get();
    FAIL("a rejected command must throw");
  } catch (const reactor::ReactorError& error) {
    CHECK(error.code() == "MODEL_BUSY");
    CHECK(error.recoverable());
    CHECK(std::string{error.what()}.find("still rendering") != std::string::npos);
  }
}

TEST_CASE("two commands in flight each get their own reply") {
  Connected fixture;

  auto first = fixture.client.send_command("one");
  auto second = fixture.client.send_command("two");

  CHECK(first.get().has_value());
  CHECK(second.get().has_value());
  REQUIRE(fixture.session.commands.size() == 2);
  CHECK(fixture.session.commands[0].name == "one");
  CHECK(fixture.session.commands[1].name == "two");
}

TEST_CASE("a command sent before there is a session is refused rather than hung") {
  FakeCommands session;
  const auto table = session.table();
  const reactor::detail::FfiOverride override{&table};
  reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};

  CHECK_THROWS_AS(client.send_command("start").get(), reactor::InvalidStateError);
  CHECK(session.commands.empty());
}

TEST_CASE("a command sent while the session is not ready is refused") {
  Connected fixture;
  fixture.session.current_status = "waiting";

  try {
    fixture.client.send_command("start").get();
    FAIL("a command sent while not ready must be refused");
  } catch (const reactor::InvalidStateError& error) {
    CHECK(std::string{error.what()}.find("waiting") != std::string::npos);
  }
}

// ── Uploads ──────────────────────────────────────────────────────────────────

TEST_CASE("uploading a file returns a reference a command can use") {
  Connected fixture;
  const TempFile file{"pretend this is a jpeg"};

  const auto ref = fixture.client.upload_file(file.path.string()).get();

  CHECK(ref.upload_id == "up_123");
  CHECK(ref.name == "photo.jpg");
  CHECK(ref.mime_type == "image/jpeg");
  CHECK(ref.size == 42);
  REQUIRE(fixture.session.uploads.size() == 1);
  CHECK(fixture.session.uploads.front().path_or_name == file.path.string());
}

TEST_CASE("a FileRef reaches the uploads object, and never the arguments") {
  Connected fixture;
  const TempFile file{"bytes"};
  const auto ref = fixture.client.upload_file(file.path.string()).get();

  fixture.client.send_command("set_image", reactor::Json{{"strength", 0.8}}, {{"image", ref}})
      .get();

  REQUIRE(fixture.session.commands.size() == 1);
  const auto& command = fixture.session.commands.front();

  const auto args = reactor::Json::parse(command.args_json);
  CHECK(args == reactor::Json{{"strength", 0.8}});
  CHECK(args.find("image") == args.end());

  REQUIRE_FALSE(command.uploads_were_null);
  const auto uploads = reactor::Json::parse(command.uploads_json);
  REQUIRE(uploads.contains("image"));
  CHECK(uploads["image"]["upload_id"] == "up_123");
  CHECK(uploads["image"]["mime_type"] == "image/jpeg");
  CHECK(uploads["image"]["size"] == 42);
}

TEST_CASE("uploading bytes passes them through with their name and type") {
  Connected fixture;
  const std::vector<std::uint8_t> data{1, 2, 3, 4, 5};

  const auto ref =
      fixture.client
          .upload_bytes(reactor::Bytes{data.data(), data.size()}, "frame.png", "image/png")
          .get();

  CHECK(ref.upload_id == "up_123");
  REQUIRE(fixture.session.uploads.size() == 1);
  CHECK(fixture.session.uploads.front().path_or_name == "frame.png");
  CHECK(fixture.session.uploads.front().mime_type == "image/png");
  CHECK(fixture.session.uploads.front().byte_count == 5);
}

// Named here so the failure says which path, rather than arriving as whatever the
// coordinator says about an upload with no bytes in it.
TEST_CASE("uploading a file that does not exist is refused, naming the path") {
  Connected fixture;

  try {
    fixture.client.upload_file("/nonexistent/photo.jpg").get();
    FAIL("uploading a missing file must be refused");
  } catch (const reactor::NotFoundError& error) {
    CHECK(std::string{error.what()}.find("/nonexistent/photo.jpg") != std::string::npos);
  }
  CHECK(fixture.session.uploads.empty());
}

TEST_CASE("uploading an empty buffer is refused") {
  Connected fixture;
  CHECK_THROWS_AS(
      fixture.client.upload_bytes(reactor::Bytes{}, "empty.bin", "application/octet-stream").get(),
      reactor::BadRequestError);
  CHECK(fixture.session.uploads.empty());
}

TEST_CASE("uploading before there is a session is refused") {
  FakeCommands session;
  const auto table = session.table();
  const reactor::detail::FfiOverride override{&table};
  reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};
  const TempFile file{"bytes"};

  CHECK_THROWS_AS(client.upload_file(file.path.string()).get(), reactor::InvalidStateError);
}

// An upload that reports success and gives nothing to refer to it by is unusable
// by any command, so it fails here rather than producing an empty reference that
// fails later and further away.
TEST_CASE("an upload that returns no upload_id is a decode failure") {
  Connected fixture;
  const TempFile file{"bytes"};
  fixture.session.upload_result = R"({"name":"photo.jpg"})";

  CHECK_THROWS_AS(fixture.client.upload_file(file.path.string()).get(), reactor::DecodeError);
}

// ── Schema and messages ──────────────────────────────────────────────────────

// A success whose payload cannot be parsed used to become `{}`: request_schema()
// answered with a schema declaring nothing, which a caller cannot tell from a model
// that really declares nothing — and it buried the ABI or server corruption that
// actually happened.
TEST_CASE("a success whose payload is not JSON is a decode failure") {
  Connected fixture;
  fixture.session.schema_result = "{not json at all";

  auto future = fixture.client.request_schema();
  REQUIRE(future.wait_for(std::chrono::seconds{2}) == std::future_status::ready);
  CHECK_THROWS_AS(future.get(), reactor::DecodeError);
}

// The same class of failure one field deeper: a field of the wrong type made the
// conversion throw *after* `settle` had claimed the operation, so the trampoline's
// fallback could no longer fail it and the caller got future_error(broken_promise)
// instead of the documented typed error.
TEST_CASE("an upload whose size is not a number is a decode failure") {
  Connected fixture;
  fixture.session.upload_result = R"({"upload_id":"up_1","name":"photo.jpg","size":"very big"})";

  const std::vector<std::uint8_t> bytes{0x01, 0x02, 0x03};
  auto future = fixture.client.upload_bytes(reactor::Bytes{bytes.data(), bytes.size()}, "photo.jpg",
                                            "image/jpeg");
  REQUIRE(future.wait_for(std::chrono::seconds{2}) == std::future_status::ready);
  CHECK_THROWS_AS(future.get(), reactor::DecodeError);
}

TEST_CASE("the schema comes back as the document it is") {
  Connected fixture;
  const auto schema = fixture.client.request_schema().get();
  CHECK(schema["openapi"] == "3.1.0");
}

TEST_CASE("model messages and runtime messages are separate events") {
  Connected fixture;

  std::mutex mutex;
  std::vector<reactor::Json> model;
  std::vector<reactor::Json> runtime;

  auto on_model = fixture.client.on_message([&](const reactor::Json& message) {
    const std::lock_guard<std::mutex> lock(mutex);
    model.push_back(message);
  });
  auto on_runtime = fixture.client.on_runtime_message([&](const reactor::Json& message) {
    const std::lock_guard<std::mutex> lock(mutex);
    runtime.push_back(message);
  });

  fixture.session.push_message(R"({"type":"frame_ready","data":{"n":1}})");
  fixture.session.push_runtime_message(R"({"type":"clip_ready","data":{"url":"x"}})");

  REQUIRE(eventually([&] {
    const std::lock_guard<std::mutex> lock(mutex);
    return model.size() == 1 && runtime.size() == 1;
  }));

  const std::lock_guard<std::mutex> lock(mutex);
  CHECK(model.front()["type"] == "frame_ready");
  CHECK(runtime.front()["type"] == "clip_ready");
  // A caller reading only model messages never sees the platform's.
  CHECK(model.size() == 1);
}

TEST_CASE("a message that is not JSON is dropped rather than thrown from a callback") {
  Connected fixture;

  std::atomic<int> received{0};
  auto subscription = fixture.client.on_message([&](const reactor::Json&) { ++received; });

  CHECK_NOTHROW(fixture.session.push_message("not json at all"));
  CHECK_NOTHROW(fixture.session.push_message(R"({"type":"fine"})"));

  REQUIRE(eventually([&] { return received.load() == 1; }));
  CHECK(received.load() == 1);
}
