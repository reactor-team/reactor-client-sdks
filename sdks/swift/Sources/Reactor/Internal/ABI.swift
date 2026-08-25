import CReactorFFI

/// The check that has no substitute.
///
/// `scripts/check-abi-parity.py` compares the hand-written copies of the C ABI
/// **by function name only** — arity and types are not checked and cannot be. So
/// a function that gained a parameter still links, still resolves, and corrupts
/// the stack at the call. It does not fail at load: it looks like a hang, or like
/// the operation silently doing nothing, never like a version error. Twice now
/// the library on disk was simply older than the crates.
///
/// Comparing the two halves is what catches it: ``compiledAgainst`` is what the
/// header this SDK was built against says, and ``FFI/abiVersion`` is what the
/// library that actually loaded says.
enum ABI {

    /// The ABI version this SDK was compiled against.
    ///
    /// Read from the header rather than written down here — a number of our own
    /// would be one more copy of the ABI to drift.
    static let compiledAgainst = UInt32(REACTOR_ABI_VERSION)

    /// Throw unless `ffi`'s library speaks the ABI this SDK was compiled against.
    ///
    /// Called once, when a client is created — the first moment there is a
    /// caller to report to, and before any call whose signature could have moved.
    static func check(_ ffi: FFI = .system) throws {
        let loaded = ffi.abiVersion()
        guard loaded != compiledAgainst else { return }

        throw ReactorError(
            .versionMismatch,
            """
            libreactor_ffi reports ABI version \(loaded), but this SDK was built \
            against \(compiledAgainst). The library on disk is not the one these \
            declarations describe — rebuild it with `cargo build -p reactor-ffi \
            --release`, or point REACTOR_FFI_LIB at a matching one. Running anyway \
            would corrupt the stack at the first call whose signature moved, which \
            looks like a hang rather than a version error.
            """,
            operation: "load"
        )
    }
}
