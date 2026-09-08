import CReactorFFI
import Testing

@testable import Reactor

/// What a scaffold can actually prove: that the package builds, and that the C
/// header resolves through the module map. The second one is the whole point of
/// this pull request — if the relative path in `module.modulemap` is wrong, or
/// the crate moves its header, this test is what says so, rather than the first
/// PR that tries to call a function.
@Suite("Scaffold")
struct ScaffoldTests {

    @Test("the C ABI header resolves through the module map")
    func abiVersionIsVisible() {
        // A macro from reactor_ffi.h, so reading it at all means the header was
        // found, parsed, and imported. Compared against the floor rather than
        // against 1: the number is the FFI's to move, and pinning it here would
        // make this test fail for the one reason it is not about.
        #expect(REACTOR_ABI_VERSION >= 1)
    }

    @Test("the package reports a version")
    func versionIsSet() {
        #expect(!ReactorSDK.version.isEmpty)
    }
}
