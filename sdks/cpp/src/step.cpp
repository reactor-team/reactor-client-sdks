// Stepped generation: creating a stepped client, running a step, and reading the
// result the library hands over.
//
// The one ownership rule here is the result's. `reactor_step`'s completion hands
// this SDK a `ReactorStepResult` it now owns; the accessors borrow from it. It is
// copied out once, into a `StepResult` that owns its bytes, and freed before the
// completion returns — on every path, including the ones that throw — so nothing
// a caller holds ever points into the library.

#include "reactor/step.hpp"

#include <cmath>
#include <cstdint>
#include <functional>
#include <limits>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <variant>
#include <vector>

#include "detail/client_impl.hpp"
#include "detail/ffi.hpp"
#include "detail/strings.hpp"
#include "reactor/errors.hpp"
#include "reactor/json.hpp"
#include "reactor/reactor.hpp"
#include "reactor/version.hpp"

namespace reactor {

std::string_view to_string(Generation generation) noexcept {
  switch (generation) {
    case Generation::Streamed:
      return "streamed";
    case Generation::Stepped:
      return "stepped";
  }
  return "streamed";
}

const StepTrackOutput& StepResult::track(std::string_view name) const {
  for (const auto& output : tracks) {
    if (output.name == name) {
      return output;
    }
  }
  std::string produced;
  for (const auto& output : tracks) {
    produced += (produced.empty() ? "\"" : ", \"") + output.name + "\"";
  }
  throw NotFoundError{"step " + std::to_string(step) + " produced no output on \"" +
                      std::string{name} +
                      "\" (tracks: " + (produced.empty() ? std::string{"none"} : produced) + ")"};
}

namespace detail {

namespace {

/// A `ReactorStepResult` this SDK owns, freed exactly once.
class OwnedStepResult {
 public:
  explicit OwnedStepResult(ReactorStepResult* raw) noexcept : raw_(raw) {}
  ~OwnedStepResult() {
    if (raw_ != nullptr) {
      // Through the table, so a test's fake sees the free.
      ffi().step_result_free(raw_);
    }
  }

  OwnedStepResult(const OwnedStepResult&) = delete;
  OwnedStepResult& operator=(const OwnedStepResult&) = delete;
  OwnedStepResult(OwnedStepResult&&) = delete;
  OwnedStepResult& operator=(OwnedStepResult&&) = delete;

  const ReactorStepResult* get() const noexcept { return raw_; }

 private:
  ReactorStepResult* raw_;
};

/// Element sizes of the dtypes a RAW frame can declare — the same table the
/// Python SDK checks against. A frame whose byte count disagrees with its shape
/// is refused when the result arrives, not when someone first reads past the end.
std::optional<std::size_t> dtype_size(const std::string& dtype) {
  static const std::map<std::string, std::size_t> sizes{
      {"uint8", 1}, {"int8", 1},    {"uint16", 2},  {"int16", 2},   {"uint32", 4},
      {"int32", 4}, {"float16", 2}, {"float32", 4}, {"float64", 8},
  };
  const auto found = sizes.find(dtype);
  if (found == sizes.end()) {
    return std::nullopt;
  }
  return found->second;
}

std::string text_or_empty(const char* text) { return text == nullptr ? std::string{} : text; }

[[noreturn]] void throw_decode(const std::string& message) {
  throw DecodeError{ErrorDetails{
      std::string{codes::DECODE_FAILED}, message, false, {}, std::string{"step"}, {}, {}, {}, {}}};
}

StepFrame read_frame(const Ffi& lib, const ReactorStepResult* result, std::uint32_t track,
                     std::uint32_t index) {
  StepFrame frame;
  frame.encoding = text_or_empty(lib.step_result_frame_encoding(result, track, index));
  frame.dtype = text_or_empty(lib.step_result_frame_dtype(result, track, index));

  std::uint32_t ndim = 0;
  const std::uint32_t* shape = lib.step_result_frame_shape(result, track, index, &ndim);
  if (shape != nullptr) {
    frame.shape.assign(shape, shape + ndim);
  }

  std::size_t length = 0;
  const std::uint8_t* data = lib.step_result_frame_data(result, track, index, &length);
  if (data != nullptr && length != 0) {
    frame.data.assign(data, data + length);
  }

  std::size_t metadata_length = 0;
  const std::uint8_t* metadata =
      lib.step_result_frame_metadata(result, track, index, &metadata_length);
  if (metadata != nullptr) {
    // Non-null and empty is an attached, empty tag — not the same as none.
    frame.metadata = std::vector<std::uint8_t>(metadata, metadata + metadata_length);
  }

  std::int64_t captured = 0;
  if (lib.step_result_frame_capture_time_us(result, track, index, &captured) == 1) {
    frame.capture_time_us = captured;
  }

  if (const auto element = dtype_size(frame.dtype)) {
    std::size_t expected = *element;
    for (const std::uint32_t dimension : frame.shape) {
      if (dimension != 0 && expected > std::numeric_limits<std::size_t>::max() / dimension) {
        throw_decode("a step frame's shape overflows a byte count");
      }
      expected *= dimension;
    }
    if (frame.data.size() != expected) {
      std::string shape_text;
      for (const std::uint32_t dimension : frame.shape) {
        shape_text += (shape_text.empty() ? "" : ", ") + std::to_string(dimension);
      }
      throw_decode("a step frame of shape (" + shape_text + ") and dtype " + frame.dtype +
                   " needs " + std::to_string(expected) + " bytes, got " +
                   std::to_string(frame.data.size()));
    }
  }
  return frame;
}

/// Copy a native result into one that owns its bytes. Does not free it.
StepResult read_step_result(const Ffi& lib, const ReactorStepResult* result) {
  StepResult copied;
  copied.step = lib.step_result_step(result);
  copied.elapsed = lib.step_result_elapsed(result);
  const std::uint32_t track_count = lib.step_result_track_count(result);
  copied.tracks.reserve(track_count);
  for (std::uint32_t track = 0; track < track_count; ++track) {
    StepTrackOutput output;
    output.name = text_or_empty(lib.step_result_track_name(result, track));
    const std::string kind = text_or_empty(lib.step_result_track_kind(result, track));
    const auto parsed = track_kind_from_string(kind);
    if (!parsed) {
      throw_decode("step output track \"" + output.name + "\" has an unknown kind \"" + kind +
                   "\"");
    }
    output.kind = *parsed;
    output.rate = lib.step_result_track_rate(result, track);
    const std::uint32_t frame_count = lib.step_result_frame_count(result, track);
    output.frames.reserve(frame_count);
    for (std::uint32_t frame = 0; frame < frame_count; ++frame) {
      output.frames.push_back(read_frame(lib, result, track, frame));
    }
    copied.tracks.push_back(std::move(output));
  }
  const std::uint32_t file_count = lib.step_result_file_count(result);
  copied.files.reserve(file_count);
  for (std::uint32_t file = 0; file < file_count; ++file) {
    copied.files.push_back(StepFile{text_or_empty(lib.step_result_file_name(result, file)),
                                    text_or_empty(lib.step_result_file_content_type(result, file)),
                                    lib.step_result_file_size(result, file)});
  }
  if (const char* error = lib.step_result_error(result)) {
    copied.error = std::string{error};
  }
  return copied;
}

/// `commands` as the `[{command, data}]` array the C ABI takes, or a thrown
/// `BadRequestError` naming the entry that is wrong.
Json wire_commands(const std::vector<StepCommand>& commands) {
  Json wired = Json::array();
  for (std::size_t i = 0; i < commands.size(); ++i) {
    const auto& entry = commands[i];
    if (entry.command.empty()) {
      throw BadRequestError{"step command " + std::to_string(i) + " has no name"};
    }
    if (!entry.data.is_null() && !entry.data.is_object()) {
      // The runtime would refuse it, but only after the step's inputs were sent
      // with it; saying so here keeps them for the next step.
      throw BadRequestError{"the data of step command \"" + entry.command +
                            "\" must be a JSON object or null, got " +
                            std::string{entry.data.type_name()}};
    }
    wired.push_back(Json{{"command", entry.command}, {"data", entry.data}});
  }
  return wired;
}

}  // namespace

std::optional<double> ClientImpl::checked_step_timeout(Generation generation,
                                                       std::optional<double> timeout) {
  if (!timeout) {
    return timeout;
  }
  if (generation != Generation::Stepped) {
    throw BadRequestError{
        "Options::step_timeout_seconds only applies to a stepped client: set "
        "Options::generation to Generation::Stepped, or leave the timeout unset"};
  }
  // A NaN or an infinity would reach the library as a duration it cannot make.
  if (!std::isfinite(*timeout) || *timeout <= 0) {
    throw BadRequestError{
        "Options::step_timeout_seconds must be a finite number of seconds above zero, got " +
        std::to_string(*timeout)};
  }
  return timeout;
}

ReactorHandle* ClientImpl::create_stepped_handle(const ReactorCallbacks& callbacks) const {
  Json options{
      {"local", local_},
      {"auto_resume_tracks", auto_resume_tracks_},
      // Named, as `create_with_adm`'s mode 0 is: never the environment's choice.
      {"adm", "synthetic"},
      {"sdk_version", REACTOR_SDK_VERSION},
      {"sdk_type", "cpp"},
      {"generation", std::string{to_string(generation_)}},
  };
  if (step_timeout_seconds_) {
    options["step_timeout_seconds"] = *step_timeout_seconds_;
  }
  const std::string options_json = options.dump();

  char* error = nullptr;
  ReactorHandle* handle =
      ffi().create_with_options(api_url_.c_str(), model_.c_str(), jwt_ ? jwt_->c_str() : nullptr,
                                &callbacks, options_json.c_str(), &error);
  if (handle == nullptr) {
    const OwnedString refused{error};
    if (refused) {
      throw_error_payload(refused.view(), "connect");
    }
    throw ReactorError{"libreactor_ffi could not create a stepped client"};
  }
  return handle;
}

void ClientImpl::step_completion_trampoline(int ok, ReactorStepResult* result,
                                            const char* error_json, void* userdata) noexcept {
  // Ours from here, on every path: freed when this returns, copied or not.
  const OwnedStepResult owned_result{result};
  auto* raw = static_cast<PendingStep*>(userdata);
  if (raw == nullptr) {
    return;
  }
  try {
    // Reclaimed from the client if it is still tracked. Null means teardown got
    // here first and owns the pointer, so it is settled here but never freed.
    std::unique_ptr<Pending> owned;
    if (const auto impl = raw->owner.lock()) {
      owned = impl->untrack(raw);
    }

    if (ok != 1) {
      raw->fail(as_exception_ptr(error_from_payload(error_json, "step")->details()));
      return;
    }
    if (owned_result.get() == nullptr) {
      raw->fail(as_exception_ptr(ErrorDetails{std::string{codes::DECODE_FAILED},
                                              "a step succeeded with no result",
                                              false,
                                              {},
                                              "step",
                                              {},
                                              {},
                                              {},
                                              {}}));
      return;
    }
    StepResult copied;
    try {
      copied = read_step_result(ffi(), owned_result.get());
    } catch (const ReactorError& error) {
      raw->fail(as_exception_ptr(error.details()));
      return;
    }
    raw->settle_step(std::move(copied));
  } catch (...) {
    // The caller is waiting on a future; leaving it unsettled would hang them.
    try {
      raw->fail(std::make_exception_ptr(ReactorError{"the SDK could not deliver this step"}));
    } catch (...) {  // NOLINT(bugprone-empty-catch)
    }
  }
}

void ClientImpl::push_step_media(const StepMedia& media) {
  // Every frame is checked before the first is pushed: the buffer can only be
  // cleared whole, so a refusal part-way would leave the frames before it to ride
  // the next step, with no way to take back just those.
  std::vector<std::function<void()>> pushes;
  for (const auto& [name, frames] : media) {
    for (const auto& frame : frames) {
      if (const auto* encoded = std::get_if<EncodedFrame>(&frame)) {
        pushes.push_back(prepare_encoded_video(name, *encoded));
      } else if (const auto* audio = std::get_if<RawAudioFrame>(&frame)) {
        pushes.push_back(prepare_audio(name, audio->pcm, audio->sample_rate, audio->channels));
      } else {
        const auto& raw = std::get<RawVideoFrame>(frame);
        pushes.push_back(
            prepare_video(name, raw.pixels, raw.format, raw.width, raw.height, raw.options));
      }
    }
  }
  for (const auto& push : pushes) {
    push();
  }
}

void ClientImpl::begin_step(std::unique_ptr<PendingStep> op, const Json& state,
                            const std::vector<StepCommand>& commands, const StepMedia& media) {
  try {
    if (generation_ != Generation::Stepped) {
      throw InvalidStateError{
          "step() needs a stepped session: create the client with Options::generation = "
          "reactor::Generation::Stepped"};
    }
    if (!state.is_null() && !state.is_object()) {
      throw BadRequestError{"a step's state must be a JSON object or null, got " +
                            std::string{state.type_name()}};
    }
    const std::string commands_json = wire_commands(commands).dump();
    const std::string state_json = state.is_null() ? std::string{} : state.dump();

    ReactorHandle* handle = require_ready_session("run a step");
    // Pushed only once the state and commands are known to be well formed, so a
    // step refused for those leaves the buffer as the caller left it.
    push_step_media(media);
    PendingStep* step = op.get();
    track_pending(std::move(op));
    ffi().step(handle, state_json.empty() ? nullptr : state_json.c_str(), commands_json.c_str(),
               &step_completion_trampoline, step);
  } catch (...) {
    if (op) {
      op->fail(std::current_exception());
    }
  }
}

void ClientImpl::clear_step_inputs() {
  if (generation_ != Generation::Stepped) {
    return;
  }
  ReactorHandle* handle = nullptr;
  {
    const std::lock_guard<std::mutex> lock(mutex_);
    handle = handle_;
  }
  if (handle != nullptr) {
    ffi().clear_step_inputs(handle);
  }
}

}  // namespace detail

Generation Reactor::generation() const noexcept { return impl_->generation(); }

// By value: see the declaration's own comment in reactor.hpp.
// NOLINTNEXTLINE(performance-unnecessary-value-param)
std::future<StepResult> Reactor::step(Json state, std::vector<StepCommand> commands) {
  auto op = std::make_unique<detail::PendingStep>();
  op->operation = "step";
  auto future = op->promise.get_future();
  impl_->begin_step(std::move(op), state, commands, {});
  return future;
}

// By value: see the declaration's own comment in reactor.hpp.
// NOLINTBEGIN(performance-unnecessary-value-param)
std::future<StepResult> Reactor::step(Json state, std::vector<StepCommand> commands,
                                      StepMedia media) {
  auto op = std::make_unique<detail::PendingStep>();
  op->operation = "step";
  auto future = op->promise.get_future();
  impl_->begin_step(std::move(op), state, commands, media);
  return future;
}
// NOLINTEND(performance-unnecessary-value-param)

void Reactor::clear_step_inputs() { impl_->clear_step_inputs(); }

}  // namespace reactor
