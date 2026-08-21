#include "reactor/reactor.hpp"

#include <atomic>
#include <cstdint>
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
    if (const auto impl = from_userdata(userdata)) {
      // Read here, not kept: the string is valid for this call only.
      impl->fire_status(status_from_string(status == nullptr ? "" : status));
    }
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
    if (mid_or_null != nullptr) {
      const std::lock_guard<std::mutex> lock(impl->media_mutex_);
      // Renegotiated on a reconnect, so this overwrites rather than inserts.
      impl->track_mids_[track_name] = mid_or_null;
    }
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
        result = Json::object();
      }
    }
    raw->settle(std::move(result));
    return;
  }
  raw->fail(as_exception_ptr(error_from_payload(error_json, raw->operation)->details()));
}

// ── The two-step connect ─────────────────────────────────────────────────────

namespace {

/// A connect that has to mint a token first.
///
/// Owned by its own completion, which fires exactly once and is not tied to any
/// handle — `reactor_fetch_jwt` takes none, because there is no session yet.
struct TokenExchange {
  std::weak_ptr<ClientImpl> impl;
  std::unique_ptr<Pending> op;
  ConnectOptions options;
};

/// The body of the token completion, where throwing is allowed because the
/// trampoline below catches. Defined after it, declared here.
void resume_connect_with_token(TokenExchange& exchange, int ok, const char* result_json,
                               const char* error_json);

extern "C" void token_completion_trampoline(int ok, const char* result_json, const char* error_json,
                                            void* userdata) noexcept {
  std::unique_ptr<TokenExchange> exchange{static_cast<TokenExchange*>(userdata)};
  if (exchange == nullptr) {
    return;
  }

  try {
    resume_connect_with_token(*exchange, ok, result_json, error_json);
  } catch (...) {
    try {
      exchange->op->fail(
          std::make_exception_ptr(ReactorError{"the SDK could not finish exchanging the API key"}));
    } catch (...) {  // NOLINT(bugprone-empty-catch)
    }
  }
}

void resume_connect_with_token(TokenExchange& exchange, int ok, const char* result_json,
                               const char* error_json) {
  const auto impl = exchange.impl.lock();
  if (!impl) {
    exchange.op->fail(
        as_exception_ptr(ErrorDetails{std::string{codes::ABORTED},
                                      "the client was destroyed while exchanging the API key",
                                      false,
                                      {},
                                      "connect",
                                      {},
                                      {}}));
    return;
  }

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

  impl->set_jwt(token);
  // Same call as the no-key path takes, now that there is a token. Running it
  // from this thread is fine: it is one of the library's own, and creating a
  // handle is not a callback of any handle.
  impl->begin_connect(std::move(exchange.op), std::move(exchange.options));
}

}  // namespace

void ClientImpl::begin_connect(std::unique_ptr<Pending> op, ConnectOptions options) {
  bool needs_token = false;
  std::string key;
  {
    const std::lock_guard<std::mutex> lock(mutex_);
    needs_token = api_key_.has_value() && !jwt_.has_value();
    if (needs_token) {
      key = *api_key_;
    }
  }

  if (needs_token) {
    // Scoped to this model, so a leaked token is worth a handful of sessions on
    // one model rather than everything the key can reach.
    const Json token_options = Json{{"models", Json::array({model_})}};
    const std::string options_json = token_options.dump();

    auto* exchange = new TokenExchange{weak_from_this(), std::move(op), std::move(options)};
    ffi().fetch_jwt(api_url_.c_str(), key.c_str(), options_json.c_str(), local_ ? 1 : 0,
                    &token_completion_trampoline, exchange);
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

  Pending* raw = track(std::move(op));
  ffi().connect(handle, session, connection, &completion_trampoline, raw);
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
