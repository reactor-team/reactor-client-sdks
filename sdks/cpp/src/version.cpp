#include "reactor/version.hpp"

namespace reactor {

std::string_view version() noexcept { return REACTOR_SDK_VERSION; }

}  // namespace reactor
