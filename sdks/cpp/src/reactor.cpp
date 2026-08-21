#include "reactor/reactor.hpp"

#include <atomic>
#include <cstdint>
#include <filesystem>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include "detail/client_impl.hpp"
#include "detail/ffi.hpp"
#include "detail/log.hpp"
#include "detail/strings.hpp"
#include "reactor/json.hpp"

namespace reactor {

// ── Status ───────────────────────────────────────────────────────────────────

std::string_view to_string(Status status) noexcept {
  switch (status) {
    case Status::Disconnected:
      return "disconnected";
    case Status::Connecting:
      return "connecting";
    case Status::Waiting:
      return "waiting";
    case Status::Ready:
      return "ready";
  }
  return "disconnected";
}

Status status_from_string(std::string_view text) noexcept {
  if (text == "ready") {
    return Status::Ready;
  }
  if (text == "waiting") {
    return Status::Waiting;
  }
  if (text == "connecting") {
    return Status::Connecting;
  }
  // Including "disconnected", and including anything this build has never heard
  // of: a client that cannot understand what the library is telling it should
  // behave as though it has no session rather than assume the most capable state
  // it knows about.
  return Status::Disconnected;
}

std::int64_t time_micros() noexcept { return detail::ffi().time_micros(); }

namespace detail {

// ── Trampolines ──────────────────────────────────────────────────────────────

void ClientImpl::on_status_trampoline(const char* status, void* userdata) noexcept {
  try {
    const auto impl = from_userdata(userdata);
    if (!impl) {
      return;
    }
    // Read here, not kept: the string is valid for this call only.
    const Status now = status_from_string(status == nullptr ? "" : status);

    // Both of these happen *on this thread*, before the event is queued. A push
    // racing the status change has to see the new answer, not the one the
    // dispatcher has not got to yet.
    impl->invalidate_declared();
    if (now != Status::Ready) {
      // A reconnect resumes recvonly tracks and nothing else, so a slot published
      // before one is not published after it. Remembering otherwise would let a
      // push through onto a slot with no sender behind it.
      impl->clear_published();
    }

    impl->fire_status(now);
  } catch (...) {
    // Dropped deliberately. There is no caller to report to — the caller is
    // Rust — and a status this SDK failed to queue is one it will be told again
    // on the next transition.
  }
}

void ClientImpl::on_error_trampoline(const char* error_json, void* userdata) noexcept {
  try {
    if (const auto impl = from_userdata(userdata)) {
      std::shared_ptr<ReactorError> error{error_from_payload(error_json)};
      impl->fire_error(error);
    }
  } catch (...) {
    // As above. Failing to deliver an error event is bad; unwinding into Rust is
    // worse.
  }
}

void ClientImpl::on_track_trampoline(const char* name, const char* mid_or_null,
                                     void* userdata) noexcept {
  try {
    const auto impl = from_userdata(userdata);
    if (!impl || name == nullptr) {
      return;
    }
    const std::string track_name{name};
    {
      const std::lock_guard<std::mutex> lock(impl->media_mutex_);
      if (mid_or_null != nullptr) {
        // Renegotiated on a reconnect, so this overwrites rather than inserts.
        impl->track_mids_[track_name] = mid_or_null;
      } else {
        // And a track reported *without* one has no MID, which is not the same as
        // "keep whatever it had last time": after a renegotiation that is a
        // transceiver that no longer exists, and `Track::mid()` would hand back a
        // string from a session that is gone.
        impl->track_mids_.erase(track_name);
      }
    }
    impl->invalidate_declared();
    // A Track, not a name: a handler that has to go back and ask the client for
    // the track it was just told about is a handler doing the SDK's job.
    Track track{impl->weak_from_this(), track_name};
    impl->dispatcher_.post([weak = impl->weak_from_this(), track = std::move(track)] {
      if (const auto self = weak.lock()) {
        self->track_handlers_.invoke(track);
      }
    });
  } catch (...) {
    // As with every trampoline: this frame was called from Rust.
  }
}

void ClientImpl::fire_message(Handlers<const Json&>& handlers, const char* msg_json) {
  // Parsed and copied here, not in the handler: the string is valid for this call
  // only, and the handler runs later, on the dispatcher.
  auto message = std::make_shared<Json>(
      Json::parse(msg_json == nullptr ? "" : msg_json, nullptr, /*allow_exceptions=*/false));
  if (message->is_discarded()) {
    log_warn_once("message-unparseable",
                  "a message arrived that is not valid JSON; it has been dropped");
    return;
  }
  dispatcher_.post([weak = weak_from_this(), &handlers, message] {
    if (const auto self = weak.lock()) {
      handlers.invoke(*message);
    }
  });
}

void ClientImpl::on_message_trampoline(const char* msg_json, void* userdata) noexcept {
  try {
    if (const auto impl = from_userdata(userdata)) {
      impl->fire_message(impl->message_handlers_, msg_json);
    }
  } catch (...) {
  }
}

void ClientImpl::on_runtime_message_trampoline(const char* msg_json, void* userdata) noexcept {
  try {
    if (const auto impl = from_userdata(userdata)) {
      // A separate event, not a filtered one: session lifecycle notices and clip
      // readiness are the platform's business, and a caller reading only model
      // messages should never have to sift them out.
      impl->fire_message(impl->runtime_message_handlers_, msg_json);
    }
  } catch (...) {
  }
}

void ClientImpl::on_capabilities_trampoline(const char* caps_json, void* userdata) noexcept {
  try {
    const auto impl = from_userdata(userdata);
    if (!impl) {
      return;
    }
    // The capabilities carry the same track entries `reactor_tracks` reports, so
    // there is nothing here to parse — what matters is that the answer changed, and
    // the cached one has to go.
    (void)caps_json;
    impl->invalidate_declared();
  } catch (...) {
  }
}

void ClientImpl::on_frame_trampoline(const char* track_name, const std::uint8_t* data,
                                     std::uint32_t width, std::uint32_t height,
                                     std::uint64_t frame_id, std::uint64_t timestamp_us,
                                     const std::uint8_t* user_data, std::uint32_t user_data_len,
                                     void* userdata) noexcept {
  try {
    const auto impl = from_userdata(userdata);
    if (!impl) {
      return;
    }
    // Never NULL per the header, but empty when the transceiver could not be
    // matched to a declared track — which is a case with its own diagnostic.
    const std::string name{track_name == nullptr ? "" : track_name};

    VideoFrame frame;
    frame.track_name = name;
    frame.bgra = data;
    frame.width = width;
    frame.height = height;
    frame.frame_id = frame_id;
    frame.timestamp_us = timestamp_us;
    if (user_data != nullptr && user_data_len != 0) {
      frame.user_data = Bytes{user_data, user_data_len};
    }

    // Nothing is copied. The frame is handed to the handler exactly as it arrived,
    // and is invalid the moment this returns — which is why VideoFrame says so.
    impl->deliver_video(name, frame);
  } catch (...) {
  }
}

void ClientImpl::on_audio_trampoline(const char* track_name, const std::int16_t* samples,
                                     std::uint32_t num_samples, std::uint32_t sample_rate,
                                     std::uint32_t channels, void* userdata) noexcept {
  try {
    const auto impl = from_userdata(userdata);
    if (!impl) {
      return;
    }
    const std::string name{track_name == nullptr ? "" : track_name};

    AudioFrame frame;
    frame.track_name = name;
    frame.samples = samples;
    frame.num_samples = num_samples;
    frame.sample_rate = sample_rate;
    frame.channels = channels;

    impl->deliver_audio(name, frame);
  } catch (...) {
  }
}

void ClientImpl::completion_trampoline(int ok, const char* result_json, const char* error_json,
                                       void* userdata) noexcept {
  auto* raw = static_cast<Pending*>(userdata);
  if (raw == nullptr) {
    return;
  }
  try {
    settle_completion(raw, ok, result_json, error_json);
  } catch (...) {
    // Whatever went wrong here, the caller is waiting on a future. Leaving it
    // unsettled would hang them for the life of the process, so the last resort
    // is to fail it — and if even that throws, there is nothing left to try.
    try {
      raw->fail(
          std::make_exception_ptr(ReactorError{"the SDK could not deliver this call's result"}));
    } catch (...) {  // NOLINT(bugprone-empty-catch)
    }
  }
}

void ClientImpl::settle_completion(Pending* raw, int ok, const char* result_json,
                                   const char* error_json) {
  // Reclaimed from the client if it is still tracked. Null means teardown got
  // here first and the pointer now belongs to the orphan store, so it is read
  // but never freed.
  std::unique_ptr<Pending> owned;
  if (const auto impl = raw->owner.lock()) {
    owned = impl->untrack(raw);
  }

  if (ok == 1) {
    Json result = Json::object();
    if (result_json != nullptr) {
      result = Json::parse(result_json, nullptr, /*allow_exceptions=*/false);
      if (result.is_discarded()) {
        // A success whose payload cannot be parsed is not a success with an empty
        // one. Substituting `{}` made request_schema() answer with a schema
        // declaring nothing, which a caller cannot tell from a model that really
        // declares nothing — and it hides exactly the ABI or server corruption
        // worth knowing about. An absent payload (a null pointer) is different and
        // still means "nothing to report".
        raw->fail(as_exception_ptr(
            ErrorDetails{std::string{codes::DECODE_FAILED},
                         "this call succeeded but its result could not be read as JSON: " +
                             std::string{result_json},
                         false,
                         {},
                         raw->operation,
                         {},
                         {}}));
        return;
      }
    }
    raw->settle(std::move(result));
    return;
  }
  raw->fail(as_exception_ptr(error_from_payload(error_json, raw->operation)->details()));
}

// ── The two-step connect ─────────────────────────────────────────────────────

/// A connect that has to mint a token first.
///
/// Owned by the client rather than by its own completion, and that is the
/// difference that matters: `reactor_fetch_jwt` takes no handle, so
/// `reactor_destroy` neither stops the request nor waits for what it will call
/// back. An exchange owned only by a completion that may never come is a caller
/// blocked in `.get()` for the life of the process, and an object nothing frees.
struct TokenExchange {
  std::weak_ptr<ClientImpl> impl;
  std::unique_ptr<Pending> op;
  ConnectOptions options;
  /// What this token is being minted for, so the client can tell later whether
  /// the one it holds is still the right one.
  ClientImpl::TokenScope scope = ClientImpl::TokenScope::ThisModel;
};

void settle_exchange_as_aborted(TokenExchange& exchange) {
  if (!exchange.op) {
    return;
  }
  exchange.op->fail(
      as_exception_ptr(ErrorDetails{std::string{codes::ABORTED},
                                    "the client was destroyed while exchanging the API key",
                                    false,
                                    {},
                                    "connect",
                                    {},
                                    {}}));
}

namespace {

/// What the FFI is handed, and all it is handed.
///
/// The completion owns this and nothing else, so it is always safe to read. The
/// exchange it names may be gone, and a weak reference is how it finds that out
/// instead of by dereferencing what teardown freed.
struct TokenTicket {
  std::weak_ptr<TokenExchange> exchange;
};

/// The body of the token completion, where throwing is allowed because the
/// trampoline below catches. Defined after it, declared here.
void resume_connect_with_token(TokenExchange& exchange, int ok, const char* result_json,
                               const char* error_json);

extern "C" void token_completion_trampoline(int ok, const char* result_json, const char* error_json,
                                            void* userdata) noexcept {
  // The ticket is this completion's own, whatever else happened.
  const std::unique_ptr<TokenTicket> ticket{static_cast<TokenTicket*>(userdata)};
  if (ticket == nullptr) {
    return;
  }

  const std::shared_ptr<TokenExchange> exchange = ticket->exchange.lock();
  if (!exchange) {
    // The client was destroyed and teardown settled this on the way out. Nothing
    // here is ours to touch, and the caller's future already has its answer.
    return;
  }

  try {
    resume_connect_with_token(*exchange, ok, result_json, error_json);
  } catch (...) {
    try {
      if (exchange->op) {
        exchange->op->fail(std::make_exception_ptr(
            ReactorError{"the SDK could not finish exchanging the API key"}));
      }
    } catch (...) {  // NOLINT(bugprone-empty-catch)
    }
  }
}

void resume_connect_with_token(TokenExchange& exchange, int ok, const char* result_json,
                               const char* error_json) {
  const auto impl = exchange.impl.lock();
  if (!impl) {
    // Teardown already failed this — `fail` settles once — and the exchange goes
    // with the client. Saying it again costs nothing and covers a client that
    // expired without a teardown of its own.
    if (exchange.op) {
      exchange.op->fail(
          as_exception_ptr(ErrorDetails{std::string{codes::ABORTED},
                                        "the client was destroyed while exchanging the API key",
                                        false,
                                        {},
                                        "connect",
                                        {},
                                        {}}));
    }
    return;
  }

  // The completion arrived, so the client has nothing left to settle for this
  // exchange. Done before the op moves on, so teardown can never find an entry
  // whose op has already been handed to `connect`.
  impl->forget_exchange(&exchange);

  if (ok != 1) {
    exchange.op->fail(as_exception_ptr(error_from_payload(error_json, "connect")->details()));
    return;
  }

  const Json payload = Json::parse(result_json == nullptr ? "" : result_json, nullptr,
                                   /*allow_exceptions=*/false);
  const auto token = payload.is_object() ? payload.value("jwt", std::string{}) : std::string{};
  if (token.empty()) {
    exchange.op->fail(
        as_exception_ptr(ErrorDetails{std::string{codes::DECODE_FAILED},
                                      "the coordinator returned no token for this API key",
                                      false,
                                      {},
                                      "connect",
                                      {},
                                      {}}));
    return;
  }

  // Records the scope with the token, and drops the handle if the token changed —
  // the native client is handed its token at creation, so a new one only reaches
  // it through a new handle.
  impl->adopt_minted_token(token, exchange.scope);
  // Same call as the no-key path takes, now that there is a token. Running it
  // from this thread is fine: it is one of the library's own, and creating a
  // handle is not a callback of any handle.
  impl->begin_connect(std::move(exchange.op), std::move(exchange.options));
}

}  // namespace

void ClientImpl::begin_connect(std::unique_ptr<Pending> op, ConnectOptions options) {
  // The scope depends on the *call*, not on the client. Creating a session wants a
  // model-scoped token: a leak is then worth a handful of sessions on one model
  // rather than everything the key can reach. Adopting a session created
  // elsewhere needs the broader one, because a scoped token cannot reach a session
  // it did not create — which is a 403 on `get session`, and exactly what the
  // multi-connection example hit.
  const TokenScope wanted =
      options.session_id.has_value() ? TokenScope::Unscoped : TokenScope::ThisModel;

  bool needs_token = false;
  std::string key;
  {
    const std::lock_guard<std::mutex> lock(mutex_);
    // Not just "have we got one": a token minted for one connect is not
    // necessarily right for the next. Never re-minted over a token the caller
    // supplied, which is theirs.
    needs_token = api_key_.has_value() && !caller_supplied_jwt_ &&
                  (!jwt_.has_value() || minted_for_ != wanted);
    if (needs_token) {
      key = *api_key_;
    }
  }

  if (needs_token) {
    Json token_options = Json::object();
    if (wanted == TokenScope::ThisModel) {
      token_options["models"] = Json::array({model_});
    }
    const std::string options_json = token_options.dump();

    auto exchange = std::make_shared<TokenExchange>(
        TokenExchange{weak_from_this(), std::move(op), std::move(options), wanted});
    // Watched before the request goes out: a completion arriving on another thread
    // while this one is still here must find it already registered.
    auto ticket = std::make_unique<TokenTicket>(TokenTicket{exchange});
    watch_exchange(std::move(exchange));
    ffi().fetch_jwt(api_url_.c_str(), key.c_str(), options_json.c_str(), local_ ? 1 : 0,
                    &token_completion_trampoline, ticket.release());
    return;
  }

  try {
    ensure_handle();
  } catch (...) {
    op->fail(std::current_exception());
    return;
  }

  ReactorHandle* handle = nullptr;
  {
    const std::lock_guard<std::mutex> lock(mutex_);
    handle = handle_;
  }

  // Borrowed for the duration of the call, which is all the FFI needs: it copies
  // what it keeps before returning.
  const char* session = options.session_id ? options.session_id->c_str() : nullptr;
  const std::uint32_t connection_value = options.connection_id.value_or(0);
  const std::uint32_t* connection = options.connection_id ? &connection_value : nullptr;

  Pending* raw = track_pending(std::move(op));
  ffi().connect(handle, session, connection, &completion_trampoline, raw);
}

// ── Commands, messages, uploads ──────────────────────────────────────────────

void ClientImpl::begin_send_command(std::unique_ptr<Pending> op, const std::string& command,
                                    const Json& args,
                                    const std::map<std::string, FileRef>& uploads) {
  try {
    ReactorHandle* handle = require_ready_handle("send a command on", command);

    const std::string args_json = args.is_null() ? "{}" : args.dump();

    std::string uploads_json;
    if (!uploads.empty()) {
      Json object = Json::object();
      for (const auto& [parameter, ref] : uploads) {
        object[parameter] = Json{{"upload_id", ref.upload_id},
                                 {"name", ref.name},
                                 {"mime_type", ref.mime_type},
                                 {"size", ref.size}};
      }
      uploads_json = object.dump();
    }

    auto* raw = track_pending(std::move(op));
    ffi().send_command(handle, command.c_str(), args_json.c_str(),
                       uploads_json.empty() ? nullptr : uploads_json.c_str(),
                       &completion_trampoline, raw);
  } catch (...) {
    op->fail(std::current_exception());
  }
}

void ClientImpl::begin_request_schema(std::unique_ptr<Pending> op) {
  try {
    ReactorHandle* handle = require_ready_handle("request the schema of", model_);
    auto* raw = track_pending(std::move(op));
    ffi().request_schema(handle, &completion_trampoline, raw);
  } catch (...) {
    op->fail(std::current_exception());
  }
}

void ClientImpl::begin_upload_file(std::unique_ptr<Pending> op, const std::string& path) {
  try {
    // Checked here so the failure names the path, rather than arriving as whatever
    // the coordinator says about an upload with no bytes.
    if (!std::filesystem::exists(path)) {
      throw NotFoundError{"no file at \"" + path + "\""};
    }
    ReactorHandle* handle = require_ready_handle("upload a file to", path);
    auto* raw = track_pending(std::move(op));
    ffi().upload_file(handle, path.c_str(), &completion_trampoline, raw);
  } catch (...) {
    op->fail(std::current_exception());
  }
}

void ClientImpl::begin_upload_bytes(std::unique_ptr<Pending> op, Bytes data,
                                    const std::string& name, const std::string& mime_type) {
  try {
    if (data.data == nullptr || data.size == 0) {
      throw BadRequestError{"cannot upload an empty buffer as \"" + name + "\""};
    }
    ReactorHandle* handle = require_ready_handle("upload bytes to", name);
    auto* raw = track_pending(std::move(op));
    // Borrowed for the duration of the call, which the header states and this
    // relies on: the FFI copies before returning.
    ffi().upload_bytes(handle, data.data, data.size, name.c_str(), mime_type.c_str(),
                       &completion_trampoline, raw);
  } catch (...) {
    op->fail(std::current_exception());
  }
}

}  // namespace detail

// ── Reactor ──────────────────────────────────────────────────────────────────

Reactor::Reactor(std::string model, ApiKey key, Options options)
    : impl_(std::make_shared<detail::ClientImpl>(std::move(model), std::move(options))) {
  impl_->set_api_key(std::move(key.value));
}

Reactor::Reactor(std::string model, Jwt jwt, Options options)
    : impl_(std::make_shared<detail::ClientImpl>(std::move(model), std::move(options))) {
  impl_->set_jwt(std::move(jwt.value));
}

Reactor::~Reactor() = default;
Reactor::Reactor(Reactor&&) noexcept = default;
Reactor& Reactor::operator=(Reactor&&) noexcept = default;

std::future<void> Reactor::connect(ConnectOptions options) {
  auto op = std::make_unique<detail::PendingVoid>();
  op->operation = "connect";
  auto future = op->promise.get_future();
  impl_->begin_connect(std::move(op), std::move(options));
  return future;
}

std::future<void> Reactor::reconnect() {
  auto op = std::make_unique<detail::PendingVoid>();
  op->operation = "reconnect";
  auto future = op->promise.get_future();
  impl_->begin_reconnect(std::move(op));
  return future;
}

std::future<void> Reactor::disconnect() {
  auto op = std::make_unique<detail::PendingVoid>();
  op->operation = "disconnect";
  auto future = op->promise.get_future();
  impl_->begin_disconnect(std::move(op));
  return future;
}

Status Reactor::status() const { return impl_->status(); }

std::optional<std::string> Reactor::session_id() const { return impl_->session_id(); }

Subscription Reactor::on_status(std::function<void(Status)> handler) {
  const std::uint64_t id = impl_->status_handlers().add(std::move(handler));
  return Subscription{[weak = std::weak_ptr<detail::ClientImpl>{impl_}, id] {
    if (const auto impl = weak.lock()) {
      impl->status_handlers().remove(id);
    }
  }};
}

std::future<std::optional<Json>> Reactor::send_command(std::string command, Json args,
                                                       std::map<std::string, FileRef> uploads) {
  auto op = std::make_unique<detail::PendingOptionalJson>();
  op->operation = "send_command";
  auto future = op->promise.get_future();
  impl_->begin_send_command(std::move(op), command, args, uploads);
  return future;
}

std::future<Json> Reactor::request_schema() {
  auto op = std::make_unique<detail::PendingJson>();
  op->operation = "request_schema";
  auto future = op->promise.get_future();
  impl_->begin_request_schema(std::move(op));
  return future;
}

std::future<FileRef> Reactor::upload_file(std::string path) {
  auto op = std::make_unique<detail::PendingFileRef>();
  op->operation = "upload_file";
  auto future = op->promise.get_future();
  impl_->begin_upload_file(std::move(op), path);
  return future;
}

std::future<FileRef> Reactor::upload_bytes(Bytes data, std::string name, std::string mime_type) {
  auto op = std::make_unique<detail::PendingFileRef>();
  op->operation = "upload_bytes";
  auto future = op->promise.get_future();
  impl_->begin_upload_bytes(std::move(op), data, name, mime_type);
  return future;
}

Subscription Reactor::on_message(std::function<void(const Json&)> handler) {
  const std::uint64_t id = impl_->message_handlers().add(std::move(handler));
  return Subscription{[weak = std::weak_ptr<detail::ClientImpl>{impl_}, id] {
    if (const auto impl = weak.lock()) {
      impl->message_handlers().remove(id);
    }
  }};
}

Subscription Reactor::on_runtime_message(std::function<void(const Json&)> handler) {
  const std::uint64_t id = impl_->runtime_message_handlers().add(std::move(handler));
  return Subscription{[weak = std::weak_ptr<detail::ClientImpl>{impl_}, id] {
    if (const auto impl = weak.lock()) {
      impl->runtime_message_handlers().remove(id);
    }
  }};
}

Track Reactor::track(const std::string& name) { return impl_->track(name); }

TrackList Reactor::tracks() { return impl_->tracks(); }

Subscription Reactor::on_track(std::function<void(Track)> handler) {
  const std::uint64_t id = impl_->track_handlers().add(std::move(handler));
  return Subscription{[weak = std::weak_ptr<detail::ClientImpl>{impl_}, id] {
    if (const auto impl = weak.lock()) {
      impl->track_handlers().remove(id);
    }
  }};
}

Subscription Reactor::on_error(std::function<void(const ReactorError&)> handler) {
  const std::uint64_t id = impl_->error_handlers().add(std::move(handler));
  return Subscription{[weak = std::weak_ptr<detail::ClientImpl>{impl_}, id] {
    if (const auto impl = weak.lock()) {
      impl->error_handlers().remove(id);
    }
  }};
}

}  // namespace reactor
