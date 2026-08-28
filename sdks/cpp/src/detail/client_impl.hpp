// The client's state: everything `Reactor` is a handle onto.
//
// A header rather than a block inside reactor.cpp because more than one
// translation unit needs the type — `Track` reaches the client to read what the
// session declared, and to register a frame handler on it. Still private: no
// public header includes this, so the C ABI and this layout stay out of a
// consumer's translation units.
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <future>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include "detail/dispatcher.hpp"
#include "detail/ffi.hpp"
#include "detail/handlers.hpp"
#include "detail/strings.hpp"
#include "reactor/errors.hpp"
#include "reactor/json.hpp"
#include "reactor/reactor.hpp"
#include "reactor/recording.hpp"
#include "reactor/track.hpp"

namespace reactor {

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
    if (!claim()) {
      return;
    }
    if (on_success) {
      try {
        on_success();
      } catch (...) {  // NOLINT(bugprone-empty-catch)
        // As for on_failure below: bookkeeping that throws must not replace the
        // answer the caller is waiting for.
      }
    }
    deliver(std::move(result));
  }

  void fail(const std::exception_ptr& error) {
    if (!claim()) {
      return;
    }
    if (on_failure) {
      try {
        on_failure();
      } catch (...) {  // NOLINT(bugprone-empty-catch)
        // A rollback that throws must not replace the failure the caller is about
        // to be told about, which is the one that actually explains what happened.
      }
    }
    deliver_error(error);
  }

  /// Which call this is, for an error payload that does not name one.
  std::string operation;

  /// Run when this call succeeds, before the caller hears about it.
  ///
  /// The publish path needs it: a track counts as published once the request is
  /// answered, and not before — while it is in flight there is no sender behind the
  /// slot, so a frame pushed then would be taken by the FFI and dropped.
  std::function<void()> on_success;

  /// Run when this call fails, before the caller hears about it.
  ///
  /// The other half of the same bookkeeping: a publish that was refused leaves the
  /// track in neither state.
  std::function<void()> on_failure;

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

/// A call whose answer is a JSON document: request_schema.
class PendingJson final : public Pending {
 public:
  std::promise<Json> promise;

 private:
  void deliver(Json result) override { promise.set_value(std::move(result)); }
  void deliver_error(const std::exception_ptr& error) override { promise.set_exception(error); }
};

/// A call whose answer may legitimately be nothing: send_command.
///
/// A handler that ran and returned no message is a success with no value, which is
/// a different thing from a failure — and folding the two together is how a caller
/// ends up treating a working setter as a broken one.
class PendingOptionalJson final : public Pending {
 public:
  std::promise<std::optional<Json>> promise;

 private:
  void deliver(Json result) override {
    if (result.is_null() || (result.is_object() && result.empty())) {
      promise.set_value(std::nullopt);
      return;
    }
    promise.set_value(std::move(result));
  }
  void deliver_error(const std::exception_ptr& error) override { promise.set_exception(error); }
};

/// A call that answers with a clip.
class PendingClip final : public Pending {
 public:
  std::promise<Clip> promise;

  /// The client the clip should be able to reach, so `download()` can bound its
  /// wait on the session still being alive.
  std::weak_ptr<ClientImpl> client;

 private:
  void deliver(Json result) override;
  void deliver_error(const std::exception_ptr& error) override { promise.set_exception(error); }
};

/// A download in flight: the promise, plus the progress callback it has to keep
/// alive for the length of the transfer.
class PendingDownload final : public Pending {
 public:
  std::promise<void> promise;
  std::function<void(std::uint32_t, std::uint32_t)> on_progress;

  /// What the FFI is handed for both callbacks, and all it is handed.
  ///
  /// A download is documented as outliving the handle it was given: neither the
  /// progress callback nor the completion is bounded by `reactor_destroy`, because
  /// the work runs on a task nothing here can cancel. So the pointer that detached
  /// task holds has to survive this client, and a raw `PendingDownload*` from the
  /// pending map does not — teardown frees that. The ticket survives; what it
  /// names may not, and a lock that fails is how a callback finds out.
  struct Ticket {
    std::weak_ptr<PendingDownload> download;
  };

  /// The C progress callback. `userdata` is a `Ticket`, borrowed.
  static void progress_trampoline(std::uint32_t done, std::uint32_t total, void* userdata) noexcept;

  /// The C completion. `userdata` is a `Ticket`, and this call owns it.
  ///
  /// Its own, not the shared `completion_trampoline`: that one reads `userdata` as
  /// a `Pending*` and reclaims it from the pending map, which is exactly the
  /// ownership a download cannot have.
  static void completion_trampoline(int ok, const char* result_json, const char* error_json,
                                    void* userdata) noexcept;

 private:
  void deliver(Json /*result*/) override { promise.set_value(); }
  void deliver_error(const std::exception_ptr& error) override { promise.set_exception(error); }
};

/// A call that answers with an uploaded file's reference.
class PendingFileRef final : public Pending {
 public:
  std::promise<FileRef> promise;

 private:
  void deliver(Json result) override {
    FileRef ref;
    if (result.is_object()) {
      // Any of these throws if the field is present with the wrong type — a size
      // that arrived as a string, say. Caught here because `settle` has already
      // claimed this operation: a throw from `deliver` makes the trampoline's
      // fallback `fail` a no-op, and the caller is left holding a promise nobody
      // can fulfil, which surfaces as future_error(broken_promise) instead of the
      // typed decode failure this SDK documents.
      try {
        ref.upload_id = result.value("upload_id", std::string{});
        ref.name = result.value("name", std::string{});
        ref.mime_type = result.value("mime_type", std::string{});
        ref.size = result.value("size", std::uint64_t{0});
      } catch (const std::exception& error) {
        promise.set_exception(
            as_exception_ptr(ErrorDetails{std::string{codes::DECODE_FAILED},
                                          std::string{"the upload's reply could not be read ("} +
                                              error.what() + "): " + result.dump(),
                                          false,
                                          {},
                                          "upload_file",
                                          {},
                                          {}}));
        return;
      }
    }
    if (ref.upload_id.empty()) {
      // The upload reported success and gave nothing to refer to it by, which no
      // command could use. A decode failure, not a silent empty reference.
      promise.set_exception(as_exception_ptr(
          ErrorDetails{std::string{codes::DECODE_FAILED},
                       "the upload succeeded but returned no upload_id: " + result.dump(),
                       false,
                       {},
                       "upload_file",
                       {},
                       {}}));
      return;
    }
    promise.set_value(std::move(ref));
  }
  void deliver_error(const std::exception_ptr& error) override { promise.set_exception(error); }
};

// ── The client's state ───────────────────────────────────────────────────────

/// A connect waiting on a token. Defined in reactor.cpp, where the exchange runs.
struct TokenExchange;

/// Fail a watched exchange's caller, on the way out.
///
/// Defined next to `TokenExchange` rather than here, because teardown only knows
/// the type by name — which is the point: the exchange belongs to the connect
/// path, and this file only has to be able to settle one.
void settle_exchange_as_aborted(TokenExchange& exchange);

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

  /// What a token was minted for.
  ///
  /// A session-scoped token cannot reach a session it did not create, so the scope
  /// depends on the *call* rather than on the client: creating a session wants a
  /// model-scoped token — a leak is then worth a handful of sessions on one model
  /// rather than everything the key can reach — while adopting one created
  /// elsewhere needs the broader token. A token minted for one connect is
  /// therefore not necessarily right for the next.
  enum class TokenScope : std::uint8_t { None, ThisModel, Unscoped };

  void set_api_key(std::string key) { api_key_ = std::move(key); }

  void set_jwt(std::string jwt) {
    // Under the lock too. Only the constructor calls this today, where nothing else
    // can see the object yet — but that is a fact about the caller, and the next
    // caller will not know it.
    const std::lock_guard<std::mutex> lock(mutex_);
    jwt_ = std::move(jwt);
    // A token the caller supplied is theirs, and its scope is not ours to reason
    // about: whatever it can reach, it can reach.
    minted_for_ = TokenScope::Unscoped;
    caller_supplied_jwt_ = true;
  }

  /// Take a token this client minted, and drop the handle if it changed.
  ///
  /// The native client is handed its token at creation, so a new one only reaches
  /// it through a new handle.
  void adopt_minted_token(std::string jwt, TokenScope scope) {
    bool changed = false;
    {
      const std::lock_guard<std::mutex> lock(mutex_);
      // Compared under the lock rather than before it. This runs on whichever FFI
      // thread answered the token request, while another connect may be reading or
      // replacing the same optional — and an unlocked read of a string someone
      // else is assigning is a race inside the string's own buffer, not merely a
      // stale answer.
      changed = !jwt_.has_value() || *jwt_ != jwt;
      jwt_ = std::move(jwt);
      minted_for_ = scope;
    }
    if (changed) {
      destroy_handle();
    }
  }

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

  // ── What the session declared ──────────────────────────────────────────────

  /// A track as the runtime declared it, under the name it declared it with.
  struct Declared {
    std::string name;
    TrackKind kind = TrackKind::Video;
    TrackDirection direction = TrackDirection::RecvOnly;
  };

  /// The declared tracks, in the order the session declared them.
  ///
  /// A sequence and not a map, deliberately: `tracks()` is indexed by position and
  /// every SDK promises that position is the declaration order — collecting them
  /// by name sorts them alphabetically and silently renumbers what `tracks()[0]`
  /// means. Lookups here are linear over a handful of entries, which is what a
  /// session declares.
  ///
  /// Read from the session rather than cached: `reactor_tracks` answers `[]`
  /// before the session is accepted and again after teardown, which is exactly
  /// what a caller needs in order to tell "no tracks yet" from "no track by that
  /// name". Caching would lose that distinction, and it is renegotiated on a
  /// reconnect anyway.
  std::vector<Declared> declared_tracks() const;

  /// The declared names, in declaration order, for an error message that lists
  /// what the caller could have asked for instead.
  std::vector<std::string> declared_names() const;

  std::optional<Declared> declared(const std::string& name) const;

  std::optional<std::string> track_mid(const std::string& name) const;
  bool track_paused(const std::string& name) const;

  /// The tracks as `Track` handles.
  TrackList tracks();

  /// A `Track` for `name`, or a thrown `NotFoundError` naming what does exist.
  Track track(const std::string& name);

  Handlers<Track>& track_handlers() { return track_handlers_; }
  Handlers<const Json&>& message_handlers() { return message_handlers_; }
  Handlers<const Json&>& runtime_message_handlers() { return runtime_message_handlers_; }

  // ── Commands, messages, uploads ────────────────────────────────────────────

  void begin_send_command(std::unique_ptr<Pending> op, const std::string& command, const Json& args,
                          const std::map<std::string, FileRef>& uploads);
  void begin_request_schema(std::unique_ptr<Pending> op);
  void begin_upload_file(std::unique_ptr<Pending> op, const std::string& path);
  void begin_upload_bytes(std::unique_ptr<Pending> op, Bytes data, const std::string& name,
                          const std::string& mime_type);

  // ── Recording ──────────────────────────────────────────────────────────────

  void begin_request_clip(std::unique_ptr<Pending> op, double duration_seconds);
  void begin_request_recording(std::unique_ptr<Pending> op);
  void begin_download(std::unique_ptr<Pending> op, const std::string& playlist_url,
                      const std::string& path, double predicted_ready_at_ms,
                      std::optional<double> ready_timeout_seconds);

  /// Whether this client still has a session that could produce a clip.
  bool has_live_session() const;

  Subscription add_video_handler(const std::string& name,
                                 std::function<void(const VideoFrame&)> handler);
  Subscription add_audio_handler(const std::string& name,
                                 std::function<void(const AudioFrame&)> handler);

  // ── Sending ────────────────────────────────────────────────────────────────

  bool is_published(const std::string& name) const;

  /// Whether a publish for this track has been asked for and not yet answered.
  bool is_publishing(const std::string& name) const;

  /// Throw unless there is a sender behind this track's slot, saying which of the
  /// two reasons there is not.
  void require_published(const std::string& name) const;

  void begin_publish(std::unique_ptr<Pending> op, const std::string& name);
  void unpublish(const std::string& name);
  void begin_pause(std::unique_ptr<Pending> op, const std::string& name);
  void begin_resume(std::unique_ptr<Pending> op, const std::string& name);
  void begin_set_bitrate(std::unique_ptr<Pending> op, const Reactor::Bitrate& bounds);
  void begin_set_track_bitrate(std::unique_ptr<Pending> op, const std::string& name,
                               const Track::Bitrate& bounds);

  void push_video(const std::string& name, Bytes bgra, std::uint32_t width, std::uint32_t height,
                  const Track::FrameOptions& options);
  void push_audio(const std::string& name, Samples pcm, std::uint32_t sample_rate,
                  std::uint32_t channels);

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
    Pending* raw = track_pending(std::move(op));
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
    Pending* raw = track_pending(std::move(op));
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
    callbacks.on_track = &on_track_trampoline;
    callbacks.on_capabilities = &on_capabilities_trampoline;
    callbacks.on_message = &on_message_trampoline;
    callbacks.on_runtime_message = &on_runtime_message_trampoline;
    callbacks.on_frame = &on_frame_trampoline;
    callbacks.on_audio = &on_audio_trampoline;
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

 public:
  /// Watch a token exchange, so teardown can settle it.
  ///
  /// Not `track`: what that map holds is freed on teardown, which is safe only
  /// because `reactor_destroy` promises those completions are done. This one has
  /// no such promise — `reactor_fetch_jwt` takes no handle — so the object lives
  /// until its completion arrives or this client goes, whichever is first.
  void watch_exchange(std::shared_ptr<TokenExchange> exchange) {
    const std::lock_guard<std::mutex> lock(mutex_);
    TokenExchange* raw = exchange.get();
    exchanges_.emplace(raw, std::move(exchange));
  }

  /// Stop watching, because the completion arrived and owns it from here.
  void forget_exchange(TokenExchange* raw) {
    const std::lock_guard<std::mutex> lock(mutex_);
    exchanges_.erase(raw);
  }

  /// Watch a download, for the same reason and with the same shape.
  ///
  /// `reactor_download_clip`'s callbacks are not bounded by `reactor_destroy`
  /// either — the header says so — so the pending map, whose contents teardown
  /// frees, is the one place this must not live.
  void watch_download(std::shared_ptr<PendingDownload> download) {
    const std::lock_guard<std::mutex> lock(mutex_);
    PendingDownload* raw = download.get();
    downloads_.emplace(raw, std::move(download));
  }

  /// Stop watching, because the completion arrived and owns it from here.
  void forget_download(PendingDownload* raw) {
    const std::lock_guard<std::mutex> lock(mutex_);
    downloads_.erase(raw);
  }

 private:
  Pending* track_pending(std::unique_ptr<Pending> op) {
    op->owner = weak_from_this();
    const std::lock_guard<std::mutex> lock(mutex_);
    const std::uint64_t id = next_pending_id_++;
    Pending* raw = op.get();
    pending_ids_.emplace(raw, id);
    pending_.emplace(id, std::move(op));
    return raw;
  }

  // ── Teardown ───────────────────────────────────────────────────────────────

  /// Release the native handle, and with it the right to call back into us.
  ///
  /// Public because re-minting a token has to drop the handle: the native client
  /// is handed its token at creation, so a new one only reaches it through a new
  /// handle.
 public:
  void destroy_handle() {
    ReactorHandle* to_destroy = nullptr;
    Context* to_release = nullptr;
    std::vector<std::unique_ptr<Pending>> outstanding;
    std::vector<std::shared_ptr<TokenExchange>> exchanges;
    std::vector<std::shared_ptr<PendingDownload>> downloads;
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
      exchanges.reserve(exchanges_.size());
      for (auto& entry : exchanges_) {
        exchanges.push_back(std::move(entry.second));
      }
      exchanges_.clear();
      downloads.reserve(downloads_.size());
      for (auto& entry : downloads_) {
        downloads.push_back(std::move(entry.second));
      }
      downloads_.clear();
    }

    // 0 means quiescence: no callback is running and none will start, so the
    // context can go. -1 means one is still in flight and every pointer it holds
    // must stay valid — leaking is correct, freeing is a jump into freed memory.
    // A small permanent leak beats that every time.
    const int quiesced = to_destroy == nullptr ? 0 : ffi().destroy(to_destroy);

    for (auto& download : downloads) {
      // The transfer itself is not ours to stop and may well finish the file. What
      // is certain is that nothing is going to resolve this future, so it is
      // resolved here — and the object stays alive until the completion that still
      // points at it arrives and finds a weak reference that no longer locks.
      download->fail(as_exception_ptr(
          ErrorDetails{std::string{codes::ABORTED},
                       "the client was destroyed before this download finished. The download "
                       "itself is not bounded by the client and may still complete.",
                       false,
                       {},
                       "download_clip",
                       {},
                       {}}));
    }

    for (auto& exchange : exchanges) {
      // The request is still out there and its completion may still arrive, but it
      // will find a weak reference that no longer locks and leave everything
      // alone. What matters here is the caller: nothing else resolves this future.
      settle_exchange_as_aborted(*exchange);
    }

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

 private:
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
  static void on_track_trampoline(const char* name, const char* mid_or_null,
                                  void* userdata) noexcept;
  static void on_capabilities_trampoline(const char* caps_json, void* userdata) noexcept;
  static void on_message_trampoline(const char* msg_json, void* userdata) noexcept;
  static void on_runtime_message_trampoline(const char* msg_json, void* userdata) noexcept;

  /// Deliver a parsed message to `handlers`, on the dispatcher.
  void fire_message(Handlers<const Json&>& handlers, const char* msg_json);

  /// Forget what the session declared, so the next read asks again.
  void invalidate_declared();

  /// Forget which slots were published.
  ///
  /// Called the moment the status leaves `Ready`, on the library's own thread —
  /// not from the dispatcher, because a push racing the status change has to see
  /// the new answer, not the one the queue has not got to yet.
  void clear_published();

  /// The handle, or a thrown error naming what a caller has to do first.
  ReactorHandle* require_ready_handle(const char* action, const std::string& track_name) const;

  /// Media, and the reason these are separate from everything above: they run
  /// *inline*, on the thread the library called on, and never go near the
  /// dispatcher. Blocking here is the backpressure.
  static void on_frame_trampoline(const char* track_name, const std::uint8_t* data,
                                  std::uint32_t width, std::uint32_t height, std::uint64_t frame_id,
                                  std::uint64_t timestamp_us, const std::uint8_t* user_data,
                                  std::uint32_t user_data_len, void* userdata) noexcept;
  static void on_audio_trampoline(const char* track_name, const std::int16_t* samples,
                                  std::uint32_t num_samples, std::uint32_t sample_rate,
                                  std::uint32_t channels, void* userdata) noexcept;

  void deliver_video(const std::string& name, const VideoFrame& frame);
  void deliver_audio(const std::string& name, const AudioFrame& frame);

  /// Say once that frames are arriving for a track this session never declared.
  ///
  /// A frame nobody can route looks exactly like no frame at all, which is the
  /// hardest thing to debug from the outside. A *declared* track with no handler
  /// is silent: not caring about one of several outputs is a choice, and there is
  /// always a gap between connect() resolving and a handler being registered.
  void warn_about_unhandled(const std::string& name);

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
  TokenScope minted_for_ = TokenScope::None;
  bool caller_supplied_jwt_ = false;

  Dispatcher dispatcher_;
  Handlers<Status> status_handlers_;
  Handlers<const ReactorError&> error_handlers_;
  Handlers<Track> track_handlers_;
  Handlers<const Json&> message_handlers_;
  Handlers<const Json&> runtime_message_handlers_;

  /// Frame handlers, per track name.
  ///
  /// Guarded by their own mutex, not the one above: this is read on every frame,
  /// and a 30fps path has no business contending with `connect`. Entries are only
  /// ever added — removing a *handler* happens inside the `Handlers` object — so a
  /// pointer to one stays valid for the client's lifetime, which is what lets the
  /// delivery path drop the lock before invoking.
  mutable std::mutex media_mutex_;
  std::map<std::string, Handlers<const VideoFrame&>> video_handlers_;
  std::map<std::string, Handlers<const AudioFrame&>> audio_handlers_;

  /// Which sendonly slots are activated.
  ///
  /// The session does not record this: `publish_track` is a request and
  /// `unpublish_track` a notification, and neither leaves anything to query. So it
  /// is kept here — and **cleared whenever the status leaves `Ready`**, because a
  /// reconnect resumes recvonly tracks and nothing else. Remembering otherwise
  /// would let a push through onto a slot with no sender behind it, which is the
  /// silent failure this SDK exists to refuse.
  std::set<std::string> published_;

  /// Publishes asked for and not yet answered.
  ///
  /// Kept apart from `published_` on purpose: while the request is in flight there
  /// is no sender behind the slot yet, so a frame pushed now would be taken by the
  /// FFI and dropped — the silent failure this SDK exists to refuse. Counting it as
  /// published would make an in-flight publish indistinguishable from a live one;
  /// counting it as nothing would say "publish() it first" to a caller who just
  /// did. It is its own state, and the refusal says to await the future.
  std::set<std::string> publishing_;

  /// What the session declared, cached.
  ///
  /// Cached only because `push_frame` validates against it, and a JSON parse per
  /// frame at 30fps is not a validation, it is a cost. Invalidated on every status
  /// change, on new capabilities and on a new track — the three things that can
  /// change the answer — so `[]`-before-accepted still reads as `[]`.
  mutable std::optional<std::vector<Declared>> declared_cache_;

  /// Bumped by every invalidation, so a read that raced one does not overwrite it.
  ///
  /// The FFI read happens without the lock — it has to, a JSON parse is not
  /// something to hold a media mutex across — so an event can invalidate the cache
  /// while a read is in flight. Storing that read afterwards would put the *older*
  /// list back with nothing left to invalidate it, and newly declared tracks would
  /// stay invisible. The counter is what tells the writer its answer is stale.
  mutable std::uint64_t declared_generation_ = 0;

  /// The SDP media id per track, as tracks arrive. Renegotiated on a reconnect.
  std::map<std::string, std::string> track_mids_;

  /// Track names already complained about, so a 30fps mistake is one line.
  std::set<std::string> warned_tracks_;

  /// Guards the handle, the context, the token and the pending map.
  mutable std::mutex mutex_;

  ReactorHandle* handle_ = nullptr;
  Context* context_ = nullptr;

  /// Calls whose completion has not fired, owned here rather than by the
  /// trampoline so that teardown can settle them.
  std::map<std::uint64_t, std::unique_ptr<Pending>> pending_;
  std::map<Pending*, std::uint64_t> pending_ids_;
  /// Token exchanges in flight. Apart from `pending_` because their completions
  /// are not bounded by `reactor_destroy` — see `watch_exchange`.
  std::map<TokenExchange*, std::shared_ptr<TokenExchange>> exchanges_;
  /// Downloads in flight, held for the same reason as `exchanges_`: their
  /// callbacks outlive the handle, so teardown may settle them but must not free
  /// what the detached task still points at.
  std::map<PendingDownload*, std::shared_ptr<PendingDownload>> downloads_;
  std::uint64_t next_pending_id_ = 1;
};

}  // namespace detail
}  // namespace reactor
