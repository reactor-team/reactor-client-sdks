import CReactorFFI
import Foundation

extension Reactor {

    // MARK: - Statistics

    /// A statistics snapshot for the live connection.
    ///
    /// RTT, jitter, packet loss, bitrates, and the engine's per-stream counters:
    ///
    /// ```swift
    /// let stats = try await reactor.stats()
    /// if let rtt = stats.rttMS {
    ///     print("\(Int(rtt)) ms, \(Int(stats.incomingBitrateBPS ?? 0)) bps in")
    /// }
    /// ```
    ///
    /// The two measured bitrates are derived against the previous call, so the
    /// first call after connecting reports `nil` for them, as does a call made
    /// less than 200 ms after the last one. Everything else is on every call. For
    /// a continuous reading, poll — a couple of seconds apart is what the browser
    /// SDK's own `statsUpdate` uses:
    ///
    /// ```swift
    /// while reactor.status == .ready {
    ///     let stats = try await reactor.stats()
    ///     // ...
    ///     try await Task.sleep(for: .seconds(2))
    /// }
    /// ```
    ///
    /// - Throws: ``ReactorError`` with ``ReactorError/Code/invalidState`` unless
    ///   the session is ready — a snapshot of zeroes cannot be told from a
    ///   connection carrying nothing — and with
    ///   ``ReactorError/Code/decodeFailed`` when the report is not the documented
    ///   shape, never a snapshot with zeroes filled in.
    public func stats() async throws -> ConnectionStats {
        let payload = try await perform("get_stats") { handle, completion, userdata in
            self.ffi.getStats(handle, completion, userdata)
        }
        return try ConnectionStats(payload: payload)
    }
}
