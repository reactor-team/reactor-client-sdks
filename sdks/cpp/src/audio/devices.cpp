// Speaker and Microphone, over miniaudio.
//
// miniaudio is compiled once, here, into this target only — which is what keeps
// `reactor::sdk` free of any audio dependency. A build without
// REACTOR_SDK_HAVE_MINIAUDIO still compiles this file, and `start()` then throws
// rather than pretending: a speaker that plays nothing and says nothing is the
// worst of the three possible behaviours.

#include <mutex>
#include <string>
#include <vector>

#include "audio/ring_buffer.hpp"
#include "reactor/audio_devices.hpp"
#include "reactor/errors.hpp"

#ifdef REACTOR_SDK_HAVE_MINIAUDIO
#define MINIAUDIO_IMPLEMENTATION
// Only what a Speaker and a Microphone need. Every other backend, decoder and
// resampler miniaudio ships is compiled out — this is a small helper, not a media
// framework.
#define MA_NO_DECODING
#define MA_NO_ENCODING
#define MA_NO_GENERATION
#define MA_NO_ENGINE
#define MA_NO_NODE_GRAPH
#include <miniaudio.h>
#endif

namespace reactor::audio {
namespace {

/// What the far end sends and what the source expects. Anything else is resampled
/// by the device layer, which is the one thing miniaudio is asked to do here.
constexpr std::uint32_t DEFAULT_SAMPLE_RATE = 48'000;

/// Roughly 400 ms at 48 kHz mono — enough to ride out a slow handler, short
/// enough that a listener hears the stream rather than the buffer.
constexpr std::size_t BUFFER_SAMPLES = 19'200;

/// Refuse a device on a track that could never feed it.
///
/// The FFI would accept every one of these and simply never call back, which is
/// indistinguishable from silence on the wire.
void require_audio(const Track& track, TrackDirection wanted, const char* what) {
  const auto kind = track.kind();
  const auto direction = track.direction();
  if (!kind || !direction) {
    throw InvalidStateError{std::string{"cannot attach a "} + what + " to track \"" + track.name() +
                            "\" yet: the session has not declared its tracks. Attach after "
                            "connect() resolves."};
  }
  if (*kind != TrackKind::Audio) {
    throw InvalidStateError{std::string{"track \""} + track.name() + "\" carries " +
                            std::string{to_string(*kind)} + ", not audio, so a " + what +
                            " would have nothing to work with."};
  }
  if (*direction != wanted) {
    throw InvalidStateError{
        std::string{"track \""} + track.name() + "\" is " + std::string{to_string(*direction)} +
        ", so a " + what + " on it would " +
        (wanted == TrackDirection::RecvOnly ? "never receive anything." : "never be listened to.")};
  }
}

[[noreturn]] void no_backend(const char* what) {
  throw InvalidStateError{
      std::string{"this build of the Reactor SDK has no audio backend, so a "} + what +
      " cannot open a device. Rebuild with the reactor::sdk_audio target enabled "
      "(-DREACTOR_SDK_BUILD_AUDIO=ON), or push and receive PCM yourself with "
      "Track::push_audio() and Track::on_audio()."};
}

}  // namespace

bool devices_available() noexcept {
#ifdef REACTOR_SDK_HAVE_MINIAUDIO
  return true;
#else
  return false;
#endif
}

// ── Speaker ──────────────────────────────────────────────────────────────────

struct Speaker::Impl {
  detail::RingBuffer buffer{BUFFER_SAMPLES};
  std::atomic<std::uint64_t> dropped_samples{0};
  std::atomic<std::uint32_t> under_runs{0};

  /// Guards `running`, the device handle, and the rate it was opened at. They are
  /// one piece of state: a frame arriving while stop() is half-way through must
  /// either be dropped or written to a device that is still open, never to one
  /// being closed. The render callback below takes no lock — it only reads the ring
  /// buffer — so holding this across `ma_device_uninit`, which waits for that
  /// callback, cannot deadlock.
  mutable std::mutex device_mutex;
  bool running = false;
  std::uint32_t sample_rate = 0;
  std::uint32_t channels = 0;

#ifdef REACTOR_SDK_HAVE_MINIAUDIO
  ma_device device{};
  bool device_open = false;

  /// miniaudio's callback, on the device's own thread.
  static void render(ma_device* device, void* output, const void* /*input*/,
                     ma_uint32 frame_count) {
    auto* self = static_cast<Impl*>(device->pUserData);
    if (self == nullptr) {
      return;
    }
    const std::size_t wanted = static_cast<std::size_t>(frame_count) * device->playback.channels;
    const std::size_t silence = self->buffer.read(static_cast<std::int16_t*>(output), wanted);
    if (silence != 0) {
      self->under_runs.fetch_add(1, std::memory_order_relaxed);
    }
  }

  /// Open on the first frame, with the rate and channel count the audio actually
  /// has — guessing them and reopening on the first mismatch is audible.
  ///
  /// Called with `device_mutex` held.
  void open_locked(std::uint32_t rate, std::uint32_t channel_count) {
    if (device_open || !running) {
      return;
    }
    ma_device_config config = ma_device_config_init(ma_device_type_playback);
    config.playback.format = ma_format_s16;
    config.playback.channels = channel_count;
    config.sampleRate = rate == 0 ? DEFAULT_SAMPLE_RATE : rate;
    config.dataCallback = &render;
    config.pUserData = this;
    if (ma_device_init(nullptr, &config, &device) != MA_SUCCESS) {
      return;
    }
    if (ma_device_start(&device) != MA_SUCCESS) {
      ma_device_uninit(&device);
      return;
    }
    device_open = true;
    sample_rate = config.sampleRate;
    channels = channel_count;
  }

  void close_locked() {
    if (!device_open) {
      return;
    }
    ma_device_uninit(&device);
    device_open = false;
  }
#else
  void open_locked(std::uint32_t /*rate*/, std::uint32_t /*channel_count*/) {}
  void close_locked() {}
#endif

  /// Queue a frame, opening the device on the first one — or drop it, if this
  /// speaker is not playing. One decision, under one lock.
  void play(const AudioFrame& frame) {
    const std::lock_guard<std::mutex> lock(device_mutex);
    if (!running) {
      return;
    }
    open_locked(frame.sample_rate, frame.channels);
    const std::size_t dropped =
        buffer.write(frame.samples, static_cast<std::size_t>(frame.num_samples));
    if (dropped != 0) {
      dropped_samples.fetch_add(dropped, std::memory_order_relaxed);
    }
  }

  /// The caller's own PCM, under the same lock as a received frame.
  ///
  /// Queued whether or not this speaker is playing, which is the difference from
  /// `play`: audio the caller pushed by hand is theirs to time, and `open_locked`
  /// already declines to open a device for a speaker that is not started.
  void submit_locked(Samples pcm, std::uint32_t rate, std::uint32_t channel_count) {
    const std::lock_guard<std::mutex> lock(device_mutex);
    open_locked(rate, channel_count);
    const std::size_t dropped = buffer.write(pcm.data, pcm.size);
    if (dropped != 0) {
      dropped_samples.fetch_add(dropped, std::memory_order_relaxed);
    }
  }

  /// Stop playing and close, as one step.
  void quiesce() {
    const std::lock_guard<std::mutex> lock(device_mutex);
    running = false;
    close_locked();
  }

  /// Start playing. The device opens on the first frame that arrives.
  void resume() {
    const std::lock_guard<std::mutex> lock(device_mutex);
    running = true;
  }

  /// The rate and channel count the device was opened at, or the defaults.
  std::pair<std::uint32_t, std::uint32_t> format() const {
    const std::lock_guard<std::mutex> lock(device_mutex);
    return {sample_rate == 0 ? DEFAULT_SAMPLE_RATE : sample_rate, channels == 0 ? 1 : channels};
  }
};

// NOLINTNEXTLINE(performance-unnecessary-value-param) — `track` is used
// non-const below (on_audio registers on it), and a Track is two words: a weak
// pointer and a name.
Speaker::Speaker(Track track) : impl_(std::make_shared<Impl>()) {
  require_audio(track, TrackDirection::RecvOnly, "speaker");

  // Registered now, playing only once started: audio that arrives before start()
  // is dropped rather than queued, because a listener hearing four seconds of
  // backlog when they press play is worse than missing it.
  subscription_ = track.on_audio([impl = impl_](const AudioFrame& frame) { impl->play(frame); });
}

Speaker::~Speaker() {
  // Before the buffer goes: the device callback reads it.
  if (impl_) {
    impl_->quiesce();
  }
}

void Speaker::start() {
  if (!devices_available()) {
    no_backend("speaker");
  }
  impl_->resume();
}

void Speaker::stop() {
  impl_->quiesce();
  // After the device is closed and `running` is false, so a frame that was already
  // in flight has either been written before the lock or dropped after it — and
  // either way what is left here goes.
  impl_->buffer.clear();
}

std::uint64_t Speaker::dropped_ms() const noexcept {
  // Under the same lock the device was opened with: these are written when it
  // opens, on whichever thread delivered the first frame.
  const auto [rate, channels] = impl_->format();
  const std::uint64_t frames = impl_->dropped_samples.load(std::memory_order_relaxed) / channels;
  return frames * 1000U / rate;
}

std::uint32_t Speaker::under_runs() const noexcept {
  return impl_->under_runs.load(std::memory_order_relaxed);
}

void Speaker::submit(Samples pcm, std::uint32_t sample_rate, std::uint32_t channels) {
  if (pcm.data == nullptr || pcm.size == 0) {
    return;
  }
  // Through the same door a received frame goes: one lock decides whether this
  // speaker is playing, opens the device if it is not open yet, and writes.
  impl_->submit_locked(pcm, sample_rate, channels == 0 ? 1 : channels);
}

// ── Microphone ───────────────────────────────────────────────────────────────

struct Microphone::Impl {
  Track track;
  std::atomic<std::uint64_t> blocks_sent{0};
  std::atomic<std::uint64_t> blocks_refused{0};

  mutable std::mutex refusal_mutex;
  std::string last_refusal;

  std::mutex device_mutex;
  /// Atomic rather than under `device_mutex`, and this is not a preference: the
  /// capture callback reads it on the device's own thread, and `close()` holds that
  /// mutex across `ma_device_uninit`, which waits for that very callback to
  /// finish. Locking there would deadlock the two against each other.
  std::atomic<bool> running{false};

  explicit Impl(Track attached) : track(std::move(attached)) {}

  /// Hand one captured block to the track.
  ///
  /// A refusal is recorded rather than thrown: this runs on the device's thread,
  /// where an exception has nowhere to go, and the usual cause — the track is not
  /// published, or the session left ready — is a state the caller can read back.
  void deliver(const std::int16_t* samples, std::size_t count, std::uint32_t rate,
               std::uint32_t channels) {
    // A block captured while stop() is closing the device belongs to a microphone
    // the caller has already stopped feeding, and pushing it would be audible on
    // the other end after they asked for silence.
    if (!running.load(std::memory_order_acquire)) {
      return;
    }
    try {
      track.push_audio(Samples{samples, count}, rate, channels);
      blocks_sent.fetch_add(1, std::memory_order_relaxed);
    } catch (const ReactorError& error) {
      blocks_refused.fetch_add(1, std::memory_order_relaxed);
      const std::lock_guard<std::mutex> lock(refusal_mutex);
      last_refusal = error.what();
    }
  }

#ifdef REACTOR_SDK_HAVE_MINIAUDIO
  ma_device device{};
  bool device_open = false;

  static void capture(ma_device* device, void* /*output*/, const void* input,
                      ma_uint32 frame_count) {
    auto* self = static_cast<Impl*>(device->pUserData);
    if (self == nullptr || input == nullptr) {
      return;
    }
    self->deliver(static_cast<const std::int16_t*>(input),
                  static_cast<std::size_t>(frame_count) * device->capture.channels,
                  device->sampleRate, device->capture.channels);
  }

  void open() {
    const std::lock_guard<std::mutex> lock(device_mutex);
    if (device_open) {
      return;
    }
    ma_device_config config = ma_device_config_init(ma_device_type_capture);
    config.capture.format = ma_format_s16;
    // Mono at 48 kHz: what the source expects, so nothing downstream resamples.
    config.capture.channels = 1;
    config.sampleRate = DEFAULT_SAMPLE_RATE;
    config.dataCallback = &capture;
    config.pUserData = this;
    if (ma_device_init(nullptr, &config, &device) != MA_SUCCESS) {
      throw InvalidStateError{"could not open a capture device"};
    }
    if (ma_device_start(&device) != MA_SUCCESS) {
      ma_device_uninit(&device);
      throw InvalidStateError{"could not start the capture device"};
    }
    device_open = true;
  }

  void close() {
    const std::lock_guard<std::mutex> lock(device_mutex);
    if (!device_open) {
      return;
    }
    ma_device_uninit(&device);
    device_open = false;
  }
#else
  void open() {}
  void close() {}
#endif
};

// impl_ is assigned in the body rather than in a member initialiser, and
// deliberately: require_audio has to refuse an unusable track *before* anything
// is allocated for it, and a member initialiser runs first.
// NOLINTNEXTLINE(cppcoreguidelines-prefer-member-initializer,performance-unnecessary-value-param)
Microphone::Microphone(Track track) {
  require_audio(track, TrackDirection::SendOnly, "microphone");
  impl_ = std::make_shared<Impl>(std::move(track));
}

Microphone::~Microphone() {
  if (impl_) {
    // False before close: the capture callback reads it, and a block that arrives
    // while the device is being torn down has nowhere useful to go.
    impl_->running.store(false, std::memory_order_release);
    impl_->close();
  }
}

void Microphone::start() {
  if (!devices_available()) {
    no_backend("microphone");
  }
  if (!impl_->track.published()) {
    // Publishing is what puts a sender behind the slot. Starting anyway would
    // capture happily and have every block refused.
    throw InvalidStateError{"publish() the track \"" + impl_->track.name() +
                            "\" before starting a microphone on it, or every captured block "
                            "is refused."};
  }
  impl_->running.store(true, std::memory_order_release);
  impl_->open();
}

void Microphone::stop() {
  impl_->running.store(false, std::memory_order_release);
  impl_->close();
}

std::uint64_t Microphone::blocks_sent() const noexcept {
  return impl_->blocks_sent.load(std::memory_order_relaxed);
}

std::uint64_t Microphone::blocks_refused() const noexcept {
  return impl_->blocks_refused.load(std::memory_order_relaxed);
}

std::string Microphone::last_refusal() const {
  const std::lock_guard<std::mutex> lock(impl_->refusal_mutex);
  return impl_->last_refusal;
}

}  // namespace reactor::audio
