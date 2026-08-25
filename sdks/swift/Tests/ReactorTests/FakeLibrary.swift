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
    /// What the fake was asked to do, in order — enough to tell whether a
    /// `reactor_destroy` overtook a call that was still inside the ABI.
    enum Event: Equatable {
        case statusEntered
        case statusReturned
        case destroyed
    }

    struct CreateCall {
        var apiURL: String?
        var model: String?
        var jwt: String?
        var local: Int32
        var admMode: Int32
        var sdkVersion: String?
        var sdkType: String?
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
        var tracksJSON = "[]"
        var pausedJSON = "[]"
        var lastCompletion: (fn: reactor_completion_fn, userdata: UnsafeMutableRawPointer)?
        weak var lastUserdataObject: AnyObject?
        var events: [Event] = []
        var whileReadingStatus: (@Sendable () -> Void)?
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

    /// What the fake was asked to do, in order.
    var events: [Event] { state.withLock { $0.events } }

    /// Run this inside `reactor_status`, before it returns.
    var whileReadingStatus: (@Sendable () -> Void)? {
        get { state.withLock { $0.whileReadingStatus } }
        set { state.withLock { $0.whileReadingStatus = newValue } }
    }

    /// Whether the object the SDK passed as the last `userdata` is still alive.
    var lastUserdataIsAlive: Bool { state.withLock { $0.lastUserdataObject != nil } }
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

    /// Declare tracks, in the order given — which is the order the SDK must keep.
    func setTracks(_ tracks: [(name: String, kind: String, direction: String)]) {
        let entries = tracks.map {
            #"{"name":"\#($0.name)","kind":"\#($0.kind)","direction":"\#($0.direction)"}"#
        }
        state.withLock { $0.tracksJSON = "[" + entries.joined(separator: ",") + "]" }
    }

    /// Hand back a declaration list the SDK has to cope with rather than parse.
    func setRawTracksJSON(_ json: String) {
        state.withLock { $0.tracksJSON = json }
    }

    func setPausedTracks(_ names: [String]) {
        let quoted = names.map { "\"\($0)\"" }.joined(separator: ",")
        state.withLock { $0.pausedJSON = "[" + quoted + "]" }
    }

    /// Fire `on_track`, the way the library reports a media id arriving.
    func fireTrack(name: String, mid: String?) {
        guard let callbacks = state.withLock({ $0.callbacks }), let onTrack = callbacks.on_track
        else { return }
        name.withCString { namePointer in
            withOptionalCString(mid) { midPointer in
                onTrack(namePointer, midPointer, callbacks.userdata)
            }
        }
    }

    /// Fire `on_frame` on the calling thread, as the library's delivery thread
    /// would — the SDK must run handlers right here rather than queueing them.
    func fireFrame(
        track: String,
        width: UInt32 = 2,
        height: UInt32 = 2,
        frameID: UInt64 = 0,
        captureTimeUS: UInt64 = 0,
        userData: [UInt8]? = nil,
        fill: UInt8 = 0xAB
    ) {
        guard let callbacks = state.withLock({ $0.callbacks }), let onFrame = callbacks.on_frame
        else { return }
        var pixels = [UInt8](repeating: fill, count: Int(width) * Int(height) * 4)
        track.withCString { namePointer in
            pixels.withUnsafeMutableBufferPointer { pixelBuffer in
                let base = pixelBuffer.baseAddress
                if var tag = userData {
                    tag.withUnsafeMutableBufferPointer { tagBuffer in
                        onFrame(
                            namePointer, base, width, height, frameID, captureTimeUS,
                            tagBuffer.baseAddress, UInt32(tagBuffer.count), callbacks.userdata)
                    }
                } else {
                    onFrame(
                        namePointer, base, width, height, frameID, captureTimeUS, nil, 0,
                        callbacks.userdata)
                }
            }
        }
    }

    /// Fire `on_audio` on the calling thread.
    func fireAudio(
        track: String,
        samples: [Int16] = [1, -1, 2, -2],
        sampleRate: UInt32 = 48000,
        channels: UInt32 = 1
    ) {
        guard let callbacks = state.withLock({ $0.callbacks }), let onAudio = callbacks.on_audio
        else { return }
        var buffer = samples
        track.withCString { namePointer in
            buffer.withUnsafeMutableBufferPointer { audioBuffer in
                onAudio(
                    namePointer, audioBuffer.baseAddress, UInt32(audioBuffer.count), sampleRate,
                    channels, callbacks.userdata)
            }
        }
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
            createWithADM: {
                [self]
                apiURL, model, jwt, local, callbacks, admMode, sdkVersion,
                sdkType in
                state.withLock { state in
                    state.createCalls.append(
                        CreateCall(
                            apiURL: String(borrowing: apiURL),
                            model: String(borrowing: model),
                            jwt: String(borrowing: jwt),
                            local: local,
                            admMode: admMode,
                            sdkVersion: String(borrowing: sdkVersion),
                            sdkType: String(borrowing: sdkType)))
                    state.callbacks = callbacks?.pointee
                }
                return OpaquePointer(handle)
            },
            destroy: { [self] _ in
                state.withLock {
                    $0.destroyCount += 1
                    $0.events.append(.destroyed)
                }
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
                let (hook, value) = state.withLock { state -> ((@Sendable () -> Void)?, String) in
                    state.events.append(.statusEntered)
                    return (state.whileReadingStatus, state.status)
                }
                // Widens the window a close() racing this read has to fit into.
                // Nothing here holds a lock while it runs, so a close() that is
                // allowed to overtake this read will.
                hook?()
                state.withLock { $0.events.append(.statusReturned) }
                // A static string, as the header promises: allocated once and
                // never freed, so the SDK freeing it would be a heap corruption
                // this fake would not survive either.
                return staticStatus(value)
            },
            sessionID: { [self] _ in
                guard let sessionID = state.withLock({ $0.sessionID }) else { return nil }
                // Heap-allocated, for the SDK to free — the free path is only
                // exercised if the fake really allocates.
                return strdup(sessionID)
            },
            tracks: { [self] _ in strdup(state.withLock { $0.tracksJSON }) },
            pausedTracks: { [self] _ in strdup(state.withLock { $0.pausedJSON }) }
        )
    }

    private func record(_ completion: reactor_completion_fn?, _ userdata: UnsafeMutableRawPointer?)
    {
        guard let completion, let userdata else { return }
        // Held weakly, so a test can tell whether the SDK ever balanced the
        // `passRetained` it made for this call. A strong reference here would
        // hide exactly the leak it is meant to expose.
        let object = Unmanaged<AnyObject>.fromOpaque(userdata).takeUnretainedValue()
        state.withLock {
            $0.lastCompletion = (completion, userdata)
            $0.lastUserdataObject = object
        }
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
