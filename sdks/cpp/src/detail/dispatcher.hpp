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
#include <memory>
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
  /// Everything the thread touches — and, deliberately, all it can.
  ///
  /// Held behind a `shared_ptr` rather than as members because the thread is
  /// allowed to outlive the `Dispatcher`. A handler may drop the last reference to
  /// the client it was handed ("on disconnected, throw the client away" is an
  /// ordinary thing to write), and the dispatched work holds a strong one while it
  /// runs — so the client's destructor, and this object's, land on *this* thread,
  /// mid-`work()`. The loop still has to see that it was stopped, and it cannot
  /// read that from a `Dispatcher` that no longer exists.
  struct State {
    std::mutex mutex;
    std::condition_variable ready;
    std::deque<std::function<void()>> queue;
    bool stopped = false;
  };

  /// Static, and takes the state as an argument: the loop has no `this` to touch
  /// after `work()` returns, because by then there may be none.
  static void run(const std::shared_ptr<State>& state);

  Executor executor_;
  std::shared_ptr<State> state_ = std::make_shared<State>();
  std::thread thread_;
};

}  // namespace reactor::detail
