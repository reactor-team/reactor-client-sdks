// Clips, and turning one into a file.
//
//     auto clip = reactor.request_clip(10.0).get();
//     clip.download("last-ten-seconds.mp4").get();
//
// Reactor does not host clips: `playlist_url` names a short-lived HLS playlist,
// and the fragments behind it have to be fetched and assembled. That assembly
// lives in the Rust core — the init segment is a comment line, a presigned
// segment rejects an Authorization header, and readiness is in media time — so
// this SDK carries no HTTP client and no playlist parser of its own.
#pragma once

#include <cstdint>
#include <functional>
#include <future>
#include <memory>
#include <optional>
#include <string>

namespace reactor {

namespace detail {
class ClientImpl;
class PendingClip;
}  // namespace detail

/// A clip or a full-session recording, once the platform has accepted the
/// request.
///
/// Accepted is not the same as ready: `download()` is what waits.
class Clip {
 public:
  /// The HLS media playlist. Short-lived, and on the coordinator or presigned
  /// elsewhere — which is why the token only ever goes to the former.
  const std::string& playlist_url() const noexcept { return playlist_url_; }

  /// The session this was cut from.
  const std::string& session_id() const noexcept { return session_id_; }

  /// `"snap"` for a `request_clip`, `"recording"` for a `request_recording`.
  const std::string& kind() const noexcept { return kind_; }

  /// The window, in the session's media clock.
  double start_marker() const noexcept { return start_marker_; }
  double end_marker() const noexcept { return end_marker_; }
  double now_marker() const noexcept { return now_marker_; }

  /// The runtime's guess at when this will be ready, in Unix milliseconds.
  ///
  /// A wall clock plus media seconds, so it is only right for a model generating
  /// at real time — one at a tenth of that reaches the boundary chunk ten times
  /// later. Used as the anchor a grace period is measured from, never as a
  /// deadline. Zero when the runtime offered none.
  double predicted_ready_at_ms() const noexcept { return predicted_ready_at_ms_; }

  /// How the download should behave.
  struct DownloadOptions {
    /// Called after each segment is written, with how many of how many.
    ///
    /// Invoked on the downloader's own thread. Fine for a counter or a log line,
    /// and not a place to touch anything that is not thread-safe.
    std::function<void(std::uint32_t done, std::uint32_t total)> on_progress;

    /// Grace past when the runtime predicted the clip would be ready.
    ///
    /// Empty — the default — waits as long as the session lives, which is the only
    /// sane answer for a model generating slower than real time. A clip becomes
    /// ready *because* the model keeps generating; once the session is gone, a
    /// "not yet" is a "not yet" forever, and that is what ends the wait.
    std::optional<double> ready_timeout_seconds;
  };

  /// Fetch and assemble this clip into one playable file at `path`.
  ///
  /// The file is created before anything is asked of the network, so an unwritable
  /// path fails immediately; a download that fails part-way leaves nothing behind,
  /// because a truncated clip opens, plays some of itself, and gives no reason to
  /// suspect the download.
  std::future<void> download(std::string path, DownloadOptions options = {}) const;

 private:
  friend class Reactor;
  friend class detail::ClientImpl;
  /// The pending call that builds one out of the platform's answer.
  friend class detail::PendingClip;

  Clip() = default;

  std::weak_ptr<detail::ClientImpl> client_;
  std::string playlist_url_;
  std::string session_id_;
  std::string kind_;
  double start_marker_ = 0.0;
  double end_marker_ = 0.0;
  double now_marker_ = 0.0;
  double predicted_ready_at_ms_ = 0.0;
};

}  // namespace reactor
