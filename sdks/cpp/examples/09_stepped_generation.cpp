// 09 — Stepped generation: one step at a time instead of a stream.
//
// A client created with `Generation::Stepped` asks for a session that generates
// only when told to. Each `step()` sends the model's state, a list of commands
// and every frame pushed since the previous step, and resolves with what the
// model produced on each output track — as arrays of bytes, not a video stream —
// along with the files it kept and, if it reported one, the step's failure.
//
// Pushing into an input track does not send anything in a stepped session: the
// frame waits for the next step. A runtime without stepped generation ignores the
// request, so `connect()` checks and throws `GenerationModeMismatchError` rather
// than leaving this waiting on a stream.
//
//   export REACTOR_API_KEY=...
//   REACTOR_MODEL=owner/model ./09_stepped_generation
//
// Set REACTOR_INPUT_TRACK to a sendonly video track the model declares to push a
// frame into every step, and REACTOR_STEP_COMMAND to a command the model takes
// with no arguments to run it in every step. REACTOR_PROMPT sets the state's
// `prompt`; REACTOR_STEPS how many steps to run (default 3).
//
// Docs: https://docs.reactor.inc/concepts/tracks

#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <optional>
#include <reactor/reactor.hpp>
#include <string>
#include <vector>

namespace {

constexpr std::uint32_t WIDTH = 320;
constexpr std::uint32_t HEIGHT = 180;

std::optional<std::string> env(const char* name) {
  const char* value = std::getenv(name);
  if (value == nullptr || *value == '\0') {
    return std::nullopt;
  }
  return std::string{value};
}

/// A solid BGRA frame whose colour follows `seq`.
std::vector<std::uint8_t> frame(int seq) {
  std::vector<std::uint8_t> bgra(static_cast<std::size_t>(WIDTH) * HEIGHT * 4U);
  for (std::size_t i = 0; i < bgra.size(); i += 4) {
    bgra[i + 0] = static_cast<std::uint8_t>((seq * 7) & 0xFF);
    bgra[i + 1] = static_cast<std::uint8_t>((seq * 13) & 0xFF);
    bgra[i + 2] = static_cast<std::uint8_t>((seq * 29) & 0xFF);
    bgra[i + 3] = 0xFF;
  }
  return bgra;
}

std::string shape_of(const reactor::StepFrame& frame) {
  std::string text = "(";
  for (std::size_t i = 0; i < frame.shape.size(); ++i) {
    text += (i == 0 ? "" : ", ") + std::to_string(frame.shape[i]);
  }
  return text + ")";
}

}  // namespace

int main() {
  const auto model = env("REACTOR_MODEL");
  if (!model) {
    std::cerr << "set REACTOR_MODEL to a model with stepped generation, as owner/name\n";
    return 2;
  }
  const bool local = env("REACTOR_LOCAL") == std::optional<std::string>{"1"};
  const auto api_key = env("REACTOR_API_KEY");
  if (!api_key && !local) {
    std::cerr << "set REACTOR_API_KEY — https://www.reactor.inc/account/api-keys\n";
    return 2;
  }
  const int steps = std::atoi(env("REACTOR_STEPS").value_or("3").c_str());
  const std::string prompt = env("REACTOR_PROMPT").value_or("a lighthouse in a storm");
  const auto input_track = env("REACTOR_INPUT_TRACK");
  const auto command = env("REACTOR_STEP_COMMAND");

  reactor::Options options;
  options.api_url = env("REACTOR_API_URL").value_or(std::string{reactor::DEFAULT_API_URL});
  options.local = local;
  options.generation = reactor::Generation::Stepped;
  reactor::Reactor client = api_key ? reactor::Reactor{*model, reactor::ApiKey{*api_key}, options}
                                    : reactor::Reactor{*model, options};

  auto errors = client.on_error(
      [](const reactor::ReactorError& error) { std::cerr << "error: " << error.what() << '\n'; });

  try {
    client.connect().get();
    std::cout << "session " << client.session_id().value_or("?") << " is ready\n";

    std::optional<reactor::Track> source;
    if (input_track) {
      source = client.track(*input_track);
      source->publish().get();
    }

    for (int seq = 0; seq < steps; ++seq) {
      if (source) {
        // Buffered, not sent: the step below carries it.
        const auto bgra = frame(seq);
        source->push_frame(reactor::Bytes{bgra.data(), bgra.size()}, WIDTH, HEIGHT);
      }

      // State on the first step only: the model keeps it until changed.
      reactor::Json state = seq == 0 ? reactor::Json{{"prompt", prompt}} : reactor::Json{};
      std::vector<reactor::StepCommand> commands;
      if (command) {
        commands.push_back(reactor::StepCommand{*command});
      }

      const auto result = client.step(std::move(state), std::move(commands)).get();
      for (const auto& track : result.tracks) {
        std::cout << "step " << result.step << ": " << track.name << " ("
                  << reactor::to_string(track.kind) << ") " << track.frames.size() << " frame(s)";
        for (const auto& out : track.frames) {
          std::cout << ' ' << shape_of(out) << ' ' << out.dtype;
        }
        std::cout << '\n';
      }
      for (const auto& file : result.files) {
        // Described only: the bytes are on GET /sessions/{id}/steps/{index}.
        std::cout << "step " << result.step << ": kept " << file.name << " (" << file.content_type
                  << ", " << file.size << " bytes)\n";
      }
      if (result.error) {
        // The step ran and the model reported a failure; the session goes on.
        std::cout << "step " << result.step << ": the model reported " << *result.error << '\n';
      }
    }

    client.disconnect().get();
    return 0;
  } catch (const reactor::ReactorError& error) {
    std::cerr << "failed: " << error.what() << '\n';
    try {
      client.disconnect().get();
    } catch (const reactor::ReactorError&) {
    }
    return 1;
  }
}
