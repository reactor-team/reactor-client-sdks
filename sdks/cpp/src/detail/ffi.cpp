#include "detail/ffi.hpp"

#include <mutex>
#include <string>

#include "reactor/errors.hpp"

namespace reactor::detail {
namespace {

/// Set by FfiOverride. Atomic because a callback may read the table from one of
/// the library's own threads while a test on the main thread swaps it.
///
/// Mutable and global by nature: it is a process-wide hook, and the alternative —
/// threading a table reference through every object — would put a test-only
/// parameter in the public constructor of everything.
// NOLINTNEXTLINE(cppcoreguidelines-avoid-non-const-global-variables)
std::atomic<const Ffi*> g_override{nullptr};

const Ffi& real_table() {
  static const Ffi table = [] {
    Ffi filled;
    // Named on both sides, so a wrong pairing is visible rather than positional.
#define REACTOR_FFI_ASSIGN(name, symbol) filled.name = &(symbol);
    REACTOR_FFI_EACH(REACTOR_FFI_ASSIGN)
#undef REACTOR_FFI_ASSIGN
    return filled;
  }();
  return table;
}

}  // namespace

void require_supported_abi(const Ffi& table) {
  const std::uint32_t loaded = table.abi_version();
  if (loaded == REACTOR_ABI_VERSION) {
    return;
  }
  throw VersionMismatchError{
      "libreactor_ffi reports ABI version " + std::to_string(loaded) +
      ", but this SDK was built against " + std::to_string(REACTOR_ABI_VERSION) +
      ". The library on disk is not the one these headers describe — rebuild it "
      "with `cargo build -p reactor-ffi --release`, or point the build at a "
      "matching one with -DREACTOR_FFI_LIB_DIR. Running anyway would corrupt the "
      "stack at the first call whose signature moved, which looks like a hang "
      "rather than a version error."};
}

const Ffi& ffi() {
  if (const Ffi* overridden = g_override.load(std::memory_order_acquire)) {
    // A test's table. Its version is the test's business, not this check's.
    return *overridden;
  }
  // Once per process, on the first real call: a mismatch is not going to fix
  // itself, and checking per call would put a branch on the frame-push path.
  static std::once_flag checked;
  std::call_once(checked, [] { require_supported_abi(real_table()); });
  return real_table();
}

FfiOverride::FfiOverride(const Ffi* table) noexcept
    : previous_(g_override.exchange(table, std::memory_order_acq_rel)) {}

FfiOverride::~FfiOverride() { g_override.store(previous_, std::memory_order_release); }

}  // namespace reactor::detail
