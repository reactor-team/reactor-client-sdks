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
  OwnedString json;
  {
    const std::lock_guard<std::mutex> lock(mutex_);
    json = OwnedString{ffi().tracks(handle_)};
  }
  if (!json.has_value()) {
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

}  // namespace detail
}  // namespace reactor
