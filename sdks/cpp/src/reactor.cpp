#include "reactor/reactor.hpp"

#include <atomic>
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include "detail/dispatcher.hpp"
#include "detail/ffi.hpp"
#include "detail/handlers.hpp"
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

// ── Calls in flight ──────────────────────────────────────────────────────────

/// A call in flight, from the completion's point of view.
///
/// The trampoline settles one of these and does not care whether the caller is
/// waiting for a value or for nothing at all.
class Pending {
 public:
  Pending() = default;
  virtual ~Pending() = default;

  Pending(const Pending&) = delete;
  Pending& operator=(const Pending&) = delete;
  Pending(Pending&&) = delete;
  Pending& operator=(Pending&&) = delete;

  /// Settle once, whoever gets here first: the completion, or teardown.
  ///
  /// Both are possible and neither can be ruled out — `reactor_destroy` may
  /// report that a callback could not be waited for, in which case a completion
  /// can still arrive afterwards.
  void settle(Json result) {
    if (claim()) {
      deliver(std::move(result));
    }
  }

  void fail(const std::exception_ptr& error) {
    if (claim()) {
      deliver_error(error);
    }
  }

  /// Which call this is, for an error payload that does not name one.
  std::string operation;

  /// The client that is tracking this, so a completion can hand it back.
  std::weak_ptr<ClientImpl> owner;

 private:
  bool claim() { return !settled_.exchange(true); }

  virtual void deliver(Json result) = 0;
  virtual void deliver_error(const std::exception_ptr& error) = 0;

  std::atomic<bool> settled_{false};
};

/// A call whose answer is nothing: connect, disconnect, reconnect.
class PendingVoid final : public Pending {
 public:
  std::promise<void> promise;

 private:
  void deliver(Json /*result*/) override { promise.set_value(); }
  void deliver_error(const std::exception_ptr& error) override { promise.set_exception(error); }
};

// ── The client's state ───────────────────────────────────────────────────────

class ClientImpl : public std::enable_shared_from_this<ClientImpl> {
 public:
  ClientImpl(std::string model_name, Options options)
      : model_(std::move(model_name)),
        api_url_(std::move(options.api_url)),
        local_(options.local),
        dispatcher_(std::move(options.executor)) {}

  ~ClientImpl() {
    // Before any member is destroyed: a callback that is still running is reading
    // them, and `destroy_handle` is what waits for it.
    destroy_handle();
    dispatcher_.stop();
  }

  ClientImpl(const ClientImpl&) = delete;
  ClientImpl& operator=(const ClientImpl&) = delete;
  ClientImpl(ClientImpl&&) = delete;
  ClientImpl& operator=(ClientImpl&&) = delete;

  /// The `userdata` every callback carries.
  ///
  /// Heap-allocated and never moved, so moving the owning `Reactor` cannot
  /// invalidate a pointer the library holds. It keeps only a weak reference: a
  /// handler parked on a capture thread must not hold the session — and the
  /// native handle — open for the life of that thread.
  struct Context {
    std::weak_ptr<ClientImpl> impl;
  };

  void set_api_key(std::string key) { api_key_ = std::move(key); }
  void set_jwt(std::string jwt) { jwt_ = std::move(jwt); }

  // ── Reads ──────────────────────────────────────────────────────────────────

  Status status() const {
    const std::lock_guard<std::mutex> lock(mutex_);
    // Through the FFI even with a null handle: it answers "disconnected", which
    // is the right answer and one fewer thing for this SDK to decide.
    const StaticString text{ffi().status(handle_)};
    return status_from_string(text.view());
  }

  std::optional<std::string> session_id() const {
    const std::lock_guard<std::mutex> lock(mutex_);
    const OwnedString id{ffi().session_id(handle_)};
    if (!id.has_value()) {
      return std::nullopt;
    }
    return id.to_string();
  }

  // ── Events ─────────────────────────────────────────────────────────────────

  Handlers<Status>& status_handlers() { return status_handlers_; }
  Handlers<const ReactorError&>& error_handlers() { return error_handlers_; }

  void fire_status(Status status) {
    dispatcher_.post([weak = weak_from_this(), status] {
      if (const auto self = weak.lock()) {
        self->status_handlers_.invoke(status);
      }
    });
  }

  void fire_error(const std::shared_ptr<ReactorError>& error) {
    dispatcher_.post([weak = weak_from_this(), error] {
      if (const auto self = weak.lock()) {
        self->error_handlers_.invoke(*error);
      }
    });
  }

  // ── Session ────────────────────────────────────────────────────────────────

  /// Exchange the key for a token if there is one to exchange, then connect.
  void begin_connect(std::unique_ptr<Pending> op, ConnectOptions options);

  void begin_reconnect(std::unique_ptr<Pending> op) {
    ReactorHandle* handle = nullptr;
    {
      const std::lock_guard<std::mutex> lock(mutex_);
      handle = handle_;
    }
    if (handle == nullptr) {
      // Nothing to reconnect to, and the FFI would decline anyway — but it would
      // decline through a completion, and saying so here names the actual
      // mistake.
      op->fail(as_exception_ptr(ErrorDetails{std::string{codes::INVALID_STATE},
                                             "reconnect() needs a session: connect() first",
                                             false,
                                             {},
                                             "reconnect",
                                             {},
                                             {}}));
      return;
    }
    Pending* raw = track(std::move(op));
    ffi().reconnect(handle, &completion_trampoline, raw);
  }

  void begin_disconnect(std::unique_ptr<Pending> op) {
    ReactorHandle* handle = nullptr;
    {
      const std::lock_guard<std::mutex> lock(mutex_);
      handle = handle_;
    }
    if (handle == nullptr) {
      // Already disconnected is not a failure: the caller asked for a state, and
      // it is in it.
      op->settle(Json::object());
      return;
    }
    Pending* raw = track(std::move(op));
    ffi().disconnect(handle, &completion_trampoline, raw);
  }

  /// Stop tracking `op` and hand ownership back.
  ///
  /// Null when teardown got there first, in which case the pointer belongs to the
  /// orphan store and must not be freed.
  std::unique_ptr<Pending> untrack(Pending* op) {
    const std::lock_guard<std::mutex> lock(mutex_);
    const auto id_it = pending_ids_.find(op);
    if (id_it == pending_ids_.end()) {
      return nullptr;
    }
    const auto it = pending_.find(id_it->second);
    pending_ids_.erase(id_it);
    if (it == pending_.end()) {
      return nullptr;
    }
    auto owned = std::move(it->second);
    pending_.erase(it);
    return owned;
  }

  /// The C completion for every async call.
  ///
  /// `noexcept`, like every trampoline here: these frames are called from Rust,
  /// and letting an exception unwind across that boundary is undefined behaviour.
  /// Nothing in them is allowed to escape, however unlikely — a `bad_alloc` while
  /// building an error object is still a `bad_alloc`.
  static void completion_trampoline(int ok, const char* result_json, const char* error_json,
                                    void* userdata) noexcept;

 private:
  /// The body of the completion, where throwing is allowed because the caller
  /// above catches.
  static void settle_completion(Pending* raw, int ok, const char* result_json,
                                const char* error_json);

 public:
 private:
  // ── Handle ─────────────────────────────────────────────────────────────────

  /// Create the native handle if it does not exist yet.
  ///
  /// Deferred to the first connect because it needs the token, and because a
  /// client that never connects should not have allocated a session's worth of
  /// machinery. Callers hold no lock.
  void ensure_handle() {
    const std::lock_guard<std::mutex> lock(mutex_);
    if (handle_ != nullptr) {
      return;
    }

    auto context = std::make_unique<Context>(Context{weak_from_this()});

    ReactorCallbacks callbacks{};
    callbacks.on_status = &on_status_trampoline;
    callbacks.on_error = &on_error_trampoline;
    callbacks.userdata = context.get();

    // Mode 0: the synthetic audio module, always. `reactor_create` takes its mode
    // from an environment variable, and a library whose audience is scripts and
    // servers must never let an env var put a live microphone on the wire because
    // a model happened to declare a sendonly audio track. The other entry point
    // is not even in the symbol table — see detail/ffi.hpp.
    constexpr int SYNTHETIC_ADM = 0;
    handle_ =
        ffi().create_with_adm(api_url_.c_str(), model_.c_str(), jwt_ ? jwt_->c_str() : nullptr,
                              local_ ? 1 : 0, &callbacks, SYNTHETIC_ADM);
    if (handle_ == nullptr) {
      throw ReactorError{"libreactor_ffi could not create a client (allocation failed)"};
    }
    context_ = context.release();
  }

  Pending* track(std::unique_ptr<Pending> op) {
    op->owner = weak_from_this();
    const std::lock_guard<std::mutex> lock(mutex_);
    const std::uint64_t id = next_pending_id_++;
    Pending* raw = op.get();
    pending_ids_.emplace(raw, id);
    pending_.emplace(id, std::move(op));
    return raw;
  }

  // ── Teardown ───────────────────────────────────────────────────────────────

  void destroy_handle() {
    ReactorHandle* to_destroy = nullptr;
    Context* to_release = nullptr;
    std::vector<std::unique_ptr<Pending>> outstanding;
    {
      const std::lock_guard<std::mutex> lock(mutex_);
      to_destroy = std::exchange(handle_, nullptr);
      to_release = std::exchange(context_, nullptr);
      outstanding.reserve(pending_.size());
      for (auto& entry : pending_) {
        outstanding.push_back(std::move(entry.second));
      }
      pending_.clear();
      pending_ids_.clear();
    }

    // 0 means quiescence: no callback is running and none will start, so the
    // context can go. -1 means one is still in flight and every pointer it holds
    // must stay valid — leaking is correct, freeing is a jump into freed memory.
    // A small permanent leak beats that every time.
    const int quiesced = to_destroy == nullptr ? 0 : ffi().destroy(to_destroy);

    for (auto& op : outstanding) {
      // Whatever the caller was waiting for is not coming. Saying so beats
      // leaving them blocked in .get() for the life of the process.
      op->fail(as_exception_ptr(ErrorDetails{std::string{codes::ABORTED},
                                             "the client was destroyed before this call completed",
                                             false,
                                             {},
                                             op->operation,
                                             {},
                                             {}}));
      if (quiesced != 0) {
        orphan(op.release());
      }
    }

    if (to_release != nullptr) {
      if (quiesced == 0) {
        delete to_release;  // NOLINT(cppcoreguidelines-owning-memory)
      } else {
        orphan(to_release);
      }
    }
  }

  /// Pointers deliberately never freed, because a callback may still reach them.
  ///
  /// The C++ twin of the Python SDK's `_ORPHANED_CALLBACKS`. It only grows on the
  /// path where `reactor_destroy` reported that it could not wait for a callback,
  /// which takes a wedged host or a destroy called from inside a callback.
  static void orphan(void* pointer) {
    static std::mutex mutex;
    static std::vector<void*> orphaned;
    const std::lock_guard<std::mutex> lock(mutex);
    orphaned.push_back(pointer);
  }

  static void on_status_trampoline(const char* status, void* userdata) noexcept;
  static void on_error_trampoline(const char* error_json, void* userdata) noexcept;

  static std::shared_ptr<ClientImpl> from_userdata(void* userdata) {
    if (userdata == nullptr) {
      return nullptr;
    }
    return static_cast<Context*>(userdata)->impl.lock();
  }

  std::string model_;
  std::string api_url_;
  bool local_ = false;

  /// At most one of these: whichever constructor was used.
  std::optional<std::string> api_key_;
  std::optional<std::string> jwt_;

  Dispatcher dispatcher_;
  Handlers<Status> status_handlers_;
  Handlers<const ReactorError&> error_handlers_;

  /// Guards the handle, the context, the token and the pending map.
  mutable std::mutex mutex_;

  ReactorHandle* handle_ = nullptr;
  Context* context_ = nullptr;

  /// Calls whose completion has not fired, owned here rather than by the
  /// trampoline so that teardown can settle them.
  std::map<std::uint64_t, std::unique_ptr<Pending>> pending_;
  std::map<Pending*, std::uint64_t> pending_ids_;
  std::uint64_t next_pending_id_ = 1;
};

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

Subscription Reactor::on_error(std::function<void(const ReactorError&)> handler) {
  const std::uint64_t id = impl_->error_handlers().add(std::move(handler));
  return Subscription{[weak = std::weak_ptr<detail::ClientImpl>{impl_}, id] {
    if (const auto impl = weak.lock()) {
      impl->error_handlers().remove(id);
    }
  }};
}

}  // namespace reactor
