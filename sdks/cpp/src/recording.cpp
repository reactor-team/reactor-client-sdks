// Clips: asking for one, and turning it into a file.

#include "reactor/recording.hpp"

#include <string>
#include <utility>

#include "detail/client_impl.hpp"
#include "detail/ffi.hpp"
#include "reactor/errors.hpp"
#include "reactor/json.hpp"

namespace reactor {

std::future<void> Clip::download(std::string path, DownloadOptions options) const {
  auto op = std::make_unique<detail::PendingDownload>();
  op->operation = "download_clip";
  op->on_progress = std::move(options.on_progress);
  auto future = op->promise.get_future();

  const auto client = client_.lock();
  if (!client) {
    // The playlist would still be fetchable, but the wait for readiness is bounded
    // on the session being alive and there is no session left to ask about.
    op->fail(as_exception_ptr(ErrorDetails{
        std::string{codes::INVALID_STATE},
        "cannot download this clip: the client it came from has been destroyed. Download "
        "before disconnecting, or keep the client alive until the download finishes.",
        false,
        {},
        "download_clip",
        {},
        {}}));
    return future;
  }

  client->begin_download(std::move(op), playlist_url_, path, predicted_ready_at_ms_,
                         options.ready_timeout_seconds);
  return future;
}

namespace detail {

void PendingClip::deliver(Json result) {
  if (!result.is_object() || !result.contains("playlist_url")) {
    promise.set_exception(as_exception_ptr(ErrorDetails{
        std::string{codes::DECODE_FAILED},
        "the platform accepted the recording request but named no playlist: " + result.dump(),
        false,
        {},
        operation,
        {},
        {}}));
    return;
  }

  Clip clip;
  clip.client_ = client;
  // Every one of these throws if the field is there with the wrong type — a
  // numeric playlist_url, a null marker. Caught here rather than left to the
  // trampoline, because `settle` has already claimed this operation: a throw from
  // here would make the trampoline's fallback `fail` a no-op, and the caller would
  // wait on a promise nobody can fulfil. A payload the SDK cannot read is a decode
  // failure, and that is what the caller gets.
  try {
    clip.playlist_url_ = result.value("playlist_url", std::string{});
    clip.session_id_ = result.value("session_id", std::string{});
    clip.kind_ = result.value("kind", std::string{});
    clip.start_marker_ = result.value("start_marker", 0.0);
    clip.end_marker_ = result.value("end_marker", 0.0);
    clip.now_marker_ = result.value("now_marker", 0.0);
    clip.predicted_ready_at_ms_ = result.value("predicted_ready_at_ms", 0.0);
  } catch (const std::exception& error) {
    promise.set_exception(as_exception_ptr(
        ErrorDetails{std::string{codes::DECODE_FAILED},
                     std::string{"the platform's answer to this recording request could not be "
                                 "read ("} +
                         error.what() + "): " + result.dump(),
                     false,
                     {},
                     operation,
                     {},
                     {}}));
    return;
  }
  promise.set_value(std::move(clip));
}

void PendingDownload::progress_trampoline(std::uint32_t done, std::uint32_t total,
                                          void* userdata) noexcept {
  try {
    auto* ticket = static_cast<Ticket*>(userdata);
    if (ticket == nullptr) {
      return;
    }
    // Borrowed, never freed here: the completion owns the ticket, and Rust reports
    // no progress after it.
    const std::shared_ptr<PendingDownload> self = ticket->download.lock();
    if (self && self->on_progress) {
      self->on_progress(done, total);
    }
  } catch (...) {
    // Called from Rust, like every other trampoline here. A progress handler that
    // throws must not take the download — or the process — with it.
  }
}

void PendingDownload::completion_trampoline(int ok, const char* /*result_json*/,
                                            const char* error_json, void* userdata) noexcept {
  // This call owns the ticket, whatever it finds inside it.
  const std::unique_ptr<Ticket> ticket{static_cast<Ticket*>(userdata)};
  if (ticket == nullptr) {
    return;
  }

  const std::shared_ptr<PendingDownload> self = ticket->download.lock();
  if (!self) {
    // The client was destroyed and teardown settled this on the way out. The file
    // may even be complete; what is certain is that nobody is waiting on this
    // future any more, and nothing here is ours to touch.
    return;
  }

  try {
    // The client stops watching first: what it keeps a download for is settling it
    // during teardown, and that is no longer needed.
    if (const auto impl = self->owner.lock()) {
      impl->forget_download(self.get());
    }
    if (ok == 1) {
      // The result carries {path, bytes, segments}; `download()` answers with
      // nothing, and the path is the one the caller passed in.
      self->settle(Json::object());
      return;
    }
    self->fail(as_exception_ptr(error_from_payload(error_json, "download_clip")->details()));
  } catch (...) {
    try {
      self->fail(std::make_exception_ptr(
          ReactorError{"the SDK could not deliver this download's result"}));
    } catch (...) {  // NOLINT(bugprone-empty-catch)
    }
  }
}

bool ClientImpl::has_live_session() const {
  const std::lock_guard<std::mutex> lock(mutex_);
  if (handle_ == nullptr) {
    return false;
  }
  const StaticString status{ffi().status(handle_)};
  return status_from_string(status.view()) != Status::Disconnected;
}

void ClientImpl::begin_request_clip(std::unique_ptr<Pending> op, double duration_seconds) {
  try {
    if (!(duration_seconds > 0.0)) {
      throw BadRequestError{"a clip needs a positive duration; got " +
                            std::to_string(duration_seconds)};
    }
    ReactorHandle* handle = require_ready_handle("request a clip from", model_);
    auto* raw = track_pending(std::move(op));
    ffi().request_clip(handle, duration_seconds, &completion_trampoline, raw);
  } catch (...) {
    op->fail(std::current_exception());
  }
}

void ClientImpl::begin_request_recording(std::unique_ptr<Pending> op) {
  try {
    ReactorHandle* handle = require_ready_handle("request a recording from", model_);
    auto* raw = track_pending(std::move(op));
    ffi().request_recording(handle, &completion_trampoline, raw);
  } catch (...) {
    op->fail(std::current_exception());
  }
}

void ClientImpl::begin_download(std::unique_ptr<Pending> op, const std::string& playlist_url,
                                const std::string& path, double predicted_ready_at_ms,
                                std::optional<double> ready_timeout_seconds) {
  try {
    if (playlist_url.empty()) {
      throw BadRequestError{"this clip names no playlist to download"};
    }
    if (path.empty()) {
      throw BadRequestError{"download() needs a path to write to"};
    }

    ReactorHandle* handle = nullptr;
    std::string jwt;
    bool local = false;
    {
      const std::lock_guard<std::mutex> lock(mutex_);
      // Passed so the downloader can stop asking once this session can no longer
      // produce the clip. Not required: a download of a playlist that is already
      // complete works with no session at all.
      handle = handle_;
      jwt = jwt_.value_or(std::string{});
      local = local_;
    }

    // Negative means "as long as the session lives", which is the right default
    // for a model generating slower than real time.
    const double timeout = ready_timeout_seconds.value_or(-1.0);

    // Shared, and watched rather than tracked: `reactor_download_clip`'s callbacks
    // outlive the handle they were given, so the pending map — whose contents
    // teardown frees — is the one place this must not live.
    std::shared_ptr<PendingDownload> download{static_cast<PendingDownload*>(op.release())};
    download->owner = weak_from_this();
    const bool wants_progress = static_cast<bool>(download->on_progress);
    auto ticket = std::make_unique<PendingDownload::Ticket>(PendingDownload::Ticket{download});
    watch_download(std::move(download));

    // The prediction goes with the timeout, because the timeout is grace *past* it:
    // a clip the runtime expects in ten seconds, asked for with five of grace, has
    // fifteen. Passing the grace alone would time out before the runtime's own
    // estimate had passed.
    ffi().download_clip(handle, playlist_url.c_str(), jwt.empty() ? nullptr : jwt.c_str(),
                        path.c_str(), predicted_ready_at_ms, timeout, local ? 1 : 0,
                        wants_progress ? &PendingDownload::progress_trampoline : nullptr,
                        &PendingDownload::completion_trampoline, ticket.release());
  } catch (...) {
    op->fail(std::current_exception());
  }
}

}  // namespace detail

// ── Reactor ──────────────────────────────────────────────────────────────────

std::future<Clip> Reactor::request_clip(double duration_seconds) {
  auto op = std::make_unique<detail::PendingClip>();
  op->operation = "request_clip";
  op->client = impl_;
  auto future = op->promise.get_future();
  impl_->begin_request_clip(std::move(op), duration_seconds);
  return future;
}

std::future<Clip> Reactor::request_recording() {
  auto op = std::make_unique<detail::PendingClip>();
  op->operation = "request_recording";
  op->client = impl_;
  auto future = op->promise.get_future();
  impl_->begin_request_recording(std::move(op));
  return future;
}

}  // namespace reactor
