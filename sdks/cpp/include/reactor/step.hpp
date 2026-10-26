// Stepped generation: what a step takes and what it answers with.
//
//     reactor::Options options;
//     options.generation = reactor::Generation::Stepped;
//     reactor::Reactor client{"owner/model", reactor::ApiKey{key}, options};
//     client.connect().get();   // GenerationModeMismatchError if the runtime cannot
//
//     auto result = client.step({{"prompt", "a red fox"}},
//                               {{"advance", {{"frames", 4}}}}).get();
//     for (const auto& frame : result.track("main_video").frames) {
//       use(frame.data, frame.shape);   // (height, width, 3) uint8 RGB
//     }
//
// A stepped session does not stream. Each `Reactor::step` applies the model's
// state, a list of commands and every input frame pushed since the previous step,
// runs the model once, and resolves with what it produced on each output track
// and the files it kept.
#pragma once

#include <cstddef>
#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <string_view>
#include <variant>
#include <vector>

#include "reactor/json.hpp"
#include "reactor/track.hpp"

namespace reactor {

/// How a client's sessions generate.
enum class Generation : std::uint8_t {
  /// Continuously, over the model's media tracks. The default, and what every
  /// session did before the choice existed.
  Streamed,
  /// One `Reactor::step()` at a time. `connect()` fails with
  /// `GenerationModeMismatchError` when the runtime does not start the session
  /// that way, as one without stepped generation will not.
  Stepped,
};

/// The wire spellings: "streamed", "stepped".
std::string_view to_string(Generation generation) noexcept;

/// One command of a step, run after the step's state, in order.
///
/// `data` is the command's arguments as a JSON object, or null for a command that
/// takes none:
///
///     reactor::StepCommand{"set_prompt", {{"prompt", "a red fox"}}}
///     reactor::StepCommand{"advance"}
struct StepCommand {
  std::string command{};
  Json data = nullptr;
};

/// A raw video frame for a step's media, BGRA or RGB, as `Track::push_frame`
/// takes it.
///
/// Borrowed, like `Bytes`: `pixels` (and `options.user_data`) must stay valid
/// until `step()` returns, which pushes it before it does.
struct RawVideoFrame {
  /// `width * height * 4` bytes, B, G, R, A per pixel, or `* 3` and R, G, B for
  /// `PixelFormat::Rgb`.
  Bytes pixels{};
  std::uint32_t width = 0;
  std::uint32_t height = 0;
  Track::FrameOptions options{};
  PixelFormat format = PixelFormat::Bgra;
};

/// Interleaved 16-bit PCM for a step's media, as `Track::push_frame` takes it,
/// for an audio input track.
///
/// Borrowed, like `Samples`: `pcm` must stay valid until `step()` returns.
struct RawAudioFrame {
  Samples pcm{};
  std::uint32_t sample_rate = 48'000;
  std::uint32_t channels = 1;
};

/// One input frame of a step's media: raw video, encoded video decoded by the
/// runtime before the step runs, or raw audio.
using StepMediaFrame = std::variant<RawVideoFrame, EncodedFrame, RawAudioFrame>;

/// A step's own input frames, by input track name, each track's in the order they
/// go: see `Reactor::step(state, commands, media)`.
using StepMedia = std::map<std::string, std::vector<StepMediaFrame>>;

/// One frame a step produced on an output track.
///
/// Owned: the bytes are copied out of the native result once, when the step
/// completes, and the native result is freed straight away. Keep it as long as
/// you like.
struct StepFrame {
  /// How the frame travelled on the wire — "raw" today. `data` is already
  /// decoded whatever this says.
  std::string encoding{};

  /// Element type in NumPy notation: "uint8" for video, "int16" for audio.
  std::string dtype{};

  /// The array's shape: (height, width, 3) for video, (channels, samples) for
  /// audio.
  std::vector<std::uint32_t> shape{};

  /// The array's bytes: row-major, little-endian for multi-byte elements —
  /// `product(shape) * sizeof(dtype)` of them, checked when the result arrived.
  /// Video is RGB, not the BGRA that streamed frames and `push_frame` use.
  std::vector<std::uint8_t> data{};

  /// What the sender attached to the frame. Empty when it attached nothing; an
  /// attached but empty tag is a present, empty vector.
  std::optional<std::vector<std::uint8_t>> metadata{};

  /// When the sender says the frame was captured, in microseconds, if it said.
  std::optional<std::int64_t> capture_time_us{};
};

/// The frames one output track produced in a step, in production order.
struct StepTrackOutput {
  std::string name{};
  TrackKind kind = TrackKind::Video;
  /// The rate the track's frames play at, as the runtime reported it: frames per
  /// second for video, samples per second for audio. 0 when the runtime did not
  /// say.
  double rate = 0.0;
  std::vector<StepFrame> frames{};
};

/// A file the model kept with a step, beyond the frames it produced.
///
/// Only its description travels with the result. The bytes are fetched over HTTP
/// from the session's step results, `GET /sessions/{id}/steps/{step}`, which
/// lists each file with a download URL (and answers 202 while they are not yet
/// stored).
struct StepFile {
  std::string name{};
  std::string content_type{};
  /// In bytes.
  std::uint64_t size = 0;
};

/// What one step produced: its number in the session, per output track the
/// frames it generated, the files the model kept, and whether the model reported
/// a failure.
struct StepResult {
  /// The step's number in the session, counted from 1: the step of the
  /// session's step routes, `GET /sessions/{id}/steps/{step}`.
  std::uint64_t step = 0;

  /// In the order the runtime sent them. A track the model produced nothing on
  /// in this step may be absent.
  std::vector<StepTrackOutput> tracks{};

  /// Seconds the step's `generate()` took, or the time the model reported for
  /// the step.
  double elapsed{};

  /// The files the model kept with this step, described. Empty when it kept
  /// none, or when the result was not saved.
  std::vector<StepFile> files{};

  /// Set when the step ran and the model reported a failure. Such a step still
  /// resolves; `Reactor::step()`'s future fails only for a step that did not run
  /// or whose result could not be delivered.
  std::optional<std::string> error{};

  /// The output of the track called `name`.
  ///
  /// Throws `NotFoundError` naming the tracks that did produce output: a model can
  /// legitimately produce nothing on a track in a step, and that is worth telling
  /// apart from a typo.
  const StepTrackOutput& track(std::string_view name) const;
};

}  // namespace reactor
