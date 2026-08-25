import CReactorFFI

/// Every call into `libreactor_ffi` goes through this table.
///
/// Two things it buys.
///
/// **The canonical header, never a copy.** The symbols come from
/// `crates/reactor-ffi/include/reactor_ffi.h` through the module map, so the
/// compiler is the parity check for types and the linker is the parity check for
/// names. The Python binding hand-wrote its declarations and became a third
/// place the ABI could drift; there is no equivalent here to drift.
///
/// **A table, so tests can lie to it.** One indirect call per operation, and in
/// exchange a unit test can hand the SDK a fake library — which is the only way
/// to cover teardown and the refuse-do-not-fail-quietly table without a live
/// session, a network and a GPU.
///
/// The members are Swift closures rather than `@convention(c)` function
/// pointers, deliberately: a C function pointer cannot capture, and a fake that
/// cannot capture cannot record what the SDK asked it for.
///
/// ## The table grows with the stack
///
/// It holds the symbols the SDK actually calls today, and each pull request adds
/// the ones it needs. A table listing all 29 exports up front would be 27 lines
/// of code nothing calls, and `check-abi-parity.py` would have nothing to say
/// about them either way — a binding is free to bind a subset.
///
/// `reactor_create` will never be among them. It takes its audio device mode
/// from an environment variable, and a library whose audience is apps and
/// scripts must never let an env var put a live microphone on the wire because a
/// model happened to declare a sendonly audio track. The SDK uses
/// `reactor_create_with_adm` with mode 0 (synthetic) and cannot do otherwise,
/// because the other function is absent from this table —
/// `scripts/check-abi-parity.py` is what keeps it absent.
struct FFI: Sendable {

    /// `reactor_abi_version` — the ABI the loaded library speaks.
    var abiVersion: @Sendable () -> UInt32

    /// `reactor_free_string` — releases a string the FFI heap-allocated.
    ///
    /// Only for the strings the header says the caller owns. Passing it the
    /// static string `reactor_status` returns corrupts the heap, and passing it
    /// a borrowed callback string is a double free. ``Swift/String/init(takingOwnership:freeing:)``
    /// is the only place the SDK calls this, so the distinction is made once.
    var freeString: @Sendable (UnsafeMutablePointer<CChar>?) -> Void
}

extension FFI {

    /// The real library.
    ///
    /// Every member is named on both sides. Swift's memberwise initialiser
    /// requires the labels, which rules out the failure the C++ SDK needed a
    /// macro to prevent: two symbols with identical signatures silently swapped,
    /// so the binding pauses a track when asked to publish it.
    static let system = FFI(
        abiVersion: { reactor_abi_version() },
        freeString: { pointer in reactor_free_string(pointer) }
    )
}
