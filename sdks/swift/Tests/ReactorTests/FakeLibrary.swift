import CReactorFFI
import Foundation

@testable import Reactor

/// A fake `libreactor_ffi`, and the reason ``FFI`` is a table of closures.
///
/// It records what the SDK asked for and can fire the library's callbacks on
/// demand, which is the only way to cover teardown and the refusal table without
/// a live session, a network and a GPU.
///
/// ## The handle is fabricated, and that is only safe because of the table
///
/// `handle` is an allocation this class owns, not a client the library made. It
/// must never reach the real `reactor_destroy`, which would dereference it as a
/// live pointer — a segfault in an unrelated test, or after the run passes,
/// depending on when it happens. The Python suite needs a conftest guard for
/// exactly this; here it is structural instead, because a `Reactor` calls only
/// the table it was given, and a `Reactor` built with this fake can only ever
/// reach this fake's `destroy`.
final class FakeLibrary: @unchecked Sendable {

    /// What the SDK passed to `reactor_create_with_adm`.
    struct CreateCall {
        var apiURL: String?
        var model: String?
        var jwt: String?
        var local: Int32
        var admMode: Int32
    }

    private struct State {
        var createCalls: [CreateCall] = []
        var callbacks: ReactorCallbacks?
        var destroyCount = 0
        var freedStrings = 0
        var connectCalls = 0
        var disconnectCalls = 0
        var reconnectCalls = 0
        var status = "disconnected"
        var sessionID: String?
        var lastCompletion: (fn: reactor_completion_fn, userdata: UnsafeMutableRawPointer)?
    }

    private let state = Locked(State())

    /// A pointer that is unique and dereferenceable but is not a client.
    private let handle = UnsafeMutableRawPointer.allocate(byteCount: 1, alignment: 1)

    /// What `reactor_destroy` answers: 0 (quiesced) or -1 (a callback is still
    /// running and the pointers must be kept alive).
    var destroyResult: Int32 = 0

    /// Which ABI version the fake library claims to speak.
    var abiVersion: UInt32 = ABI.compiledAgainst

    deinit {
        handle.deallocate()
    }

    // MARK: - What the tests read

    var createCalls: [CreateCall] { state.withLock { $0.createCalls } }
    var destroyCount: Int { state.withLock { $0.destroyCount } }
    var freedStrings: Int { state.withLock { $0.freedStrings } }
    var connectCalls: Int { state.withLock { $0.connectCalls } }
    var disconnectCalls: Int { state.withLock { $0.disconnectCalls } }
    var reconnectCalls: Int { state.withLock { $0.reconnectCalls } }

    /// Whether an operation is waiting on a completion the fake has not fired.
    var hasPendingCompletion: Bool { state.withLock { $0.lastCompletion != nil } }

    // MARK: - What the tests drive

    func setStatus(_ status: String) {
        state.withLock { $0.status = status }
    }

    func setSessionID(_ sessionID: String?) {
        state.withLock { $0.sessionID = sessionID }
    }

    /// Fire `on_status`, the way the library's control thread would.
    func fireStatus(_ status: String) {
        guard let callbacks = state.withLock({ $0.callbacks }),
            let onStatus = callbacks.on_status
        else { return }
        status.withCString { onStatus($0, callbacks.userdata) }
    }

    /// Fire `on_error` with a payload, the way the library's control thread would.
    func fireError(_ payload: String) {
        guard let callbacks = state.withLock({ $0.callbacks }),
            let onError = callbacks.on_error
        else { return }
        payload.withCString { onError($0, callbacks.userdata) }
    }

    /// Answer the operation currently in flight.
    ///
    /// The library promises exactly one completion per call, so this consumes the
    /// recorded one — calling it twice fires nothing the second time, which is
    /// what the real library does and what the SDK is written against.
    func completeLastCall(ok: Bool, result: String? = "{}", error: String? = nil) {
        guard
            let call = state.withLock({
                state -> (fn: reactor_completion_fn, userdata: UnsafeMutableRawPointer)? in
                defer { state.lastCompletion = nil }
                return state.lastCompletion
            })
        else { return }

        withOptionalCString(result) { resultPointer in
            withOptionalCString(error) { errorPointer in
                call.fn(ok ? 1 : 0, resultPointer, errorPointer, call.userdata)
            }
        }
    }

    // MARK: - The table

    var table: FFI {
        FFI(
            abiVersion: { [self] in abiVersion },
            freeString: { [self] pointer in
                state.withLock { $0.freedStrings += 1 }
                free(pointer)
            },
            createWithADM: { [self] apiURL, model, jwt, local, callbacks, admMode in
                state.withLock { state in
                    state.createCalls.append(
                        CreateCall(
                            apiURL: String(borrowing: apiURL),
                            model: String(borrowing: model),
                            jwt: String(borrowing: jwt),
                            local: local,
                            admMode: admMode))
                    state.callbacks = callbacks?.pointee
                }
                return OpaquePointer(handle)
            },
            destroy: { [self] _ in
                state.withLock { $0.destroyCount += 1 }
                return destroyResult
            },
            connect: { [self] _, _, _, completion, userdata in
                state.withLock { $0.connectCalls += 1 }
                record(completion, userdata)
            },
            disconnect: { [self] _, completion, userdata in
                state.withLock { $0.disconnectCalls += 1 }
                record(completion, userdata)
            },
            reconnect: { [self] _, completion, userdata in
                state.withLock { $0.reconnectCalls += 1 }
                record(completion, userdata)
            },
            status: { [self] _ in
                // A static string, as the header promises: allocated once and
                // never freed, so the SDK freeing it would be a heap corruption
                // this fake would not survive either.
                staticStatus(state.withLock { $0.status })
            },
            sessionID: { [self] _ in
                guard let sessionID = state.withLock({ $0.sessionID }) else { return nil }
                // Heap-allocated, for the SDK to free — the free path is only
                // exercised if the fake really allocates.
                return strdup(sessionID)
            }
        )
    }

    private func record(_ completion: reactor_completion_fn?, _ userdata: UnsafeMutableRawPointer?)
    {
        guard let completion, let userdata else { return }
        state.withLock { $0.lastCompletion = (completion, userdata) }
    }
}

/// The status strings, allocated once each, mimicking the library's statics.
private let staticStatusStorage = Locked<[String: UnsafePointer<CChar>]>([:])

private func staticStatus(_ value: String) -> UnsafePointer<CChar> {
    staticStatusStorage.withLock { storage in
        if let existing = storage[value] { return existing }
        guard let copy = strdup(value) else { return emptyStaticString }
        let allocated = UnsafePointer(copy)
        // Never freed, deliberately: these stand in for the library's static
        // strings, and the SDK must never free one.
        storage[value] = allocated
        return allocated
    }
}

/// An empty string with the same never-freed lifetime, for the allocation
/// failure that will not happen but has to typecheck.
nonisolated(unsafe) private let emptyStaticString: UnsafePointer<CChar> = {
    let buffer = UnsafeMutablePointer<CChar>.allocate(capacity: 1)
    buffer.pointee = 0
    return UnsafePointer(buffer)
}()

func withOptionalCString<Result>(
    _ value: String?,
    _ body: (UnsafePointer<CChar>?) -> Result
) -> Result {
    guard let value else { return body(nil) }
    return value.withCString(body)
}
