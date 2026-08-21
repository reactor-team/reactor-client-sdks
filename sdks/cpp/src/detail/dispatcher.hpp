// Where control-event handlers run.
//
// The FFI delivers control events on a thread of its own, and a handler there
// would be touching the host's concurrency primitives from a foreign thread —
// which for most hosts is exactly the thing not to do. So the SDK moves them:
// onto one thread it owns, or onto the executor a host supplied, and never onto
// the thread the FFI called on.
//
// One thread, not a pool, because handlers are then serialised: two of them never
// run at once, so a handler can touch its own state without a lock. That matters
// more than the throughput a pool would buy — control events are low-rate by
// construction.
//
// Media is the deliberate exception and does not come through here. `on_frame`
// runs inline on the FFI's delivery thread, because blocking there *is* the
// backpressure: the FFI keeps only the newest frame while the handler runs. A
// queue would trade a bounded drop for unbounded latency and memory.
#pragma once

#include <condition_variable>
#include <deque>
#include <functional>
#include <mutex>
#include <thread>

#include "reactor/reactor.hpp"

namespace reactor::detail {

class Dispatcher {
 public:
  /// With an executor, hand it every event. Without one, start a thread.
  explicit Dispatcher(Executor executor);

  ~Dispatcher();

  Dispatcher(const Dispatcher&) = delete;
  Dispatcher& operator=(const Dispatcher&) = delete;
  Dispatcher(Dispatcher&&) = delete;
  Dispatcher& operator=(Dispatcher&&) = delete;

  /// Run `work` on the dispatcher, later. Never runs it inline.
  void post(std::function<void()> work);

  /// Stop accepting work and join the thread.
  ///
  /// Whatever is still queued is dropped. It is only reached during teardown, and
  /// running a handler for a client that is being destroyed is worth less than
  /// finishing the teardown promptly.
  void stop() noexcept;

 private:
  void run();

  Executor executor_;

  std::mutex mutex_;
  std::condition_variable ready_;
  std::deque<std::function<void()>> queue_;
  bool stopped_ = false;
  std::thread thread_;
};

}  // namespace reactor::detail
