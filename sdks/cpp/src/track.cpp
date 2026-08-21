// `Track`, `TrackList`, and everything the client does on their behalf.
//
// The refusals are the point of this file. The native layer is permissive: a
// frame handler on a sendonly track simply never fires, and a track name the
// session never declared reaches the FFI, finds nothing, and returns. Either one
// looks exactly like a model that sends nothing, which is the most expensive
// thing to debug from the outside — so both throw here instead.

#include "reactor/track.hpp"

#include <algorithm>
#include <string>
#include <utility>
#include <vector>

#include "detail/client_impl.hpp"
#include "detail/ffi.hpp"
#include "detail/log.hpp"
#include "detail/strings.hpp"
#include "reactor/errors.hpp"
#include "reactor/json.hpp"

namespace reactor {

// ── Spellings ────────────────────────────────────────────────────────────────

std::string_view to_string(TrackKind kind) noexcept {
  return kind == TrackKind::Audio ? "audio" : "video";
}

std::string_view to_string(TrackDirection direction) noexcept {
  return direction == TrackDirection::SendOnly ? "sendonly" : "recvonly";
}

std::optional<TrackKind> track_kind_from_string(std::string_view text) noexcept {
  if (text == "video") {
    return TrackKind::Video;
  }
  if (text == "audio") {
    return TrackKind::Audio;
  }
  // A kind this build does not know is not something to guess at: a track the SDK
  // cannot classify is one it must not claim to understand.
  return std::nullopt;
}

std::optional<TrackDirection> track_direction_from_string(std::string_view text) noexcept {
  if (text == "sendonly") {
    return TrackDirection::SendOnly;
  }
  if (text == "recvonly") {
    return TrackDirection::RecvOnly;
  }
  return std::nullopt;
}

namespace {

/// The declared names, as `"a", "b", "c"`, for an error message.
std::string quoted_list(const std::vector<std::string>& names) {
  if (names.empty()) {
    return "none — the session has not declared its tracks yet";
  }
  std::string text;
  for (std::size_t index = 0; index < names.size(); ++index) {
    if (index != 0) {
      text += ", ";
    }
    text += '"';
    text += names[index];
    text += '"';
  }
  return text;
}

}  // namespace

// ── Track ────────────────────────────────────────────────────────────────────

std::shared_ptr<detail::ClientImpl> Track::client(const char* action) const {
  auto client = client_.lock();
  if (!client) {
    throw InvalidStateError{std::string{"cannot "} + action + " on track \"" + name_ +
                            "\": the client it belongs to has been destroyed"};
  }
  return client;
}

std::optional<TrackKind> Track::kind() const {
  const auto declared = client("read the kind")->declared(name_);
  return declared ? std::optional<TrackKind>{declared->kind} : std::nullopt;
}

std::optional<TrackDirection> Track::direction() const {
  const auto declared = client("read the direction")->declared(name_);
  return declared ? std::optional<TrackDirection>{declared->direction} : std::nullopt;
}

std::optional<std::string> Track::mid() const { return client("read the mid")->track_mid(name_); }

bool Track::paused() const { return client("read the paused state")->track_paused(name_); }

Subscription Track::on_frame(std::function<void(const VideoFrame&)> handler) {
  return client("receive frames")->add_video_handler(name_, std::move(handler));
}

Subscription Track::on_audio(std::function<void(const AudioFrame&)> handler) {
  return client("receive audio")->add_audio_handler(name_, std::move(handler));
}

// ── Sending ──────────────────────────────────────────────────────────────────

bool Track::published() const { return client("read the published state")->is_published(name_); }

std::future<void> Track::publish() {
  auto op = std::make_unique<detail::PendingVoid>();
  op->operation = "publish_track";
  auto future = op->promise.get_future();
  client("publish")->begin_publish(std::move(op), name_);
  return future;
}

void Track::unpublish() { client("unpublish")->unpublish(name_); }

std::future<void> Track::pause() {
  auto op = std::make_unique<detail::PendingVoid>();
  op->operation = "pause_track";
  auto future = op->promise.get_future();
  client("pause")->begin_pause(std::move(op), name_);
  return future;
}

std::future<void> Track::resume() {
  auto op = std::make_unique<detail::PendingVoid>();
  op->operation = "resume_track";
  auto future = op->promise.get_future();
  client("resume")->begin_resume(std::move(op), name_);
  return future;
}

void Track::push_frame(Bytes bgra, std::uint32_t width, std::uint32_t height,
                       const FrameOptions& options) {
  client("push a frame")->push_video(name_, bgra, width, height, options);
}

void Track::push_audio(Samples pcm, std::uint32_t sample_rate, std::uint32_t channels) {
  client("push audio")->push_audio(name_, pcm, sample_rate, channels);
}

// ── TrackList ────────────────────────────────────────────────────────────────

TrackList TrackList::with_kind(TrackKind kind) const {
  std::vector<Track> kept;
  for (const auto& track : tracks_) {
    if (track.kind() == kind) {
      kept.push_back(track);
    }
  }
  return TrackList{std::move(kept)};
}

TrackList TrackList::with_direction(TrackDirection direction) const {
  std::vector<Track> kept;
  for (const auto& track : tracks_) {
    if (track.direction() == direction) {
      kept.push_back(track);
    }
  }
  return TrackList{std::move(kept)};
}

Track TrackList::one() const {
  if (tracks_.empty()) {
    throw NotFoundError{"no track matched this filter"};
  }
  if (tracks_.size() != 1) {
    std::vector<std::string> names;
    names.reserve(tracks_.size());
    for (const auto& track : tracks_) {
      names.push_back(track.name());
    }
    // Picking the first would answer a question the caller did not ask, and do it
    // silently. Naming them lets them ask for the one they meant.
    throw InvalidStateError{"one() matched " + std::to_string(tracks_.size()) + " tracks: " +
                            quoted_list(names) + ". Narrow the filter, or ask for one by name."};
  }
  return tracks_.front();
}

namespace detail {

// ── What the session declared ────────────────────────────────────────────────

std::vector<ClientImpl::Declared> ClientImpl::declared_tracks() const {
  std::uint64_t generation = 0;
  {
    const std::lock_guard<std::mutex> lock(media_mutex_);
    if (declared_cache_) {
      return *declared_cache_;
    }
    generation = declared_generation_;
  }

  OwnedString json;
  {
    const std::lock_guard<std::mutex> lock(mutex_);
    json = OwnedString{ffi().tracks(handle_)};
  }
  if (!json.has_value()) {
    // Only a null handle answers null. Not cached: the next read is after a
    // connect, and that is exactly when the answer changes.
    return {};
  }

  const Json parsed = Json::parse(json.view(), nullptr, /*allow_exceptions=*/false);
  if (!parsed.is_array()) {
    log_warn_once("tracks-unparseable",
                  "the session's declared tracks could not be read; treating the session as "
                  "having declared none");
    return {};
  }

  std::vector<Declared> declared;
  declared.reserve(parsed.size());
  for (const auto& entry : parsed) {
    if (!entry.is_object()) {
      continue;
    }
    const auto name = entry.value("name", std::string{});
    const auto kind = track_kind_from_string(entry.value("kind", std::string{}));
    const auto direction = track_direction_from_string(entry.value("direction", std::string{}));
    if (name.empty() || !kind || !direction) {
      // A track this build cannot classify is left out rather than guessed at. It
      // will still be visible as a name the caller can be told about.
      log_warn_once("track-unclassified-" + name,
                    "the session declared track \"" + name +
                        "\" with a kind or direction this SDK does not recognise; it will not "
                        "appear in tracks()");
      continue;
    }
    const bool already_seen =
        std::any_of(declared.begin(), declared.end(),
                    [&name](const Declared& seen) { return seen.name == name; });
    if (already_seen) {
      // Two tracks under one name is not something a session should declare, and
      // the first one wins — which is what collecting them by name used to do.
      log_warn_once("track-duplicate-" + name,
                    "the session declared more than one track called \"" + name +
                        "\"; only the first is visible");
      continue;
    }
    declared.push_back(Declared{name, *kind, *direction});
  }

  {
    const std::lock_guard<std::mutex> lock(media_mutex_);
    // Only if nothing invalidated the cache while this read was in flight. If
    // something did, this list is the older one and the caller still gets it —
    // answering the question that was asked — but it does not become the answer to
    // the next one.
    if (generation == declared_generation_) {
      declared_cache_ = declared;
    }
  }
  return declared;
}

namespace {

/// The declared track by that name, or null. Linear, over a list a session's worth
/// of tracks long.
const ClientImpl::Declared* find_declared(const std::vector<ClientImpl::Declared>& declared,
                                          const std::string& name) {
  const auto found =
      std::find_if(declared.begin(), declared.end(),
                   [&name](const ClientImpl::Declared& entry) { return entry.name == name; });
  return found == declared.end() ? nullptr : &*found;
}

/// The names, still in declaration order, for a message that lists them.
std::vector<std::string> names_of(const std::vector<ClientImpl::Declared>& declared) {
  std::vector<std::string> names;
  names.reserve(declared.size());
  for (const auto& entry : declared) {
    names.push_back(entry.name);
  }
  return names;
}

}  // namespace

std::vector<std::string> ClientImpl::declared_names() const { return names_of(declared_tracks()); }

std::optional<ClientImpl::Declared> ClientImpl::declared(const std::string& name) const {
  const auto tracks = declared_tracks();
  if (const Declared* found = find_declared(tracks, name)) {
    return *found;
  }
  return std::nullopt;
}

std::optional<std::string> ClientImpl::track_mid(const std::string& name) const {
  const std::lock_guard<std::mutex> lock(media_mutex_);
  const auto found = track_mids_.find(name);
  if (found == track_mids_.end()) {
    return std::nullopt;
  }
  return found->second;
}

bool ClientImpl::track_paused(const std::string& name) const {
  OwnedString json;
  {
    const std::lock_guard<std::mutex> lock(mutex_);
    json = OwnedString{ffi().paused_tracks(handle_)};
  }
  if (!json.has_value()) {
    return false;
  }
  const Json parsed = Json::parse(json.view(), nullptr, /*allow_exceptions=*/false);
  if (!parsed.is_array()) {
    return false;
  }
  return std::any_of(parsed.begin(), parsed.end(), [&name](const Json& entry) {
    return entry.is_string() && entry.get<std::string>() == name;
  });
}

TrackList ClientImpl::tracks() {
  std::vector<Track> tracks;
  // In declaration order, which is what makes tracks()[0] mean the same thing here
  // as in every other SDK.
  for (const auto& declared : declared_tracks()) {
    tracks.push_back(Track{weak_from_this(), declared.name});
  }
  return TrackList{std::move(tracks)};
}

Track ClientImpl::track(const std::string& name) {
  const auto tracks = declared_tracks();
  if (find_declared(tracks, name) != nullptr) {
    return Track{weak_from_this(), name};
  }

  if (tracks.empty()) {
    // Nothing declared yet, so there is nothing to contradict. A track asked for
    // this early is legitimate — the caller knows their model — and the refusals
    // that matter happen when a handler or a push is attempted.
    return Track{weak_from_this(), name};
  }

  throw NotFoundError{"this session declares no track called \"" + name + "\". It declares " +
                      quoted_list(names_of(tracks)) + "."};
}

// ── Registering for media ────────────────────────────────────────────────────

namespace {

/// Refuse a handler that could never fire.
///
/// The whole reason this function exists: the FFI would accept every one of these
/// and simply never call back, which is indistinguishable from a model that sends
/// nothing.
void require_receivable(const std::string& name, const std::optional<ClientImpl::Declared>& track,
                        TrackKind wanted, const char* other_method,
                        const std::vector<std::string>& declared_names) {
  if (!track) {
    if (declared_names.empty()) {
      throw InvalidStateError{
          "cannot receive on track \"" + name +
          "\" yet: the session has not declared its tracks. Register frame handlers "
          "after connect() resolves, when the model has said what it sends."};
    }
    throw NotFoundError{"this session declares no track called \"" + name + "\". It declares " +
                        quoted_list(declared_names) + "."};
  }

  if (track->direction != TrackDirection::RecvOnly) {
    throw InvalidStateError{"track \"" + name +
                            "\" is sendonly: this client sends on it, so no frame will ever "
                            "arrive. Use push_frame() to send, and drop the handler."};
  }

  if (track->kind != wanted) {
    throw InvalidStateError{
        "track \"" + name + "\" carries " + std::string{to_string(track->kind)} + ", not " +
        std::string{to_string(wanted)} + ". Use " + other_method + "() for it."};
  }
}

}  // namespace

Subscription ClientImpl::add_video_handler(const std::string& name,
                                           std::function<void(const VideoFrame&)> handler) {
  const auto tracks = declared_tracks();
  const Declared* found = find_declared(tracks, name);
  require_receivable(name, found == nullptr ? std::nullopt : std::optional<Declared>{*found},
                     TrackKind::Video, "on_audio", names_of(tracks));

  std::uint64_t id = 0;
  {
    const std::lock_guard<std::mutex> lock(media_mutex_);
    id = video_handlers_[name].add(std::move(handler));
  }
  return Subscription{[weak = weak_from_this(), name, id] {
    if (const auto self = weak.lock()) {
      const std::lock_guard<std::mutex> lock(self->media_mutex_);
      const auto entry = self->video_handlers_.find(name);
      if (entry != self->video_handlers_.end()) {
        entry->second.remove(id);
      }
    }
  }};
}

Subscription ClientImpl::add_audio_handler(const std::string& name,
                                           std::function<void(const AudioFrame&)> handler) {
  const auto tracks = declared_tracks();
  const Declared* found = find_declared(tracks, name);
  require_receivable(name, found == nullptr ? std::nullopt : std::optional<Declared>{*found},
                     TrackKind::Audio, "on_frame", names_of(tracks));

  std::uint64_t id = 0;
  {
    const std::lock_guard<std::mutex> lock(media_mutex_);
    id = audio_handlers_[name].add(std::move(handler));
  }
  return Subscription{[weak = weak_from_this(), name, id] {
    if (const auto self = weak.lock()) {
      const std::lock_guard<std::mutex> lock(self->media_mutex_);
      const auto entry = self->audio_handlers_.find(name);
      if (entry != self->audio_handlers_.end()) {
        entry->second.remove(id);
      }
    }
  }};
}

// ── Delivering media ─────────────────────────────────────────────────────────

void ClientImpl::deliver_video(const std::string& name, const VideoFrame& frame) {
  Handlers<const VideoFrame&>* handlers = nullptr;
  {
    const std::lock_guard<std::mutex> lock(media_mutex_);
    const auto found = video_handlers_.find(name);
    if (found != video_handlers_.end()) {
      // Safe to hold past the lock: entries are only ever added, so the object it
      // points at outlives this call. Removing a *handler* happens inside it,
      // under its own lock.
      handlers = &found->second;
    }
  }
  if (handlers == nullptr) {
    warn_about_unhandled(name);
    return;
  }
  // Inline, on the thread the library called on. That is the contract: while this
  // runs, the FFI keeps only the newest frame.
  handlers->invoke(frame);
}

void ClientImpl::deliver_audio(const std::string& name, const AudioFrame& frame) {
  Handlers<const AudioFrame&>* handlers = nullptr;
  {
    const std::lock_guard<std::mutex> lock(media_mutex_);
    const auto found = audio_handlers_.find(name);
    if (found != audio_handlers_.end()) {
      handlers = &found->second;
    }
  }
  if (handlers == nullptr) {
    warn_about_unhandled(name);
    return;
  }
  handlers->invoke(frame);
}

void ClientImpl::warn_about_unhandled(const std::string& name) {
  {
    // Checked before anything expensive: this runs per frame, and reading the
    // declared tracks parses JSON.
    const std::lock_guard<std::mutex> lock(media_mutex_);
    if (!warned_tracks_.insert(name).second) {
      return;
    }
  }

  if (name.empty()) {
    log_warn(
        "a frame arrived that could not be matched to any declared track (the transceiver "
        "was unresolved). It has been dropped.");
    return;
  }
  if (declared(name).has_value()) {
    log_warn("frames are arriving on declared track \"" + name +
             "\" with no handler registered; they are being dropped. Register one with "
             "reactor.track(\"" +
             name + "\").on_frame(...).");
    return;
  }
  log_warn("frames are arriving on track \"" + name +
           "\", which this session did not declare. They are being dropped.");
}

// ── Sending ──────────────────────────────────────────────────────────────────

bool ClientImpl::is_published(const std::string& name) const {
  const std::lock_guard<std::mutex> lock(media_mutex_);
  return published_.count(name) != 0;
}

bool ClientImpl::is_publishing(const std::string& name) const {
  const std::lock_guard<std::mutex> lock(media_mutex_);
  return publishing_.count(name) != 0;
}

namespace {

/// Refuse a push onto a track with no sender behind it yet.
///
/// Two states, two messages: a publish that was never asked for says to publish,
/// and one still in flight says to wait for it. Answering both with "publish() it
/// first" sends a caller who just did that round the loop again.
void require_published_state(const std::string& name, bool published, bool publishing) {
  if (published) {
    return;
  }
  if (publishing) {
    throw InvalidStateError{"track \"" + name +
                            "\" is still being published: the request has not been answered yet, "
                            "so there is no sender behind it. Wait for the publish() future "
                            "before pushing."};
  }
  throw InvalidStateError{"track \"" + name +
                          "\" is not published: publish() it first, or the frame is dropped "
                          "with nothing to send it."};
}

}  // namespace

void ClientImpl::require_published(const std::string& name) const {
  bool published = false;
  bool publishing = false;
  {
    const std::lock_guard<std::mutex> lock(media_mutex_);
    published = published_.count(name) != 0;
    publishing = publishing_.count(name) != 0;
  }
  require_published_state(name, published, publishing);
}

void ClientImpl::invalidate_declared() {
  const std::lock_guard<std::mutex> lock(media_mutex_);
  declared_cache_.reset();
  ++declared_generation_;
}

void ClientImpl::clear_published() {
  const std::lock_guard<std::mutex> lock(media_mutex_);
  published_.clear();
  // A publish still in flight when the session left ready is not going to put a
  // sender behind anything either.
  publishing_.clear();
}

ReactorHandle* ClientImpl::require_ready_handle(const char* action,
                                                const std::string& track_name) const {
  ReactorHandle* handle = nullptr;
  {
    const std::lock_guard<std::mutex> lock(mutex_);
    handle = handle_;
  }
  if (handle == nullptr) {
    throw InvalidStateError{std::string{"cannot "} + action + " track \"" + track_name +
                            "\": this client has not connected yet"};
  }
  const StaticString status{ffi().status(handle)};
  if (status_from_string(status.view()) != Status::Ready) {
    throw InvalidStateError{std::string{"cannot "} + action + " track \"" + track_name +
                            "\": the session is " + std::string{status.view()} +
                            ", not ready. Wait for connect() to resolve, or reconnect()."};
  }
  return handle;
}

namespace {

/// Refuse a send on a track that cannot send.
ClientImpl::Declared require_sendable(const std::string& name,
                                      const std::optional<ClientImpl::Declared>& track,
                                      const std::vector<std::string>& declared_names,
                                      const char* action) {
  if (!track) {
    if (declared_names.empty()) {
      throw InvalidStateError{std::string{"cannot "} + action + " track \"" + name +
                              "\" yet: the session has not declared its tracks."};
    }
    throw NotFoundError{"this session declares no track called \"" + name + "\". It declares " +
                        quoted_list(declared_names) + "."};
  }
  if (track->direction != TrackDirection::SendOnly) {
    // The FFI accepts this and does nothing with it, so a caller pushing at 30fps
    // sees a model receiving nothing and no reason why.
    throw InvalidStateError{"track \"" + name +
                            "\" is recvonly: the model sends on it and this client receives. "
                            "Use on_frame() to receive, not push_frame()."};
  }
  return *track;
}

}  // namespace

void ClientImpl::begin_publish(std::unique_ptr<Pending> op, const std::string& name) {
  try {
    const auto tracks = declared_tracks();
    const Declared* found = find_declared(tracks, name);
    require_sendable(name, found == nullptr ? std::nullopt : std::optional<Declared>{*found},
                     names_of(tracks), "publish");
    ReactorHandle* handle = require_ready_handle("publish", name);

    // In flight, not published: there is no sender behind the slot until the
    // request is answered, so a frame pushed in this window is refused with what to
    // wait for rather than taken and dropped.
    {
      const std::lock_guard<std::mutex> lock(media_mutex_);
      publishing_.insert(name);
    }

    auto* raw = track_pending(std::move(op));
    raw->on_success = [weak = weak_from_this(), name] {
      if (const auto self = weak.lock()) {
        const std::lock_guard<std::mutex> lock(self->media_mutex_);
        self->publishing_.erase(name);
        self->published_.insert(name);
      }
    };
    raw->on_failure = [weak = weak_from_this(), name] {
      if (const auto self = weak.lock()) {
        const std::lock_guard<std::mutex> lock(self->media_mutex_);
        self->publishing_.erase(name);
      }
    };
    ffi().publish_track(handle, name.c_str(), &completion_trampoline, raw);
  } catch (...) {
    op->fail(std::current_exception());
  }
}

void ClientImpl::unpublish(const std::string& name) {
  ReactorHandle* handle = nullptr;
  {
    const std::lock_guard<std::mutex> lock(mutex_);
    handle = handle_;
  }
  if (handle == nullptr) {
    // Nothing was ever published on a client with no handle, so there is nothing
    // to undo and nothing to complain about.
    return;
  }

  // The one sync call that reports failure by returning a heap error object.
  const OwnedString error{ffi().unpublish_track(handle, name.c_str())};
  if (error.has_value()) {
    // Left published on purpose: a failed unpublish that cleared the flag would be
    // unretryable, and the slot is still activated as far as the session knows.
    throw_error_payload(error.view(), "unpublish_track");
  }

  const std::lock_guard<std::mutex> lock(media_mutex_);
  published_.erase(name);
}

void ClientImpl::begin_pause(std::unique_ptr<Pending> op, const std::string& name) {
  try {
    if (!declared(name) && !declared_names().empty()) {
      throw NotFoundError{"this session declares no track called \"" + name + "\". It declares " +
                          quoted_list(declared_names()) + "."};
    }
    ReactorHandle* handle = require_ready_handle("pause", name);
    auto* raw = track_pending(std::move(op));
    ffi().pause_track(handle, name.c_str(), &completion_trampoline, raw);
  } catch (...) {
    op->fail(std::current_exception());
  }
}

void ClientImpl::begin_resume(std::unique_ptr<Pending> op, const std::string& name) {
  try {
    if (!declared(name) && !declared_names().empty()) {
      throw NotFoundError{"this session declares no track called \"" + name + "\". It declares " +
                          quoted_list(declared_names()) + "."};
    }
    ReactorHandle* handle = require_ready_handle("resume", name);
    auto* raw = track_pending(std::move(op));
    ffi().resume_track(handle, name.c_str(), &completion_trampoline, raw);
  } catch (...) {
    op->fail(std::current_exception());
  }
}

void ClientImpl::push_video(const std::string& name, Bytes bgra, std::uint32_t width,
                            std::uint32_t height, const Track::FrameOptions& options) {
  const auto tracks = declared_tracks();
  const Declared* found = find_declared(tracks, name);
  const Declared declared_track =
      require_sendable(name, found == nullptr ? std::nullopt : std::optional<Declared>{*found},
                       names_of(tracks), "push a frame into");

  if (declared_track.kind != TrackKind::Video) {
    throw InvalidStateError{"track \"" + name +
                            "\" carries audio, not video. Use push_audio() for it."};
  }

  require_published(name);

  const std::size_t expected =
      static_cast<std::size_t>(width) * static_cast<std::size_t>(height) * 4U;
  if (bgra.data == nullptr || bgra.size != expected) {
    // The FFI reads what it is told to read, so a wrong length here is a read past
    // the end of the caller's own buffer.
    throw BadRequestError{"a " + std::to_string(width) + "x" + std::to_string(height) +
                          " BGRA frame is " + std::to_string(expected) + " bytes; this one is " +
                          std::to_string(bgra.size) + "."};
  }

  ReactorHandle* handle = require_ready_handle("push a frame into", name);

  const std::uint8_t* tag = options.user_data.empty() ? nullptr : options.user_data.data;
  const auto tag_len = static_cast<std::uint32_t>(options.user_data.size);

  if (options.capture_time_us) {
    ffi().push_video_frame_with_metadata_at(handle, name.c_str(), bgra.data, width, height, tag,
                                            tag_len, *options.capture_time_us);
    return;
  }
  if (tag != nullptr) {
    ffi().push_video_frame_with_metadata(handle, name.c_str(), bgra.data, width, height, tag,
                                         tag_len);
    return;
  }
  ffi().push_video_frame(handle, name.c_str(), bgra.data, width, height);
}

void ClientImpl::push_audio(const std::string& name, Samples pcm, std::uint32_t sample_rate,
                            std::uint32_t channels) {
  const auto tracks = declared_tracks();
  const Declared* found = find_declared(tracks, name);
  const Declared declared_track =
      require_sendable(name, found == nullptr ? std::nullopt : std::optional<Declared>{*found},
                       names_of(tracks), "push audio into");

  if (declared_track.kind != TrackKind::Audio) {
    throw InvalidStateError{"track \"" + name +
                            "\" carries video, not audio. Use push_frame() for it."};
  }
  require_published(name);
  if (channels == 0) {
    throw BadRequestError{"channels must be at least 1"};
  }
  if (pcm.data == nullptr || pcm.size == 0 || pcm.size % channels != 0) {
    throw BadRequestError{
        "interleaved PCM must divide evenly by the channel count: " + std::to_string(pcm.size) +
        " samples across " + std::to_string(channels) + " channels."};
  }

  ReactorHandle* handle = require_ready_handle("push audio into", name);
  ffi().push_audio_frame(handle, name.c_str(), pcm.data,
                         static_cast<std::uint32_t>(pcm.size / channels), sample_rate, channels);
}

}  // namespace detail
}  // namespace reactor
