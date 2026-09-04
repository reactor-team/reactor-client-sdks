// The client.
//
//     #include <reactor/reactor.hpp>
//
//     reactor::Reactor r{"reactor/helios", reactor::ApiKey{std::getenv("REACTOR_API_KEY")}};
//
//     auto status = r.on_status([](reactor::Status s) {
//       std::cout << reactor::to_string(s) << '\n';
//     });
//
//     r.connect().get();          // throws the typed error on failure
//     …
//     r.disconnect().get();
//
// A model name is `owner/name`. A bare name resolves under `reactor/`, so it
// works by luck of ownership and answers 403 for anyone else's model.
#pragma once

#include <cstdint>
#include <functional>
#include <future>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <string_view>

#include "reactor/errors.hpp"
#include "reactor/json.hpp"
#include "reactor/recording.hpp"
#include "reactor/stats.hpp"
#include "reactor/status.hpp"
#include "reactor/subscription.hpp"
#include "reactor/track.hpp"

namespace reactor {

namespace detail {
/// The client's state, defined in reactor.cpp.
///
/// Not a nested private type: the C callbacks the library holds have to reach it,
/// and a nested private one would put them inside the class or outside its access.
class ClientImpl;
}  // namespace detail

/// Reactor's production coordinator.
inline constexpr std::string_view DEFAULT_API_URL = "https://api.reactor.inc";

/// A local runtime, for `reactor_runtime.serve` in a directory with a
/// `reactor.yaml`. Pair it with `Options::local`.
inline constexpr std::string_view LOCAL_API_URL = "http://localhost:8080";

/// An API key, exchanged for a session-scoped JWT when connecting.
///
/// Scoped to this model, so a leak is worth a handful of sessions rather than
/// everything the key can reach.
struct ApiKey {
  std::string value;
};

/// A JWT that was minted elsewhere.
///
/// For a server that already holds one, or one handed to a client by a backend
/// that owns the key.
struct Jwt {
  std::string value;
};

/// Where control-event handlers run.
///
/// Given one, the SDK hands it a callable per event instead of using its own
/// thread — for a host with a loop of its own (Qt, ASIO, a game loop) that would
/// rather own when handlers run. It is called from a library thread, so it must
/// be safe to call from any thread; everything it is handed is safe to run at any
/// time.
///
/// Futures do **not** go through it. A promise is settled on the FFI's own
/// completion thread, so `connect().get()` on the same thread that would have run
/// the executor cannot deadlock against it.
using Executor = std::function<void(std::function<void()>)>;

/// Everything about a client that is not the model or the credential.
struct Options {
  /// The coordinator. Defaults to production.
  std::string api_url{DEFAULT_API_URL};

  /// Accept a dev coordinator's self-signed certificate, and speak its
  /// local-development protocol.
  bool local = false;

  /// Where control events run. Empty means the SDK's own dispatcher thread.
  Executor executor;
};

/// What `connect` may adopt instead of creating.
struct ConnectOptions {
  /// Join a session that already exists, rather than creating one. This is how a
  /// second client attaches to the same session — see the multi-connection
  /// example.
  std::optional<std::string> session_id;

  /// Adopt a connection id a backend already registered for this session. Most
  /// callers leave this empty, as they do `session_id`.
  std::optional<std::uint32_t> connection_id;
};

/// A file the platform is holding, ready to be passed into a command.
///
/// Returned by `upload_file` / `upload_bytes`, and handed to `send_command` as a
/// named upload rather than embedded in the arguments — the platform resolves the
/// reference on its side, so the bytes cross the wire once.
struct FileRef {
  std::string upload_id;
  std::string name;
  std::string mime_type;
  std::uint64_t size = 0;
};

/// A Reactor client: one session, and the tracks and commands on it.
///
/// Movable, not copyable — a session has one owner. Destroying it releases the
/// native handle; a client that was connected should `disconnect()` first, since
/// a creator that goes away without disconnecting leaves the session orphaned
/// and the next run cannot start until it clears.
class Reactor {
 public:
  /// A client that will exchange `key` for a token when it connects.
  Reactor(std::string model, ApiKey key, Options options = {});

  /// A client that uses `jwt` as it is.
  Reactor(std::string model, Jwt jwt, Options options = {});

  ~Reactor();

  Reactor(Reactor&&) noexcept;
  Reactor& operator=(Reactor&&) noexcept;
  Reactor(const Reactor&) = delete;
  Reactor& operator=(const Reactor&) = delete;

  /// Create (or adopt) a session and bring up the transport.
  ///
  /// Resolves when the session is `Ready`. Throws the typed error on failure —
  /// `UnauthorizedError` for a token problem, `ConflictError` for a session left
  /// orphaned by a run that went away without disconnecting.
  std::future<void> connect(ConnectOptions options = {});

  /// Cycle the connection without ending the session.
  ///
  /// After a transient failure, or deliberately from `Ready`. Fails when there is
  /// no session to reconnect to. Note this is not `disconnect()` followed by
  /// `connect()`: that ends the session server-side and cannot be undone.
  std::future<void> reconnect();

  /// End the session server-side and tear down the transport.
  std::future<void> disconnect();

  /// Where the session is now.
  ///
  /// Readable before `connect()` — a client that never connected reports
  /// `Disconnected` rather than nothing.
  Status status() const;

  /// The session's id, once there is a session.
  std::optional<std::string> session_id() const;

  /// Called on every status change, with the new status.
  Subscription on_status(std::function<void(Status)> handler);

  /// Send a command and wait for its correlated reply.
  ///
  /// The reply is `{type, data}`, or **empty** when the handler ran and
  /// acknowledged the command without returning a message — an auto-generated
  /// `set_<field>` setter, for instance. Empty is not a failure and is not folded
  /// into one.
  ///
  /// Uploads are passed separately rather than embedded in `args`:
  ///
  ///     auto ref = reactor.upload_file("photo.jpg").get();
  ///     reactor.send_command("set_image", {}, {{"image", ref}}).get();
  ///
  /// The Python SDK finds a `FileRef` sitting in the arguments and pulls it out;
  /// C++ has no way to recognise one inside a `Json`, so it is named here instead.
  /// Explicit costs a few characters and cannot silently miss one.
  std::future<std::optional<Json>> send_command(std::string command, Json args = Json::object(),
                                                std::map<std::string, FileRef> uploads = {});

  /// The model's command schema, as an OpenAPI document.
  ///
  /// What to read when a command is rejected: it is the model's own account of
  /// what it accepts, which is more current than any documentation.
  std::future<Json> request_schema();

  /// Upload a local file, for passing into a command.
  ///
  /// Needs a ready session — the upload is created against it. `NotFoundError`
  /// when the path does not exist.
  std::future<FileRef> upload_file(std::string path);

  /// Upload bytes the caller already holds.
  ///
  /// The same result as `upload_file`, for a caller with the bytes rather than a
  /// path — a frame just rendered, a buffer just decoded. `data` is borrowed for
  /// the call only.
  std::future<FileRef> upload_bytes(Bytes data, std::string name, std::string mime_type);

  /// Ask for a clip of the last `duration_seconds` of the session.
  ///
  /// Resolves when the platform has accepted the request, which is not the same as
  /// the clip being ready — `Clip::download()` is what waits for that.
  std::future<Clip> request_clip(double duration_seconds);

  /// Ask for a recording of the whole session.
  std::future<Clip> request_recording();

  /// Called for each application message the model sends.
  ///
  /// The payload is the message as the model sent it: `{type, data}`. Model
  /// messages and platform messages are separate events because they are separate
  /// things — see `on_runtime_message`.
  Subscription on_message(std::function<void(const Json&)> handler);

  /// Called for each message from the runtime rather than from the model.
  ///
  /// Platform-level: session lifecycle notices, clip readiness. A caller reading
  /// only `on_message` never has to filter these out of it.
  Subscription on_runtime_message(std::function<void(const Json&)> handler);

  /// The track called `name`.
  ///
  /// How an app that knows its model asks: `reactor.track("main_video")`. Throws
  /// `NotFoundError`, listing what the session *does* declare, when the name is
  /// not among them — the FFI would accept it and then do nothing, which is the
  /// same thing a caller sees when a model sends nothing at all.
  ///
  /// Before the session has declared anything, any name is allowed: there is
  /// nothing yet to contradict, and the refusals that matter happen when a handler
  /// is registered or a frame is pushed.
  Track track(const std::string& name);

  /// Every track the session declared.
  ///
  /// For discovery. `tracks().with_kind(...)` and `.with_direction(...)` chain in
  /// either order, and `.one()` insists there is exactly one.
  TrackList tracks();

  /// Bounds for the connection's bitrate, in bits per second. A field left
  /// unset keeps the WebRTC default for that bound.
  struct Bitrate {
    /// A floor for the congestion controller; it will not drop below this even
    /// on a poor estimate. A floor above what the link can sustain trades
    /// graceful degradation for a fixed send rate, so choose it deliberately.
    std::optional<std::int32_t> min_bps;
    /// The initial encoder target. WebRTC starts at ~300 kbps and ramps, which
    /// is visible as a few seconds of soft video.
    std::optional<std::int32_t> start_bps;
    /// A ceiling on the whole connection.
    std::optional<std::int32_t> max_bps;
  };

  /// Bound what this connection may allocate.
  ///
  /// There are two bitrate ceilings and they are conjunctive — the lower one
  /// wins. This is the connection-wide one, which bounds the congestion
  /// controller's whole budget. `Track::set_bitrate` bounds one sender's share
  /// of it, and that is the one that lifts WebRTC's 2.5 Mbps video default:
  ///
  /// ```cpp
  /// reactor::Reactor::Bitrate budget;
  /// budget.start_bps = 4'000'000;
  /// budget.max_bps = 12'000'000;
  /// reactor.set_bitrate(budget).get();
  ///
  /// reactor::Track::Bitrate cap;
  /// cap.max_bps = 8'000'000;
  /// reactor.track("camera").set_bitrate(cap).get();
  /// ```
  ///
  /// Raising `max_bps` alone will not make a video track exceed 2.5 Mbps.
  ///
  /// Throws on a session that is not `Ready`. The bounds outlive a reconnect.
  std::future<void> set_bitrate(Bitrate bounds);

  /// A statistics snapshot for the live connection.
  ///
  /// RTT, jitter, packet loss, bitrates, and the engine's per-stream counters:
  ///
  /// ```cpp
  /// const auto stats = reactor.get_stats().get();
  /// if (stats.rtt_ms) {
  ///   std::cout << *stats.rtt_ms << " ms\n";
  /// }
  /// ```
  ///
  /// The two measured bitrates are derived against the previous call, so the
  /// first call after connecting leaves them empty, as does a call made less than
  /// 200 ms after the last one. Everything else is on every call. For a continuous
  /// reading, poll — a couple of seconds apart is the interval the browser SDK's
  /// own `statsUpdate` uses.
  ///
  /// Throws `InvalidStateError` unless the session is `Ready`: a snapshot of
  /// zeroes cannot be told from a connection carrying nothing. Asynchronous
  /// rather than a plain getter because the engine collects a report on its own
  /// thread and waits for it — see `stats.hpp` for what the report does and does
  /// not carry.
  std::future<ConnectionStats> get_stats();

  /// Called as each incoming track is received, with the track itself.
  Subscription on_track(std::function<void(Track)> handler);

  /// Called when the session reports a failure that no call was waiting on — a
  /// transport that dropped, a session the platform ended.
  ///
  /// The payload is a `ReactorError`, the same type a failed call throws. Match
  /// on `code()`, or catch a subclass by taking a `const NetworkError&` after a
  /// `dynamic_cast`; `recoverable()` is the property to branch on when the
  /// specific code does not matter.
  Subscription on_error(std::function<void(const ReactorError&)> handler);

 private:
  /// Shared, not unique, for two reasons: the callbacks the library holds capture
  /// it *weakly*, so a handler parked on a capture thread cannot keep the session
  /// open; and moving the `Reactor` cannot move the state, so the `userdata`
  /// pointer the library was given stays valid.
  std::shared_ptr<detail::ClientImpl> impl_;
};

/// The engine's monotonic clock, in microseconds.
///
/// The epoch a frame's capture time is read in. Read it once per unit of produced
/// media and stamp every track with that one value: tracks are synchronised by
/// sharing a capture time, not by reaching the encoder at the same moment.
/// Unrelated to the system clock — a UNIX timestamp is not a substitute.
std::int64_t time_micros() noexcept;

}  // namespace reactor
