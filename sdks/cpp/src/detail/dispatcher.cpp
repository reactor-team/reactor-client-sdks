#include "detail/dispatcher.hpp"

#include <exception>
#include <string>
#include <thread>
#include <utility>

#include "detail/log.hpp"

namespace reactor::detail {

Dispatcher::Dispatcher(Executor executor) : executor_(std::move(executor)) {
  if (!executor_) {
    // The state, not `this`: the thread may outlive this object by a turn of its
    // loop, and everything it reads has to survive that.
    thread_ = std::thread([state = state_] { run(state); });
  }
}

Dispatcher::~Dispatcher() { stop(); }

void Dispatcher::post(std::function<void()> work) {
  if (!work) {
    return;
  }
  if (executor_) {
    // The host's loop owns the ordering from here. Called from a library thread,
    // which is why the executor's contract says it must tolerate that.
    executor_(std::move(work));
    return;
  }
  {
    const std::lock_guard<std::mutex> lock(state_->mutex);
    if (state_->stopped) {
      // Teardown has begun. Dropping is right: the only thing left to deliver an
      // event to is a client that is going away.
      return;
    }
    state_->queue.push_back(std::move(work));
  }
  state_->ready.notify_one();
}

void Dispatcher::stop() noexcept {
  {
    const std::lock_guard<std::mutex> lock(state_->mutex);
    if (state_->stopped) {
      return;
    }
    state_->stopped = true;
    state_->queue.clear();
  }
  state_->ready.notify_all();
  if (!thread_.joinable()) {
    return;
  }
  if (thread_.get_id() == std::this_thread::get_id()) {
    // Stopped from the dispatcher's own thread, which happens when a handler drops
    // the last reference to its client: the destructor is running inside the work
    // this thread is executing. Joining would be a deadlock, and the standard
    // reports it by throwing — out of a `noexcept` function, so the process would
    // go instead of the client. Detaching is safe because the loop owns only
    // `state`, which outlives this object, and it stops at the next turn.
    thread_.detach();
    return;
  }
  thread_.join();
}

void Dispatcher::run(const std::shared_ptr<State>& state) {
  while (true) {
    std::function<void()> work;
    {
      std::unique_lock<std::mutex> lock(state->mutex);
      state->ready.wait(lock, [&state] { return state->stopped || !state->queue.empty(); });
      if (state->stopped) {
        return;
      }
      work = std::move(state->queue.front());
      state->queue.pop_front();
    }
    // Outside the lock: a handler may register or remove another handler, or post
    // more work, and holding the lock across it would deadlock on the first one
    // that did.
    //
    // And nothing below may touch the Dispatcher: this work can have destroyed it,
    // which is why `state` is a separate object and this function is static.
    //
    // The handler's exceptions stop here. They belong to the host, and there is no
    // caller on this thread to hand one to: escaping `work()` would leave the
    // thread function with an uncaught exception, which is std::terminate — a
    // typo in one status handler taking down a process that was otherwise fine.
    // Reported once per kind of message, because a handler that throws on every
    // event throws at the rate the events arrive.
    try {
      work();
    } catch (const std::exception& error) {
      log_warn_once(std::string{"handler-threw:"} + error.what(),
                    std::string{"an event handler threw, and the exception was "
                                "dropped here rather than ending the process: "} +
                        error.what());
    } catch (...) {
      log_warn_once("handler-threw:unknown",
                    "an event handler threw something that is not a std::exception, and it "
                    "was dropped here rather than ending the process");
    }
  }
}

}  // namespace reactor::detail
