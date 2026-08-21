#include "detail/log.hpp"

#include <iostream>
#include <mutex>
#include <set>

namespace reactor::detail {
namespace {

std::mutex& log_mutex() {
  static std::mutex mutex;
  return mutex;
}

}  // namespace

void log_warn(const std::string& message) {
  const std::lock_guard<std::mutex> lock(log_mutex());
  // stderr, not stdout: a caller's program may be writing data on stdout, and a
  // diagnostic that corrupts it is worse than no diagnostic.
  std::cerr << "reactor-sdk: " << message << '\n';
}

void log_warn_once(const std::string& key, const std::string& message) {
  {
    const std::lock_guard<std::mutex> lock(log_mutex());
    static std::set<std::string> seen;
    if (!seen.insert(key).second) {
      return;
    }
  }
  log_warn(message);
}

}  // namespace reactor::detail
