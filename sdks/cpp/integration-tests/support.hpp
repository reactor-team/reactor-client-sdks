// Shared fixtures for the C++ SDK integration suite.
//
// Real `reactor::Reactor` clients — real FFI, real WebRTC — against a real model
// in production (`reactor/echo` by default). Nothing here is mocked; that is the
// point. See README.md.
//
// Mirrors `sdks/python/integration-tests/conftest.py` and
// `sdks/js/integration-tests/harness/`, adapted to a synchronous, future-based
// client: there is no event loop to fix up after the fact (see
// lifecycle_and_commands_test.cpp's note on the connect()/on_status ordering
// this suite still has to account for), but session-creation pacing and the
// REA-5931 pixel-assertion convention both carry over unchanged.
#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include <reactor/reactor.hpp>

namespace integration {

// ── configuration ────────────────────────────────────────────────────────────
//
// Same env var names as sdks/js/integration-tests/harness/src/config.ts and
// sdks/python/integration-tests/conftest.py, so pointing this suite at a local
// runtime instead of production reads the same way as pointing either of the
// others.

extern const std::string API_URL;
extern const std::string MODEL_NAME;
extern const bool LOCAL;
extern const std::string API_KEY;  // empty when LOCAL, required otherwise

/// A `Reactor` configured from this suite's env, not yet connected.
///
/// Pass `jwt` to hand the client an already-minted token instead of letting it
/// exchange `API_KEY` for one itself — required for session adoption (see
/// multi_connection_test.cpp): the coordinator only accepts the token that
/// *created* a session for a second connection to adopt it by id, not a fresh
/// one minted per client.
reactor::Reactor new_reactor(std::optional<reactor::Jwt> jwt = std::nullopt,
                              std::string model_name = MODEL_NAME);

/// Mint one token from `API_KEY`, for callers that need to hand the *same*
/// token to more than one `Reactor`. Calls `reactor_fetch_jwt` directly —
/// reaching past the public header on purpose, the same way the unit suite
/// reaches past it for teardown, because the public API has no other way to
/// get at a token it did not mint for you. Blocks the calling thread.
std::string mint_jwt(const std::string& model_name = MODEL_NAME);

/// `client.connect(options).get()`, paced against every other call to this
/// function in the process — not just other calls on `client` itself.
///
/// `reactor/echo`'s session-creation quota (`sessions_per_minute`) is enforced
/// per API key across whatever suite is running against it, not per test or per
/// client — confirmed against prod while building the Python suite this one
/// mirrors. Retries once on a transient `RateLimitedError` as a second line of
/// defense; the pacing gate is the primary one.
///
/// `reconnect()` deliberately isn't routed through here: it reuses the existing
/// session rather than creating a new one, so it isn't counted against the
/// quota this paces against.
void paced_connect(reactor::Reactor& client, const reactor::ConnectOptions& options = {});

/// Creates `Reactor`s and disconnects, then destroys, every one of them when it
/// goes out of scope — in *reverse* creation order, not concurrently.
///
/// Mirrors `sdks/python/integration-tests/conftest.py`'s `reactor_factory`
/// fixture. Reverse, not concurrent teardown: the JS suite's own
/// multi-connection test hit a real bug destroying a session's connections in
/// parallel — it raced the creator's disconnect (which ends the session
/// server-side) against a non-creator connection still leaving. Last created,
/// first torn down keeps a session's non-creator connections gone before its
/// creator.
class ReactorFactory {
 public:
  ReactorFactory() = default;
  ~ReactorFactory();

  ReactorFactory(const ReactorFactory&) = delete;
  ReactorFactory& operator=(const ReactorFactory&) = delete;
  ReactorFactory(ReactorFactory&&) = delete;
  ReactorFactory& operator=(ReactorFactory&&) = delete;

  /// A new, not-yet-connected client, tracked for teardown.
  reactor::Reactor& create(std::optional<reactor::Jwt> jwt = std::nullopt,
                            std::string model_name = MODEL_NAME);

 private:
  std::vector<std::unique_ptr<reactor::Reactor>> created_;
};

/// One connected client — the common case every test that isn't testing
/// connection setup itself starts from. Owns a `ReactorFactory` of its own, so
/// the client is disconnected and destroyed when this goes out of scope.
class ConnectedReactor {
 public:
  ConnectedReactor() : client_(factory_.create()) { paced_connect(client_); }

  reactor::Reactor& get() noexcept { return client_; }
  reactor::Reactor* operator->() noexcept { return &client_; }
  reactor::Reactor& operator*() noexcept { return client_; }

 private:
  ReactorFactory factory_;
  reactor::Reactor& client_;
};

/// Poll `predicate` until it is true or `timeout_s` elapses. Throws
/// `std::runtime_error` on timeout.
///
/// Used for anything fed from `on_frame`/`on_audio` callbacks, which run inline
/// on the FFI's own delivery thread — a plain counter or vector a callback
/// writes to is fine to poll here, just not fine to wait on with a condition
/// variable the callback itself would have to know about.
void wait_until(const std::function<bool()>& predicate, double timeout_s = 10.0,
                double interval_s = 0.1);

/// A BGRA frame of one solid colour — `width * height * 4` bytes, exactly what
/// `Track::push_frame` accepts.
std::vector<std::uint8_t> solid_bgra_frame(std::uint32_t width, std::uint32_t height,
                                            std::uint8_t r, std::uint8_t g, std::uint8_t b);

/// A minimal, valid solid-colour PNG (8-bit depth, RGB, no interlace).
///
/// Hand-rolled rather than pulled from a fixtures directory or an imaging
/// library, mirroring `sdks/python/integration-tests/conftest.py`'s
/// `solid_rgb_png` — the SDK itself has no image-decoding dependency, and this
/// suite's only reason to encode one is to exercise `upload_file`/`upload_bytes`
/// with something a model can actually decode.
std::vector<std::uint8_t> solid_rgb_png(std::uint32_t width, std::uint32_t height,
                                        std::uint8_t r, std::uint8_t g, std::uint8_t b);

}  // namespace integration
