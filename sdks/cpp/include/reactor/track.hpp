// A named media slot the model declared, and the frames that arrive on it.
//
//     auto video = reactor.track("main_video");     // by name, the way an app asks
//     auto frames = video.on_frame([&](const reactor::VideoFrame& frame) {
//       render(frame.bgra, frame.width, frame.height);
//     });
//
// One type for all four combinations of kind and direction, because the
// operations are the same operations. Ask for a track **by name** — listing and
// filtering is for discovering what a session declared, not for using it.
#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <future>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "reactor/subscription.hpp"

namespace reactor {

namespace detail {
class ClientImpl;
}  // namespace detail

/// A borrowed run of bytes.
///
/// What `std::span<const std::uint8_t>` would be, which C++17 does not have.
/// Deliberately not `std::basic_string_view<std::uint8_t>`: that needs
/// `char_traits<unsigned char>`, which the standard library provides only
/// temporarily and warns about, and these bytes are not text.
struct Bytes {
  const std::uint8_t* data = nullptr;
  std::size_t size = 0;

  bool empty() const noexcept { return size == 0; }
  const std::uint8_t* begin() const noexcept { return data; }
  const std::uint8_t* end() const noexcept { return data + size; }
};

/// A borrowed run of interleaved 16-bit PCM samples.
///
/// A length, not just a pointer, so `push_audio` can check it against the channel
/// count instead of trusting it — the FFI reads what it is told to read.
struct Samples {
  const std::int16_t* data = nullptr;
  std::size_t size = 0;

  bool empty() const noexcept { return size == 0; }
};

/// What flows on a track.
enum class TrackKind : std::uint8_t { Video, Audio };

/// Which way it flows, from this client's point of view.
enum class TrackDirection : std::uint8_t {
  /// This client sends; the model receives. Needs `publish()` first.
  SendOnly,
  /// The model sends; this client receives.
  RecvOnly,
};

std::string_view to_string(TrackKind kind) noexcept;
std::string_view to_string(TrackDirection direction) noexcept;

/// The wire spellings, or nothing for a value this build does not recognise.
std::optional<TrackKind> track_kind_from_string(std::string_view text) noexcept;
std::optional<TrackDirection> track_direction_from_string(std::string_view text) noexcept;

/// A decoded video frame.
///
/// **Borrowed for the duration of the handler and no longer.** The library frees
/// the pixels when the handler returns, so anything kept has to be copied — a
/// pointer stored here is a use-after-free that reproduces under load and not in
/// tests.
///
/// The handler runs **inline on the library's delivery thread**, deliberately.
/// Blocking in it is the backpressure: while it runs, the FFI keeps only the
/// newest frame and drops the ones in between. Handing frames to a queue of your
/// own trades a bounded drop for unbounded latency and memory.
struct VideoFrame {
  /// The track this arrived on. Every recvonly video track decodes into one
  /// callback, so on a session with several this is what tells them apart.
  std::string_view track_name;

  /// BGRA pixels: B, G, R, A per pixel, `width * height * 4` bytes.
  const std::uint8_t* bgra = nullptr;
  std::uint32_t width = 0;
  std::uint32_t height = 0;

  /// The sender's frame counter, or 0 when the frame carried no trailer.
  std::uint64_t frame_id = 0;

  /// The sender's capture time in microseconds, or 0 with no trailer. Read in the
  /// engine's clock (`reactor::time_micros()`), not the system's.
  std::uint64_t timestamp_us = 0;

  /// Whatever the sender tagged this frame with — bytes, not text, and between
  /// the sender and the model. Empty when the frame carried no trailer, and empty
  /// is the normal case for a model that does not tag. Borrowed like the pixels.
  Bytes user_data;

  /// `width * height * 4`, the readable length of `bgra`.
  std::size_t size_bytes() const noexcept { return static_cast<std::size_t>(width) * height * 4U; }

  /// Whether this frame carried a metadata trailer at all.
  bool has_metadata() const noexcept { return frame_id != 0 || timestamp_us != 0; }
};

/// A decoded audio frame: interleaved 16-bit PCM.
///
/// Borrowed and inline, exactly as `VideoFrame`. The audio queue is short and
/// keeps its backlog rather than dropping, because there the queue is the jitter
/// buffer and a hole in it is audible — so a slow handler here costs latency.
struct AudioFrame {
  std::string_view track_name;

  /// Interleaved samples: `num_samples` in total, across `channels`.
  const std::int16_t* samples = nullptr;
  std::uint32_t num_samples = 0;
  std::uint32_t sample_rate = 0;
  std::uint32_t channels = 0;

  /// Samples per channel — `num_samples / channels`, which is what a device wants.
  std::uint32_t frames() const noexcept { return channels == 0 ? 0 : num_samples / channels; }
};

/// A named media slot on the session.
///
/// A handle, not an owner: it holds the client weakly, so a track parked in a
/// capture thread cannot keep the session — and the native handle — alive for the
/// life of that thread.
class Track {
 public:
  /// The declared name. Never changes.
  const std::string& name() const noexcept { return name_; }

  /// `Video` or `Audio`, or nothing before the session has declared its tracks.
  std::optional<TrackKind> kind() const;

  /// `SendOnly` or `RecvOnly`, or nothing before the session has declared them.
  std::optional<TrackDirection> direction() const;

  /// The SDP media id, once the track has been received. Nothing until then.
  ///
  /// Read from the client rather than remembered here: it is reported as tracks
  /// arrive and is renegotiated on a reconnect.
  std::optional<std::string> mid() const;

  /// Whether this track is paused right now.
  ///
  /// Read from the session, not cached, so it stays right across a reconnect —
  /// recvonly tracks resume automatically once connected, and a `Track` holding a
  /// stale `true` would go on claiming otherwise.
  bool paused() const;

  /// Receive decoded video frames.
  ///
  /// Throws `InvalidStateError` on a sendonly track: the callback would never
  /// fire, and a handler that never runs is indistinguishable from a model that
  /// sends nothing. Throws on an audio track, which has `on_audio`.
  ///
  /// The handler runs inline on the library's thread. See `VideoFrame`.
  Subscription on_frame(std::function<void(const VideoFrame&)> handler);

  /// Receive decoded audio frames. Refuses the wrong kind or direction, as
  /// `on_frame` does.
  Subscription on_audio(std::function<void(const AudioFrame&)> handler);

  // ── Sending ────────────────────────────────────────────────────────────────

  /// Whether this sendonly slot is activated.
  ///
  /// Kept by the SDK rather than read back, because the session does not record
  /// it: `publish` is a control request and `unpublish` a notification, and
  /// neither leaves anything to query. **It is cleared whenever the status leaves
  /// `Ready`** — a reconnect resumes recvonly tracks and nothing else, so a slot
  /// published before one is not published after it.
  bool published() const;

  /// Activate the send slot, so the model has something to receive on.
  ///
  /// Publishing is what puts a sender behind the slot: pushing before it drops the
  /// frame, and this SDK refuses rather than letting it. Throws on a recvonly
  /// track, and on a session that is not `Ready`.
  std::future<void> publish();

  /// Deactivate the send slot.
  ///
  /// Synchronous — there is no round trip, only a local state change and a
  /// fire-and-forget notification. Throws when the notification could not be made;
  /// the track then stays published, so a retry is possible.
  void unpublish();

  /// Stop this track. Nothing is generated while paused, which on a video track
  /// is visible only as a frozen frame.
  std::future<void> pause();

  /// Start it again.
  std::future<void> resume();

  /// What a pushed frame carries besides its pixels.
  struct FrameOptions {
    /// Bytes the far end reads as this frame's metadata. Sent as-is — JSON,
    /// protobuf or anything else is between the caller and the model — and
    /// dropped silently by a peer that did not declare it reads them, so tagging
    /// is safe whatever the far end supports.
    Bytes user_data;

    /// The moment this frame was captured, read from `reactor::time_micros()`.
    ///
    /// Read it **once per unit of produced media** and give every track the same
    /// value: tracks are synchronised by sharing a capture time, not by reaching
    /// the encoder at the same moment. Left empty, the frame is stamped as it is
    /// pushed, so several tracks capturing one moment arrive microseconds apart.
    std::optional<std::int64_t> capture_time_us;
  };

  /// Push one BGRA frame into this track.
  ///
  /// `bgra` must hold exactly `width * height * 4` bytes — checked here, because
  /// the FFI reads what it is told to read and a wrong length is a read past the
  /// end of the caller's buffer.
  ///
  /// Throws `InvalidStateError` on a recvonly track, before `publish()`, or once
  /// the session has left `Ready`; `BadRequestError` on a buffer that does not
  /// match. Each of those reaches the FFI, finds nothing to do, and returns — so a
  /// caller pushing at 30fps would see a model receiving nothing and no reason
  /// why.
  void push_frame(Bytes bgra, std::uint32_t width, std::uint32_t height,
                  const FrameOptions& options = {});

  /// Push interleaved 16-bit PCM into this track.
  ///
  /// `sample_rate` must be 48000 and `channels` 1, which is what the source
  /// expects; `pcm.size` must divide evenly by `channels`.
  void push_audio(Samples pcm, std::uint32_t sample_rate = 48'000, std::uint32_t channels = 1);

 private:
  friend class Reactor;
  friend class TrackList;
  friend class detail::ClientImpl;

  Track(std::weak_ptr<detail::ClientImpl> client, std::string name)
      : client_(std::move(client)), name_(std::move(name)) {}

  /// The client, or a thrown `InvalidStateError` when it is gone.
  std::shared_ptr<detail::ClientImpl> client(const char* action) const;

  std::weak_ptr<detail::ClientImpl> client_;
  std::string name_;
};

/// The tracks a session declared, filterable.
///
/// For discovery — "what does this model have?" — and for the case where a caller
/// would rather not hardcode a name. Filters chain in either order:
///
///     auto out = reactor.tracks().with_direction(reactor::TrackDirection::RecvOnly);
///     auto only_video = out.with_kind(reactor::TrackKind::Video).one();
class TrackList {
 public:
  TrackList() = default;

  /// Only the tracks of this kind.
  TrackList with_kind(TrackKind kind) const;

  /// Only the tracks pointing this way.
  TrackList with_direction(TrackDirection direction) const;

  /// The one track in this list.
  ///
  /// Throws `NotFoundError` when the list is empty and `InvalidStateError` when it
  /// holds more than one — a filter that matched several and a caller that wanted
  /// one is a question with no answer, and picking the first would answer it
  /// wrongly and silently.
  Track one() const;

  bool empty() const noexcept { return tracks_.empty(); }
  std::size_t size() const noexcept { return tracks_.size(); }
  const Track& operator[](std::size_t index) const { return tracks_.at(index); }

  std::vector<Track>::const_iterator begin() const noexcept { return tracks_.begin(); }
  std::vector<Track>::const_iterator end() const noexcept { return tracks_.end(); }

 private:
  friend class Reactor;
  friend class detail::ClientImpl;

  explicit TrackList(std::vector<Track> tracks) : tracks_(std::move(tracks)) {}

  std::vector<Track> tracks_;
};

}  // namespace reactor
