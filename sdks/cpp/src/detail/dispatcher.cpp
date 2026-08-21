#include "detail/dispatcher.hpp"

#include <utility>

namespace reactor::detail {

Dispatcher::Dispatcher(Executor executor) : executor_(std::move(executor)) {
  if (!executor_) {
    thread_ = std::thread([this] { run(); });
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
    const std::lock_guard<std::mutex> lock(mutex_);
    if (stopped_) {
      // Teardown has begun. Dropping is right: the only thing left to deliver an
      // event to is a client that is going away.
      return;
    }
    queue_.push_back(std::move(work));
  }
  ready_.notify_one();
}

void Dispatcher::stop() noexcept {
  {
    const std::lock_guard<std::mutex> lock(mutex_);
    if (stopped_) {
      return;
    }
    stopped_ = true;
    queue_.clear();
  }
  ready_.notify_all();
  if (thread_.joinable()) {
    thread_.join();
  }
}

void Dispatcher::run() {
  while (true) {
    std::function<void()> work;
    {
      std::unique_lock<std::mutex> lock(mutex_);
      ready_.wait(lock, [this] { return stopped_ || !queue_.empty(); });
      if (stopped_) {
        return;
      }
      work = std::move(queue_.front());
      queue_.pop_front();
    }
    // Outside the lock: a handler may register or remove another handler, or post
    // more work, and holding the lock across it would deadlock on the first one
    // that did.
    work();
  }
}

}  // namespace reactor::detail
