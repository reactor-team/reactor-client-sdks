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
#include <vector>

#include "reactor/errors.hpp"
#include "reactor/json.hpp"
#include "reactor/recording.hpp"
#include "reactor/stats.hpp"
#include "reactor/status.hpp"
#include "reactor/step.hpp"
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

/// Options for an independent API-key exchange.
struct FetchJwtOptions {
  std::string api_url{DEFAULT_API_URL};
  /// Accept a dev coordinator's self-signed certificate. This explicit exchange
  /// still mints a token, unlike a local Reactor connection, which skips auth.
  bool local = false;
  /// Unset, and with `sessions` unset too, mints an unscoped token. Set (even
  /// empty), it scopes the token to these models.
  std::optional<std::vector<std::string>> models;
  /// Session limits apply only when models or sessions is set.
  std::optional<std::uint32_t> max_sessions;
  /// 1–86400 seconds; validated by the coordinator.
  std::optional<std::uint32_t> max_session_duration_seconds;
  /// Token lifetime in seconds; clamped by the coordinator.
  std::optional<std::uint64_t> expires_after;
  /// Existing sessions to bind the token to, by id. Without `models` and
  /// `max_sessions`, the token can act only on these and cannot create new ones.
  /// Declared last, so an aggregate initializer written before this field
  /// existed keeps its meaning.
  std::optional<std::vector<std::string>> sessions = std::nullopt;
};

/// Exchange an API key without creating a client or session. The returned future
/// receives the JWT or a typed ReactorError. Dropping it does not cancel the
/// request; the FFI owns completion until the exchange finishes.
std::future<std::string> fetch_jwt(const ApiKey& key, const FetchJwtOptions& options = {});

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

  /// Use local development mode and skip API-key exchange. When api_url is
  /// still DEFAULT_API_URL, use LOCAL_API_URL; an explicit custom URL is kept.
  /// Also accepts the coordinator's self-signed certificate.
  bool local = false;

  /// Where control events run. Empty means the SDK's own dispatcher thread.
  Executor executor;

  /// Resume every recvonly track on connect and reconnect. Set false to leave
  /// recvonly tracks paused until the host resumes them itself. Appended after
  /// `executor` so an aggregate initializer written before this field existed
  /// — `Options{url, local, executor}` — still selects the executor rather
  /// than silently becoming the flag.
  bool auto_resume_tracks = true;

  /// How the session generates: `Streamed` over media tracks (the default), or
  /// `Stepped`, one `Reactor::step()` at a time. A stepped client fails
  /// `connect()` with `GenerationModeMismatchError` when the runtime does not
  /// start the session that way. Appended, like `auto_resume_tracks`, so an
  /// aggregate initializer written before it existed means what it meant.
  Generation generation = Generation::Streamed;

  /// Seconds a `step()` may take, generation included, before it fails with
  /// `RequestTimeoutError`. Stepped sessions only — the constructor throws
  /// `BadRequestError` for one set on a streamed client, or for a value that is
  /// not a finite number above zero. Unset keeps the library's default of 300.
  /// Brace-initialised so an aggregate initializer that stops before it does
  /// not trip -Wmissing-field-initializers in a consumer's -Werror build.
  std::optional<double> step_timeout_seconds{};
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
/// Returned by `upload_file` / `upload_bytes`. A parameter that takes one file is
/// handed to `send_command` as a named upload rather than embedded in the
/// arguments — the platform resolves the reference on its side, so the bytes
/// cross the wire once. A parameter that takes several — a list, or a field of an
/// object — has no named slot, so the reference goes inside the arguments
/// instead: `FileRef` converts to `Json` as the upload reference the platform
/// reads, so `Json::array({first, second})` is that list.
struct FileRef {
  std::string upload_id;
  std::string name;
  std::string mime_type;
  std::uint64_t size = 0;
};

/// `FileRef` → its upload reference, for a `FileRef` placed inside a `Json`.
void to_json(Json& json, const FileRef& ref);

/// A Reactor client: one session, and the tracks and commands on it.
///
/// Movable, not copyable — a session has one owner. Destroying it releases the
/// native handle; a client that was connected should `disconnect()` first, since
/// a creator that goes away without disconnecting leaves the session orphaned
/// and the next run cannot start until it clears.
class Reactor {
 public:
  /// A client without credentials, for Options::local connections.
  explicit Reactor(std::string model, Options options);

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

  /// The model capabilities as received from the coordinator. Delivered on the
  /// control-event executor, after the declared-track cache has been invalidated.
  Subscription on_capabilities_received(std::function<void(const Json&)> handler);

  /// The session id changed; nullopt means it was cleared. Delivered on the
  /// control-event executor. The callback payload is owned until handlers return.
  Subscription on_session_id_changed(
      std::function<void(const std::optional<std::string>&)> handler);

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
  //
  // By value, not const&, despite clang-tidy's own preference: these are
  // exported symbols, and changing a parameter's type changes the mangled
  // name. A host that upgrades this shared library without recompiling its
  // own binary would fail to resolve `send_command` at the next launch --
  // for the same released version number, since nothing here bumps it.
  // NOLINTNEXTLINE(performance-unnecessary-value-param)
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
  // See send_command's own comment on staying by-value: an exported symbol,
  // and the mangled name changes with the parameter type.
  // NOLINTNEXTLINE(performance-unnecessary-value-param)
  std::future<FileRef> upload_file(std::string path);

  /// Upload bytes the caller already holds.
  ///
  /// The same result as `upload_file`, for a caller with the bytes rather than a
  /// path — a frame just rendered, a buffer just decoded. `data` is borrowed for
  /// the call only.
  // NOLINTNEXTLINE(performance-unnecessary-value-param)
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

  // ── Stepped generation ─────────────────────────────────────────────────────

  /// How this client's sessions generate, as `Options::generation` chose.
  Generation generation() const noexcept;

  /// Run one step of a stepped session and resolve with what it produced.
  ///
  /// Before the model generates, the runtime applies `state` (each key as the
  /// model's `set_<key>` command), then `commands` in order, then every input
  /// frame pushed since the previous step — `Track::push_frame` does not send in
  /// a stepped session, it buffers for the next step. These are individual
  /// calls, not a transaction: one that fails does not undo the ones before it.
  ///
  /// The buffer holds at most one data-channel message's worth of frame bytes:
  /// pushing faster than steps run drops the oldest frames, across tracks. Once
  /// the session's tracks are known, a frame pushed to a track the session does
  /// not take as input is dropped with a warning. The frames buffered when a step
  /// goes out belong to it whatever its outcome: one that could not be sent,
  /// `MessageTooLargeError` included, drops them too, and nothing is resent. A
  /// step refused before it goes out keeps them for the next step.
  ///
  /// ```cpp
  /// auto camera = client.track("camera");
  /// camera.publish().get();
  /// camera.push_frame(reactor::Bytes{bgra.data(), bgra.size()}, width, height);
  /// const auto result = client.step({{"prompt", "a red fox"}},
  ///                                 {{"advance", {{"frames", 4}}}}).get();
  /// for (const auto& frame : result.track("main_video").frames) { … }
  /// ```
  ///
  /// Both arguments are optional: `client.step()` with nothing pushed runs the
  /// model once on its current state. Commands sent with `send_command` outside a
  /// step still work in a stepped session and change the state the next step
  /// uses.
  ///
  /// `state` is a JSON object or null (no state). Each command's `data` is an
  /// object or null. Files go in the way they do for `send_command`: upload them
  /// first, then put the `FileRef` in a command's data or in the state value of
  /// an upload-typed field, where it converts into its upload reference.
  ///
  /// A step that ran but that the model reported failing still resolves, with
  /// `StepResult::error` set; a step that did not run, or whose result was too
  /// large to send, fails the future.
  ///
  /// Fails with `InvalidStateError` on a streamed client or a session that is
  /// not `Ready`; `BadRequestError` for a state or command data that is not an
  /// object, or a command with no name; `StepRefusedError` when the runtime
  /// refuses the step (it checks the whole request first, so a refused step
  /// changed nothing); `StepNotReadyError` when the session cannot take a step
  /// yet (the step did not run); `StepCommandFailedError` when the handler of
  /// one of its state keys or commands fails (the calls before it stay applied,
  /// the step did not run); `StepSessionEndedError` when the session ends before
  /// the result is sent (whether the step ran is unknown);
  /// `StepResultTooLargeError` when its output does not fit in one message;
  /// `MessageTooLargeError` when the request does not (nothing was sent, and the
  /// pushed frames are dropped with it); `UnsupportedFrameEncodingError` for an
  /// output frame the library cannot decode; `RequestTimeoutError` after
  /// `Options::step_timeout_seconds`.
  ///
  /// The result is copied out of the native library once, on the library's
  /// completion thread, and owns its bytes.
  // By value, like send_command: an exported symbol whose mangled name is the
  // parameter types.
  // NOLINTNEXTLINE(performance-unnecessary-value-param)
  std::future<StepResult> step(Json state = nullptr, std::vector<StepCommand> commands = {});

  /// Run one step with frames of its own: `media` gives them by input track name,
  /// after the ones pushed to that track since the previous step. A frame is a
  /// `RawVideoFrame`, BGRA or RGB as `Track::push_frame` takes it, a
  /// `RawAudioFrame` of PCM for an audio track, or an `EncodedFrame`, which goes
  /// as it is and is decoded by the runtime before the step runs:
  ///
  /// ```cpp
  /// reactor::StepMedia media;
  /// for (const auto& packet : window) {   // keyframe first, in decode order
  ///   media["webcam"].push_back(
  ///       reactor::EncodedFrame{reactor::Bytes{packet.data(), packet.size()}, 1280, 720});
  /// }
  /// const auto result = client.step(nullptr, {}, media).get();
  /// ```
  ///
  /// The same as pushing them just before the step, so each track must be
  /// published first, and the same refusals apply, failing the future. They are
  /// pushed only once `state` and `commands` are known to be well formed, and
  /// only once every frame in `media` passes, so a step refused for any of those
  /// — a bad track or frame anywhere in `media` included — fails before any of its
  /// media is pushed, leaving the buffer as it was. A step whose
  /// encoded frames do not decode, or do not start at a keyframe, fails with
  /// `StepRefusedError`, naming the frame in `entry()`; so does a runtime that
  /// predates encoded frames.
  ///
  /// The frames are borrowed and pushed (their bytes copied) before this returns.
  // By value: see the overload above.
  // NOLINTNEXTLINE(performance-unnecessary-value-param)
  std::future<StepResult> step(Json state, std::vector<StepCommand> commands, StepMedia media);

  /// Drop every frame buffered for the next step, so that step runs without
  /// them. A no-op before a session exists and on a streamed client.
  void clear_step_inputs();

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
