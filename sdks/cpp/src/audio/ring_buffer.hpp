// The jitter buffer between the library's thread and the device's.
//
// Audio arrives in ~10 ms frames on a thread the library owns, and a device asks
// for samples on its own clock. Neither waits for the other, so something has to
// absorb the difference — and the two ways it fails are different enough to count
// separately: dropping (the device is slower than the stream) and under-running
// (the stream is slower than the device).
//
// Interleaved samples, one lock, no allocation after construction. A lock-free
// ring would be the obvious next step and is not worth it yet: the contention is
// one producer against one consumer, ~100 times a second.
#pragma once

#include <cstdint>
#include <mutex>
#include <vector>

namespace reactor::audio::detail {

class RingBuffer {
 public:
  /// `capacity_samples` is a count of interleaved samples, not frames.
  explicit RingBuffer(std::size_t capacity_samples) : buffer_(capacity_samples) {}

  /// Copy `count` samples in, dropping the oldest when there is no room.
  ///
  /// Dropping the *oldest* rather than refusing the newest: in playback, stale
  /// audio is worth less than current audio, and the alternative is a growing
  /// delay that never recovers.
  ///
  /// Returns how many samples were dropped to make room.
  std::size_t write(const std::int16_t* samples, std::size_t count) {
    const std::lock_guard<std::mutex> lock(mutex_);
    std::size_t dropped = 0;
    if (count >= buffer_.size()) {
      // More than the whole buffer: keep the tail, which is the newest audio.
      dropped = size_ + (count - buffer_.size());
      samples += count - buffer_.size();
      count = buffer_.size();
      read_ = 0;
      size_ = 0;
    }
    if (size_ + count > buffer_.size()) {
      const std::size_t overflow = size_ + count - buffer_.size();
      read_ = (read_ + overflow) % buffer_.size();
      size_ -= overflow;
      dropped += overflow;
    }
    for (std::size_t index = 0; index < count; ++index) {
      buffer_[(read_ + size_ + index) % buffer_.size()] = samples[index];
    }
    size_ += count;
    return dropped;
  }

  /// Fill `count` samples out, padding with silence when short.
  ///
  /// Returns how many samples had to be silence — an under-run, which is audible
  /// as a click and worth counting.
  std::size_t read(std::int16_t* out, std::size_t count) {
    const std::lock_guard<std::mutex> lock(mutex_);
    const std::size_t available = size_ < count ? size_ : count;
    for (std::size_t index = 0; index < available; ++index) {
      out[index] = buffer_[(read_ + index) % buffer_.size()];
    }
    read_ = (read_ + available) % buffer_.size();
    size_ -= available;
    for (std::size_t index = available; index < count; ++index) {
      out[index] = 0;
    }
    return count - available;
  }

  std::size_t size() const {
    const std::lock_guard<std::mutex> lock(mutex_);
    return size_;
  }

  std::size_t capacity() const { return buffer_.size(); }

  void clear() {
    const std::lock_guard<std::mutex> lock(mutex_);
    read_ = 0;
    size_ = 0;
  }

 private:
  mutable std::mutex mutex_;
  std::vector<std::int16_t> buffer_;
  std::size_t read_ = 0;
  std::size_t size_ = 0;
};

}  // namespace reactor::audio::detail
