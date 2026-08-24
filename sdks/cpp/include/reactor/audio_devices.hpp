// The devices the SDK will not open on its own.
//
// The core is pinned to the synthetic audio module and cannot be talked out of
// it: `reactor_create` takes its mode from an environment variable, and a library
// whose audience is scripts and servers must never let an env var put a live
// microphone on the wire because a model happened to declare a sendonly audio
// track. Nothing on the mandatory path here can open a device.
//
// Real devices are therefore *this* header, in a target of its own:
//
//     target_link_libraries(app PRIVATE reactor::sdk reactor::sdk_audio)
//
//     reactor::audio::Speaker speaker{client.track("main_audio")};
//     speaker.start();
//
// Linking `reactor::sdk` alone brings in no audio library, no device enumeration
// and none of this.
#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <reactor/track.hpp>
#include <string>

namespace reactor::audio {

/// Plays what arrives on a recvonly audio track.
///
/// The queue in here is a jitter buffer, and that is the whole design: audio
/// arrives in ~10 ms frames on the library's own thread, and a playback device
/// asks for samples on *its* clock. Neither waits for the other, so the buffer
/// absorbs the difference — and when it cannot, the two failure modes are worth
/// telling apart. `dropped_ms` counts audio thrown away because the buffer was
/// full (the device is slower than the stream); `under_runs` counts silence
/// played because it was empty (the stream is slower than the device).
class Speaker {
 public:
  /// Attach to `track` without starting playback.
  ///
  /// Throws `InvalidStateError` if the track is not a recvonly audio track: a
  /// speaker on anything else would play nothing, forever, without saying why.
  explicit Speaker(Track track);

  ~Speaker();

  Speaker(const Speaker&) = delete;
  Speaker& operator=(const Speaker&) = delete;
  Speaker(Speaker&&) = delete;
  Speaker& operator=(Speaker&&) = delete;

  /// Open the device and start playing. Idempotent.
  ///
  /// The device is opened on the first frame rather than here, because its sample
  /// rate and channel count come from the audio itself — guessing them and
  /// reopening on the first mismatch is audible.
  void start();

  /// Stop playing and close the device. Idempotent, and safe to call from a
  /// handler.
  void stop();

  /// Milliseconds of audio dropped because the buffer was full.
  std::uint64_t dropped_ms() const noexcept;

  /// Times the device asked for samples the buffer did not have.
  std::uint32_t under_runs() const noexcept;

  /// Push PCM in directly, without a track.
  ///
  /// For a caller mixing their own audio, and what the track handler calls.
  void submit(Samples pcm, std::uint32_t sample_rate, std::uint32_t channels);

 private:
  struct Impl;
  std::shared_ptr<Impl> impl_;
  Subscription subscription_;
};

/// Captures from a real microphone and pushes into a published sendonly track.
///
/// Nothing here starts by itself: constructing a `Microphone` opens no device, and
/// `start()` is the only thing that does.
class Microphone {
 public:
  /// Attach to `track` without capturing.
  ///
  /// Throws `InvalidStateError` if the track is not a sendonly audio track.
  explicit Microphone(Track track);

  ~Microphone();

  Microphone(const Microphone&) = delete;
  Microphone& operator=(const Microphone&) = delete;
  Microphone(Microphone&&) = delete;
  Microphone& operator=(Microphone&&) = delete;

  /// Open the device and start pushing captured audio into the track.
  ///
  /// The track has to be published first: publishing is what puts a sender behind
  /// the slot, and a push before it is refused rather than dropped.
  void start();

  void stop();

  /// Blocks of PCM handed to the track so far.
  std::uint64_t blocks_sent() const noexcept;

  /// Captured blocks the track refused, with the reason of the last one.
  std::uint64_t blocks_refused() const noexcept;
  std::string last_refusal() const;

 private:
  struct Impl;
  std::shared_ptr<Impl> impl_;
};

/// Whether this build can open real devices.
///
/// False in a build made without the audio backend — the classes above then throw
/// on `start()` rather than silently playing nothing.
bool devices_available() noexcept;

}  // namespace reactor::audio
