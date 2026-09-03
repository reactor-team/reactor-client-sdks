#include "fixtures.hpp"

#include <reactor_ffi.h>

#include <chrono>
#include <cstdlib>
#include <future>
#include <mutex>
#include <reactor/errors.hpp>
#include <reactor/json.hpp>
#include <stdexcept>
#include <thread>

#include <catch2/catch_test_macros.hpp>

namespace integration {

namespace {

std::string env_or(const char* name, std::string fallback) {
  const char* value = std::getenv(name);
  return value != nullptr ? std::string{value} : std::move(fallback);
}

bool env_flag(const char* name) {
  const std::string value = env_or(name, "");
  return value == "1" || value == "true" || value == "TRUE" || value == "True";
}

}  // namespace

const std::string API_URL = env_or("REACTOR_API_URL", std::string{reactor::DEFAULT_API_URL});
const std::string MODEL_NAME = env_or("REACTOR_MODEL_NAME", "reactor/echo");
const bool LOCAL = env_flag("REACTOR_LOCAL");
const std::string API_KEY = env_or("INTEGRATION_TESTS_REACTOR_API_KEY", "");

reactor::Reactor new_reactor(std::optional<reactor::Jwt> jwt, std::string model_name) {
  reactor::Options options;
  options.api_url = API_URL;
  options.local = LOCAL;

  if (jwt.has_value()) {
    return reactor::Reactor{std::move(model_name), std::move(*jwt), options};
  }

  if (!LOCAL && API_KEY.empty()) {
    throw std::runtime_error(
        "INTEGRATION_TESTS_REACTOR_API_KEY is required unless REACTOR_LOCAL=true — see "
        "sdks/cpp/integration-tests/README.md.");
  }
  return reactor::Reactor{std::move(model_name), reactor::ApiKey{API_KEY}, options};
}

std::string mint_jwt(const std::string& model_name) {
  if (!LOCAL && API_KEY.empty()) {
    throw std::runtime_error(
        "INTEGRATION_TESTS_REACTOR_API_KEY is required unless REACTOR_LOCAL=true — see "
        "sdks/cpp/integration-tests/README.md.");
  }

  // `reactor_fetch_jwt` takes no handle and is not part of the public C++
  // surface — the object model deliberately never hands a caller a raw token,
  // only a `Reactor` built from one. Session adoption needs the token itself,
  // shared between two clients, so this reaches past the header the same way
  // the unit suite reaches past it for teardown (see tests/reactor_test.cpp).
  struct Pending {
    std::promise<std::string> promise;
  };
  // Freed by the completion below, exactly once — mirrors how every other
  // completion trampoline in this SDK crosses the FFI boundary as a raw
  // pointer (see client_impl.hpp's own NOLINT'd `new`/`delete` pair).
  auto* pending = new Pending();  // NOLINT(cppcoreguidelines-owning-memory)
  auto future = pending->promise.get_future();

  const std::string options_json = R"({"models":[")" + model_name + R"("]})";

  reactor_fetch_jwt(
      API_URL.c_str(), API_KEY.c_str(), options_json.c_str(), LOCAL ? 1 : 0,
      [](int ok, const char* result_json, const char* error_json, void* userdata) {
        auto* pending = static_cast<Pending*>(userdata);
        if (ok != 0) {
          try {
            const auto parsed = reactor::Json::parse(result_json);
            pending->promise.set_value(parsed.at("jwt").get<std::string>());
          } catch (...) {
            pending->promise.set_exception(std::current_exception());
          }
        } else {
          // Reuses the SDK's own error decoding, so a rejected key surfaces as
          // the same typed `UnauthorizedError` a failed `connect()` would.
          const auto error = reactor::error_from_payload(error_json, "mint_jwt");
          try {
            error->rethrow();
          } catch (...) {
            pending->promise.set_exception(std::current_exception());
          }
        }
        delete pending;  // NOLINT(cppcoreguidelines-owning-memory)
      },
      pending);

  return future.get();
}

namespace {

// Session-creation pacing, process-wide. See paced_connect's own docstring.
// 8000ms (~7.5/min) matched the old 10/min quota; now 100/min, so 700ms
// (~86/min) leaves real margin without being needlessly conservative —
// RateLimitedError still gets one retry as a second line of defense.
constexpr auto SESSION_CREATE_INTERVAL = std::chrono::milliseconds(700);
constexpr int MAX_CONNECT_ATTEMPTS = 3;

// A process-wide gate, deliberately: the quota it paces against is per API key
// across the whole suite, not per test.
// NOLINTBEGIN(cppcoreguidelines-avoid-non-const-global-variables)
std::mutex g_connect_mutex;
std::chrono::steady_clock::time_point g_last_connect_at{};
// NOLINTEND(cppcoreguidelines-avoid-non-const-global-variables)

}  // namespace

void paced_connect(reactor::Reactor& client, const reactor::ConnectOptions& options) {
  {
    std::unique_lock<std::mutex> lock(g_connect_mutex);
    const auto now = std::chrono::steady_clock::now();
    const auto earliest = g_last_connect_at + SESSION_CREATE_INTERVAL;
    if (now < earliest) {
      std::this_thread::sleep_for(earliest - now);
    }
    g_last_connect_at = std::chrono::steady_clock::now();
  }  // released before the real connect(), so other callers only wait for the gate

  for (int attempt = 1;; ++attempt) {
    try {
      client.connect(options).get();
      return;
    } catch (const reactor::RateLimitedError& error) {
      if (attempt >= MAX_CONNECT_ATTEMPTS) {
        throw;
      }
      const double retry_after_s = error.retry_after_ms().value_or(5000.0) / 1000.0;
      std::this_thread::sleep_for(std::chrono::duration<double>(retry_after_s));
    }
  }
}

ReactorFactory::~ReactorFactory() {
  for (auto it = created_.rbegin(); it != created_.rend(); ++it) {
    try {
      (*it)->disconnect().get();
    } catch (const reactor::ReactorError&) {  // NOLINT(bugprone-empty-catch)
      // Best-effort: a test that failed partway through must not leave a
      // session running against the live model, but a disconnect that itself
      // fails (already disconnected, transport already gone) isn't this
      // destructor's problem to report.
    }
  }
  // Destroying the unique_ptrs (in whatever order the vector destructs) drops
  // the native handle for each — disconnect() above already ended the session,
  // so the order here doesn't matter the way the disconnect order above does.
}

reactor::Reactor& ReactorFactory::create(std::optional<reactor::Jwt> jwt, std::string model_name) {
  created_.push_back(
      std::make_unique<reactor::Reactor>(new_reactor(std::move(jwt), std::move(model_name))));
  return *created_.back();
}

void wait_until(const std::function<bool()>& predicate, double timeout_s, double interval_s) {
  const auto deadline = std::chrono::steady_clock::now() + std::chrono::duration<double>(timeout_s);
  while (!predicate()) {
    if (std::chrono::steady_clock::now() >= deadline) {
      throw std::runtime_error("condition not met within " + std::to_string(timeout_s) + "s");
    }
    std::this_thread::sleep_for(std::chrono::duration<double>(interval_s));
  }
}

FramePump::FramePump(reactor::Track track, std::vector<std::uint8_t> bgra, std::uint32_t width,
                     std::uint32_t height)
    : track_(std::move(track)),
      bgra_(std::move(bgra)),
      width_(width),
      height_(height),
      thread_([this] { run(); }) {}

FramePump::~FramePump() {
  stop_.store(true);
  if (thread_.joinable()) {
    thread_.join();
  }
}

void FramePump::run() {
  try {
    while (!stop_.load()) {
      track_.push_frame(reactor::Bytes{bgra_.data(), bgra_.size()}, width_, height_);
      std::this_thread::sleep_for(std::chrono::milliseconds(33));  // ~30fps
    }
  } catch (...) {
    // Stored, not swallowed: an exception escaping this function directly
    // (there is no caller frame to unwind into — this runs as the thread's
    // entry point) calls std::terminate, whether or not the destructor later
    // joins it. check() lets a caller surface this as a test failure instead.
    const std::lock_guard<std::mutex> lock(error_mutex_);
    error_ = std::current_exception();
  }
}

void FramePump::check() const {
  const std::lock_guard<std::mutex> lock(error_mutex_);
  if (error_) {
    std::rethrow_exception(error_);
  }
}

std::vector<std::uint8_t> solid_bgra_frame(std::uint32_t width, std::uint32_t height,
                                           std::uint8_t r, std::uint8_t g, std::uint8_t b) {
  std::vector<std::uint8_t> bgra(static_cast<std::size_t>(width) * height * 4U);
  for (std::size_t i = 0; i < bgra.size(); i += 4) {
    bgra[i + 0] = b;
    bgra[i + 1] = g;
    bgra[i + 2] = r;
    bgra[i + 3] = 0xFF;
  }
  return bgra;
}

namespace {

// Minimal, dependency-free deflate: one uncompressed (stored) block, which is
// always legal DEFLATE and all a fixed-content PNG like this one needs.
std::vector<std::uint8_t> zlib_store(const std::vector<std::uint8_t>& raw) {
  std::vector<std::uint8_t> out;
  out.push_back(0x78);
  out.push_back(0x01);  // zlib header: deflate, default window, no dict

  std::size_t offset = 0;
  constexpr std::size_t max_block = 65535;
  while (true) {
    const std::size_t remaining = raw.size() - offset;
    const std::size_t block = std::min(remaining, max_block);
    const bool last = (offset + block) >= raw.size();
    out.push_back(last ? 0x01 : 0x00);  // BFINAL, BTYPE=00 (stored)
    const auto len = static_cast<std::uint16_t>(block);
    const std::uint16_t nlen = static_cast<std::uint16_t>(~len);
    out.push_back(static_cast<std::uint8_t>(len & 0xFF));
    out.push_back(static_cast<std::uint8_t>((len >> 8) & 0xFF));
    out.push_back(static_cast<std::uint8_t>(nlen & 0xFF));
    out.push_back(static_cast<std::uint8_t>((nlen >> 8) & 0xFF));
    out.insert(out.end(), raw.begin() + static_cast<std::ptrdiff_t>(offset),
               raw.begin() + static_cast<std::ptrdiff_t>(offset + block));
    offset += block;
    if (last) {
      break;
    }
  }

  // Adler-32, which zlib's trailer requires and no PNG decoder skips checking.
  std::uint32_t a = 1;
  std::uint32_t b = 0;
  for (const std::uint8_t byte : raw) {
    a = (a + byte) % 65521;
    b = (b + a) % 65521;
  }
  const std::uint32_t adler = (b << 16) | a;
  out.push_back(static_cast<std::uint8_t>((adler >> 24) & 0xFF));
  out.push_back(static_cast<std::uint8_t>((adler >> 16) & 0xFF));
  out.push_back(static_cast<std::uint8_t>((adler >> 8) & 0xFF));
  out.push_back(static_cast<std::uint8_t>(adler & 0xFF));
  return out;
}

std::uint32_t crc32(const std::uint8_t* data, std::size_t size) {
  std::uint32_t crc = 0xFFFFFFFFU;
  for (std::size_t i = 0; i < size; ++i) {
    crc ^= data[i];
    for (int bit = 0; bit < 8; ++bit) {
      const std::uint32_t mask = -(crc & 1U);
      crc = (crc >> 1) ^ (0xEDB88320U & mask);
    }
  }
  return ~crc;
}

void append_chunk(std::vector<std::uint8_t>& out, const char* tag,
                  const std::vector<std::uint8_t>& data) {
  const auto len = static_cast<std::uint32_t>(data.size());
  const auto push_u32 = [&out](std::uint32_t v) {
    out.push_back(static_cast<std::uint8_t>((v >> 24) & 0xFF));
    out.push_back(static_cast<std::uint8_t>((v >> 16) & 0xFF));
    out.push_back(static_cast<std::uint8_t>((v >> 8) & 0xFF));
    out.push_back(static_cast<std::uint8_t>(v & 0xFF));
  };
  push_u32(len);
  std::vector<std::uint8_t> tag_and_data(tag, tag + 4);
  tag_and_data.insert(tag_and_data.end(), data.begin(), data.end());
  out.insert(out.end(), tag_and_data.begin(), tag_and_data.end());
  push_u32(crc32(tag_and_data.data(), tag_and_data.size()));
}

}  // namespace

std::vector<std::uint8_t> solid_rgb_png(std::uint32_t width, std::uint32_t height, std::uint8_t r,
                                        std::uint8_t g, std::uint8_t b) {
  std::vector<std::uint8_t> raw;
  raw.reserve(static_cast<std::size_t>(height) * (1 + static_cast<std::size_t>(width) * 3));
  for (std::uint32_t y = 0; y < height; ++y) {
    raw.push_back(0x00);  // filter: none
    for (std::uint32_t x = 0; x < width; ++x) {
      raw.push_back(r);
      raw.push_back(g);
      raw.push_back(b);
    }
  }

  std::vector<std::uint8_t> ihdr;
  const auto push_u32 = [&ihdr](std::uint32_t v) {
    ihdr.push_back(static_cast<std::uint8_t>((v >> 24) & 0xFF));
    ihdr.push_back(static_cast<std::uint8_t>((v >> 16) & 0xFF));
    ihdr.push_back(static_cast<std::uint8_t>((v >> 8) & 0xFF));
    ihdr.push_back(static_cast<std::uint8_t>(v & 0xFF));
  };
  push_u32(width);
  push_u32(height);
  ihdr.push_back(8);  // bit depth
  ihdr.push_back(2);  // color type: RGB
  ihdr.push_back(0);  // compression
  ihdr.push_back(0);  // filter
  ihdr.push_back(0);  // interlace

  std::vector<std::uint8_t> png = {0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
  append_chunk(png, "IHDR", ihdr);
  append_chunk(png, "IDAT", zlib_store(raw));
  append_chunk(png, "IEND", {});
  return png;
}

std::array<double, 3> mean_rgb(const reactor::VideoFrame& frame) {
  const std::size_t pixels = static_cast<std::size_t>(frame.width) * frame.height;
  if (pixels == 0) {
    return {0.0, 0.0, 0.0};
  }

  double r_sum = 0.0;
  double g_sum = 0.0;
  double b_sum = 0.0;
  for (std::size_t i = 0; i < pixels; ++i) {
    b_sum += frame.bgra[(i * 4) + 0];
    g_sum += frame.bgra[(i * 4) + 1];
    r_sum += frame.bgra[(i * 4) + 2];
  }

  return {r_sum / static_cast<double>(pixels), g_sum / static_cast<double>(pixels),
          b_sum / static_cast<double>(pixels)};
}

void assert_dominant_color(const std::array<double, 3>& mean, std::array<int, 3> expected,
                           double tolerance) {
  for (std::size_t channel = 0; channel < 3; ++channel) {
    const double mean_value = mean.at(channel);
    const int expected_value = expected.at(channel);
    INFO("channel " << channel << ": mean=" << mean_value << " expected=" << expected_value);
    REQUIRE(std::abs(mean_value - static_cast<double>(expected_value)) <= tolerance);
  }
}

}  // namespace integration
