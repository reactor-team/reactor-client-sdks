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

  // ── What the session declared ──────────────────────────────────────────────

  /// A track as the runtime declared it.
  struct Declared {
    TrackKind kind = TrackKind::Video;
    TrackDirection direction = TrackDirection::RecvOnly;
  };

  /// The declared tracks, read from the session rather than cached.
  ///
  /// `reactor_tracks` answers `[]` before the session is accepted and again after
  /// teardown, which is exactly what a caller needs in order to tell "no tracks
  /// yet" from "no track by that name". Caching would lose that distinction, and
  /// it is renegotiated on a reconnect anyway.
  std::map<std::string, Declared> declared_tracks() const;

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

  Subscription add_video_handler(const std::string& name,
                                 std::function<void(const VideoFrame&)> handler);
  Subscription add_audio_handler(const std::string& name,
                                 std::function<void(const AudioFrame&)> handler);

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
    callbacks.on_track = &on_track_trampoline;
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
  static void on_track_trampoline(const char* name, const char* mid_or_null,
                                  void* userdata) noexcept;

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

  /// Say once that frames are arriving for a track nothing is listening to.
  ///
  /// A frame with no handler looks exactly like no frame at all, which is the
  /// hardest thing to debug from the outside — so an unrecognised name is worth a
  /// line, and a name nobody declared is worth a different one.
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

  Dispatcher dispatcher_;
  Handlers<Status> status_handlers_;
  Handlers<const ReactorError&> error_handlers_;
  Handlers<Track> track_handlers_;

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
  std::uint64_t next_pending_id_ = 1;
};

}  // namespace detail
}  // namespace reactor
