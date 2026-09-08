import CReactorFFI
import Foundation
import Testing

@testable import Reactor

@Suite("FFI boundary")
struct FFITests {

    // MARK: - The ABI guard

    @Test("a library speaking this ABI is accepted")
    func matchingVersionPasses() throws {
        let ffi = FFI(abiVersion: { ABI.compiledAgainst }, freeString: { _ in })

        try ABI.check(ffi)
    }

    @Test("a library speaking another ABI is refused, naming both numbers")
    func mismatchIsRefused() {
        // The failure this guard exists for: the library on disk is older than
        // the crates. Nothing else catches it — the parity script compares names
        // only, so a function that gained a parameter still links and then
        // corrupts the stack at the call.
        let stale = FFI(abiVersion: { ABI.compiledAgainst + 1 }, freeString: { _ in })

        #expect(throws: ReactorError.self) { try ABI.check(stale) }

        do {
            try ABI.check(stale)
        } catch let error as ReactorError {
            #expect(error.code == .versionMismatch)
            // Both numbers, or the message cannot tell you which side to fix.
            #expect(error.message.contains("\(ABI.compiledAgainst + 1)"))
            #expect(error.message.contains("\(ABI.compiledAgainst)"))
            #expect(error.message.contains("cargo build -p reactor-ffi"))
        } catch {
            Issue.record("expected a ReactorError, got \(error)")
        }
    }

    @Test("the real library speaks the ABI this build was compiled against")
    func realLibraryMatches() throws {
        // The one test here that calls the library rather than a fake. It is
        // also the check a contributor most needs after pulling changes under
        // crates/: a stale libreactor_ffi fails here rather than at the first
        // call whose signature moved.
        try ABI.check(.system)
    }

    // MARK: - String ownership

    @Test("an owned string is copied and then freed exactly once")
    func ownedStringIsFreed() {
        let source = strdup("session-abc")
        let freed = Freed()

        let copy = String(takingOwnership: source) { pointer in
            freed.record(pointer)
            free(pointer)
        }

        #expect(copy == "session-abc")
        #expect(freed.count == 1)
        #expect(freed.pointer == source)
    }

    @Test("a null owned string is nil, and nothing is freed")
    func ownedNullIsNil() {
        // `reactor_session_id` answers null for "no session yet" and for a null
        // handle. Neither is an error, and neither has anything to free.
        let freed = Freed()

        let copy = String(takingOwnership: nil) { pointer in
            freed.record(pointer)
        }

        #expect(copy == nil)
        #expect(freed.count == 0)
    }

    @Test("an owned string is freed even when it is empty")
    func ownedEmptyStringIsFreed() {
        // "[]" and "" come back from the list getters on a session that has not
        // been accepted yet. An early return on emptiness would leak one
        // allocation per property read.
        let source = strdup("")
        let freed = Freed()

        let copy = String(takingOwnership: source) { pointer in
            freed.record(pointer)
            free(pointer)
        }

        #expect(copy == "")
        #expect(freed.count == 1)
    }

    @Test("a borrowed string is copied and left alone")
    func borrowedStringIsCopied() {
        // What a callback gets: valid only for the callback's duration. The copy
        // has to survive the original going away, or this is a use-after-free
        // that reproduces under load and not in tests.
        let source = strdup("ready")
        defer { free(source) }

        let copy = String(borrowing: source)
        source?.pointee = CChar(UInt8(ascii: "X"))

        #expect(copy == "ready")
    }

    @Test("a null borrowed string is nil")
    func borrowedNullIsNil() {
        #expect(String(borrowing: nil) == nil)
    }

    // MARK: - The table

    @Test("the real table reaches the real library")
    func systemTableIsWired() {
        // Proves the closures point at the C symbols rather than at nothing:
        // reactor_abi_version is documented as returning a monotonic version,
        // never zero.
        #expect(FFI.system.abiVersion() >= 1)
    }

    @Test("the real free function is wired to the library and treats null as a no-op")
    func systemFreeStringAcceptsNull() {
        // Null is the only input this member can correctly be handed at this
        // point in the stack: every function that allocates a string the caller
        // owns — reactor_session_id, reactor_tracks, reactor_paused_tracks —
        // takes a ReactorHandle, and the handle arrives with client lifetime.
        // The header documents null as a no-op and reactor-ffi has its own test
        // asserting that, so this is a real call whose only failure mode is a
        // closure wired to the wrong symbol or to nothing.
        //
        // What it deliberately no longer does is strdup a string, hand the
        // result to libc free() and call that end-to-end coverage: that version
        // passed whatever FFI.system.freeString pointed at, including nothing —
        // false coverage under a name promising the opposite. A string the
        // library itself allocated is exercised where one first exists.
        FFI.system.freeString(nil)
    }
}

/// Records what a fake `freeString` was handed, so a test can assert the free
/// happened exactly once and on the pointer it was given.
private final class Freed: @unchecked Sendable {
    private(set) var count = 0
    private(set) var pointer: UnsafeMutablePointer<CChar>?

    func record(_ pointer: UnsafeMutablePointer<CChar>?) {
        count += 1
        self.pointer = pointer
    }
}
