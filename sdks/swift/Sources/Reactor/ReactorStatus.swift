/// Where the client is in its connection lifecycle.
///
/// The same four values `reactor_status` reports, which is the only place they
/// are defined — this is the spelling, not the definition.
public enum ReactorStatus: String, Sendable, CaseIterable {

    /// No session, or the session ended.
    case disconnected

    /// Creating or adopting a session, and negotiating the transport.
    case connecting

    /// Connected, waiting for the runtime to accept the session.
    case waiting

    /// The session is live: tracks are declared and media flows.
    case ready

    /// The status the FFI reported, or ``disconnected`` for anything unknown.
    ///
    /// An unrecognised status is treated as disconnected rather than trapping:
    /// the core is free to add one, and a binding that crashes on a value it has
    /// not been taught is worse than a binding that reports the safe answer. It
    /// is logged where a log exists.
    init(ffiValue: String) {
        self = ReactorStatus(rawValue: ffiValue) ?? .disconnected
    }
}
