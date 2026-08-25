import CReactorFFI
import Foundation

/// One clip download, which is the only operation here that outlives its client.
///
/// `reactor_download_clip` is documented as outliving the handle it was given,
/// so its completion can arrive after the client is gone. Both of the obvious
/// answers are wrong, and the C++ SDK shipped one of each:
///
/// - free it during teardown, and a late callback writes through a freed
///   pointer — AddressSanitizer named that one on the progress callback;
/// - leave it out of teardown, and the caller's `await` never returns.
///
/// One shape answers both. **The client owns this object; the FFI is handed a
/// ticket carrying nothing but a weak reference to it.** Teardown settles the
/// caller and drops the client's reference; a late callback locks the weak
/// reference, finds nothing, and returns having touched nothing. The ticket is
/// the callback's own to free, so reading it is always safe.
final class DownloadOperation: @unchecked Sendable {

    private let state = Locked<CheckedContinuation<DownloadResult, any Error>?>(nil)
    private let progress: (@Sendable (DownloadProgress) -> Void)?
    private let outPath: URL
    private weak var owner: Reactor?

    init(
        outPath: URL,
        owner: Reactor,
        progress: (@Sendable (DownloadProgress) -> Void)?
    ) {
        self.outPath = outPath
        self.owner = owner
        self.progress = progress
    }

    func attach(_ continuation: CheckedContinuation<DownloadResult, any Error>) {
        state.withLock { $0 = continuation }
    }

    /// Report progress. Runs on the download's own thread; blocking it delays
    /// this download and nothing else.
    func report(done: UInt32, total: UInt32) {
        progress?(DownloadProgress(done: Int(done), total: Int(total)))
    }

    /// Settle from the FFI's completion.
    func complete(ok: Int32, resultJSON: UnsafePointer<CChar>?, errorJSON: UnsafePointer<CChar>?) {
        // Decoded before the continuation is claimed, as everywhere else.
        let outcome: Result<DownloadResult, any Error>
        if ok == 1 {
            outcome = decode(String(borrowing: resultJSON))
        } else {
            outcome = .failure(ReactorError.decode(payload: String(borrowing: errorJSON)))
        }
        owner?.forget(download: self)
        settle(outcome)
    }

    /// Settle from teardown, for a download whose client is going away.
    ///
    /// The wording matters. A download whose client was destroyed **is still
    /// downloading** — the library was told to outlive the handle. Telling the
    /// caller the file may yet arrive is worth more than "aborted", which would
    /// send them to delete a file that is about to appear.
    func abandonForTeardown() {
        settle(
            .failure(
                ReactorError(
                    .aborted,
                    "the client was closed while this clip was downloading. The download is not "
                        + "bounded by the client, so the file may still arrive at "
                        + "\(outPath.path) — check for it before retrying.",
                    operation: "download_clip")))
    }

    private func decode(_ payload: String?) -> Result<DownloadResult, any Error> {
        guard let payload, let data = payload.data(using: .utf8),
            let object = try? JSONDecoder().decode(JSONValue.self, from: data),
            let path = object["path"]?.stringValue
        else {
            return .failure(
                ReactorError(
                    .decodeFailed,
                    "the download finished but its result could not be read",
                    operation: "download_clip"))
        }
        return .success(
            DownloadResult(
                path: URL(fileURLWithPath: path),
                bytes: object["bytes"]?.intValue ?? 0,
                segments: object["segments"]?.intValue ?? 0))
    }

    private func settle(_ outcome: Result<DownloadResult, any Error>) {
        let continuation = state.withLock {
            stored -> CheckedContinuation<DownloadResult, any Error>? in
            defer { stored = nil }
            return stored
        }
        continuation?.resume(with: outcome)
    }
}

/// What the FFI is handed as `userdata` for a download.
///
/// It holds the operation **weakly** and nothing else. That is the whole point:
/// this object is the callback's own — freed by the completion, which fires
/// exactly once — so dereferencing it is always safe, while the operation it
/// points at may be gone.
final class DownloadTicket: @unchecked Sendable {

    weak var operation: DownloadOperation?

    init(operation: DownloadOperation) {
        self.operation = operation
    }

    static func from(_ userdata: UnsafeMutableRawPointer?) -> DownloadTicket? {
        guard let userdata else { return nil }
        return Unmanaged<DownloadTicket>.fromOpaque(userdata).takeUnretainedValue()
    }
}

/// Progress, on the download's own thread.
///
/// Takes the ticket **unretained**: the completion is what consumes the
/// reference, and progress may fire many times before it.
let downloadProgressTrampoline: reactor_progress_fn = { done, total, userdata in
    DownloadTicket.from(userdata)?.operation?.report(done: done, total: total)
}

/// The completion, which fires exactly once and owns the ticket.
let downloadCompletionTrampoline: reactor_completion_fn = { ok, resultJSON, errorJSON, userdata in
    guard let userdata else { return }
    // takeRetainedValue: this is where the ticket's life ends, whether or not
    // the operation behind it still exists.
    let ticket = Unmanaged<DownloadTicket>.fromOpaque(userdata).takeRetainedValue()
    ticket.operation?.complete(ok: ok, resultJSON: resultJSON, errorJSON: errorJSON)
}
