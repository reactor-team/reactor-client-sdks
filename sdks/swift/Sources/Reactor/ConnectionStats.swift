import Foundation

/// A statistics snapshot for the live connection, from ``Reactor/stats()``.
///
/// The arithmetic is not in this SDK. Counters come from the WebRTC engine and
/// the rates are derived in the Rust core, so every FFI-based binding reports the
/// same numbers under the same names — see `crates/reactor-core/src/stats.rs`.
/// What is here is the shape, and the decode.
///
/// The scalars are the summary — what a health check or an overlay reads. The
/// three arrays at the bottom are the engine's own per-stream report, for when
/// the summary is not enough.
///
/// A scalar is `nil` when the engine has not measured it yet, which is a
/// different thing from zero: no RTT yet is not a zero-latency link, and no
/// incoming bitrate yet is not an idle one.
public struct ConnectionStats: Sendable, Hashable {

    /// Round-trip time in milliseconds, from the candidate pair carrying the
    /// media — the one ICE nominated and that succeeded.
    ///
    /// Falls back to the largest RTT any send stream measured, which comes from
    /// the far end's RTCP report about us, so it too takes a moment to appear.
    public let rttMS: Double?

    /// Jitter on the received video stream, in seconds — the same stream the
    /// browser SDK reads. With no video stream, the worst across the receive
    /// streams there are. Per-stream values are in ``inbound``.
    public let jitterSeconds: Double?

    /// Fraction of inbound packets lost since the connection came up, 0–1.
    ///
    /// Cumulative, not per-window, and from the video stream for the same reason
    /// ``jitterSeconds`` is.
    public let packetLossRatio: Double?

    /// Receive rate over the window since the previous ``Reactor/stats()``, in
    /// bits per second.
    ///
    /// Measured on the candidate pair carrying the media, so it covers everything
    /// that pair carried — RTP, RTCP and the data channel — which is what the
    /// browser SDK's `incomingBitrate` measures.
    ///
    /// `nil` on the first call after connecting, on a call less than 200 ms after
    /// the last one, before ICE has nominated a pair, and on the first call after
    /// a reconnect — a reconnect nominates a different pair whose counters
    /// restart from zero.
    public let incomingBitrateBPS: Double?

    /// Send rate over the same window, on the same terms.
    public let outgoingBitrateBPS: Double?

    /// The congestion controller's own estimate of what the path can carry, in
    /// bits per second — not what is flowing. `nil` until it has one, which needs
    /// media on the wire: a data-channel-only connection never reports it.
    public let availableIncomingBitrateBPS: Double?

    /// As above, for the send direction.
    public let availableOutgoingBitrateBPS: Double?

    /// What the encoders are aiming at, summed across send streams, in bits per
    /// second. The target, not the achieved rate — compare
    /// ``outgoingBitrateBPS``, which is measured.
    public let targetBitrateBPS: Double?

    /// Frames per second on the received video stream. `nil` with no video
    /// stream, and until the engine has measured a window's worth.
    public let framesPerSecond: Double?

    /// Transport type of the local candidate on the pair carrying the media:
    /// `"host"`, `"srflx"`, `"prflx"` or `"relay"`.
    ///
    /// `"relay"` means the media is going through a TURN server, which is the
    /// first thing worth knowing when latency is bad. `nil` before ICE has
    /// selected anything.
    public let candidateType: String?

    /// `"udp"`, `"tcp"` or `"tls"` when ``candidateType`` is `"relay"`; `nil`
    /// when the path is not relayed. Not a field the browser SDK reports.
    public let relayProtocol: String?

    /// State of the pair ``rttMS`` was read from. `nil` when the engine reported
    /// no candidate pairs at all, which is what an unconnected transport looks
    /// like.
    public let candidatePairState: String?

    /// Cumulative counters, summed across streams. Present on every call, even
    /// when the derived rates above are not, so a caller can do its own
    /// arithmetic over whatever window it likes.
    public let packetsReceived: Int

    /// Signed, and the sign is meaningful — see ``InboundStream/packetsLost``.
    /// The plain sum across receive streams, so one stream's duplicates do offset
    /// another's losses here; ``packetLossRatio`` is the field to read for "how
    /// bad is it".
    public let packetsLost: Int

    /// Packets sent, summed across send streams.
    public let packetsSent: Int

    /// RTP payload bytes received, summed across receive streams.
    public let bytesReceived: Int

    /// RTP payload bytes sent, summed across send streams.
    public let bytesSent: Int

    /// When the sample was taken, in Unix milliseconds.
    public let timestampMS: Double

    /// The engine's per-stream report for what this client receives,
    /// unaggregated.
    public let inbound: [InboundStream]

    /// The engine's per-stream report for what this client sends.
    public let outbound: [OutboundStream]

    /// Every ICE candidate pair the connection gathered. See ``CandidatePair``
    /// for which one carries the media.
    public let candidatePairs: [CandidatePair]

    /// One receive stream's counters, as the engine reports them.
    public struct InboundStream: Sendable, Hashable {

        /// The stream's RTP synchronization source.
        public let ssrc: UInt32

        /// `"audio"`, `"video"`, or `nil` when the engine reported no kind. What
        /// makes ``ConnectionStats/jitterSeconds`` a question about the video
        /// stream rather than about whichever stream happened to be worst.
        public let kind: String?

        /// Packets received on this stream.
        public let packetsReceived: Int

        /// Signed. RFC 3550 allows a negative count when duplicates arrive.
        public let packetsLost: Int

        /// RTP payload bytes received on this stream.
        public let bytesReceived: Int

        /// Jitter in seconds.
        public let jitterSeconds: Double

        /// NACKs this side sent asking for retransmissions.
        public let nackCount: Int

        /// Cumulative decode time in seconds.
        public let totalDecodeTimeSeconds: Double

        /// Video only; 0 until the engine has measured a window's worth.
        public let framesPerSecond: Double

        /// Frames decoded so far; 0 for audio.
        public let framesDecoded: Int

        /// Frames dropped before decoding; 0 for audio.
        public let framesDropped: Int

        /// Decoded frame size; 0 for audio and before the first frame.
        public let frameWidth: Int

        /// See ``frameWidth``.
        public let frameHeight: Int
    }

    /// One send stream's counters, as the engine reports them.
    ///
    /// The last four come from the far end's RTCP report about us, so they stay
    /// at zero until it has sent one — a zero there is "not measured yet", not a
    /// zero-latency link with no loss.
    public struct OutboundStream: Sendable, Hashable {

        /// The stream's RTP synchronization source.
        public let ssrc: UInt32

        /// `"audio"`, `"video"`, or `nil` when the engine reported no kind.
        public let kind: String?

        /// Packets sent on this stream, retransmissions included.
        public let packetsSent: Int

        /// Of ``packetsSent``, how many were retransmissions.
        public let retransmittedPacketsSent: Int

        /// RTP payload bytes sent on this stream.
        public let bytesSent: Int

        /// What the encoder is aiming at, in bits per second.
        public let targetBitrateBPS: Double

        /// Video only; 0 until the engine has measured a window's worth.
        public let framesPerSecond: Double

        /// Frames sent so far; 0 for audio.
        public let framesSent: Int

        /// Encoded frame size; 0 for audio and before the first frame.
        public let frameWidth: Int

        /// See ``frameWidth``.
        public let frameHeight: Int

        /// Round-trip time in seconds; 0 when not yet measured.
        public let roundTripTimeSeconds: Double

        /// Cumulative round-trip time in seconds.
        public let totalRoundTripTimeSeconds: Double

        /// Fraction of this stream the receiver reports as lost, 0–1.
        public let fractionLost: Double

        /// Packets the receiver reports as lost. Signed, per RFC 3550.
        public let packetsLost: Int
    }

    /// One ICE candidate pair.
    ///
    /// A connection gathers many — a plain loopback produces eighteen — and
    /// exactly one carries traffic: the one that is ``nominated`` **and** in the
    /// `"succeeded"` state. A pair stays nominated after it fails, so during an
    /// ICE restart the dead pair and the live one are both nominated at once.
    /// The rest report zeroes, so anything aggregating across pairs averages in
    /// candidates that carried nothing.
    public struct CandidatePair: Sendable, Hashable {

        /// Current RTT in seconds; 0 when not yet measured.
        public let currentRoundTripTimeSeconds: Double

        /// Cumulative RTT in seconds across every check on this pair.
        public let totalRoundTripTimeSeconds: Double

        /// ICE pair priorities are 64-bit (RFC 8445), well past what 32 bits hold.
        public let priority: UInt64

        /// `"succeeded"`, `"waiting"`, `"in-progress"`, `"failed"` or
        /// `"cancelled"`.
        public let state: String

        /// Whether ICE selected this pair. Read this rather than inferring the
        /// selected pair from ``state`` and ``priority``.
        public let nominated: Bool

        /// Whether a consent check has succeeded recently enough to send on it.
        public let writable: Bool

        /// The congestion controller's send-direction estimate, in bits per
        /// second; 0 when it has none yet.
        public let availableOutgoingBitrateBPS: Double

        /// As above, for the receive direction.
        public let availableIncomingBitrateBPS: Double

        /// Everything this pair carried — RTCP and data channel included, so
        /// wider than the per-stream RTP counters.
        public let bytesSent: Int

        /// See ``bytesSent``.
        public let bytesReceived: Int

        /// Packets this pair carried, in each direction.
        public let packetsSent: Int

        /// See ``packetsSent``.
        public let packetsReceived: Int

        /// `"host"`, `"srflx"`, `"prflx"`, `"relay"`, or `nil` before ICE
        /// selected anything. `"relay"` means this pair goes through TURN.
        public let localCandidateType: String?

        /// `"udp"`, `"tcp"`, `"tls"`, or `nil` when not relayed.
        public let localRelayProtocol: String?
    }
}

// MARK: - Decoding

// The wire names are the header's, spelled out key by key rather than through
// `.convertFromSnakeCase`: that strategy would look for `rttMs` and `jitterS`,
// and the names a Swift caller reads should not be dictated by a decoder flag.
//
// Every non-optional field is required. A snapshot is read to decide whether a
// connection is healthy, and a zero substituted for a field that failed to parse
// says "healthy" — the one answer worth never guessing at.

extension ConnectionStats: Decodable {

    private enum CodingKeys: String, CodingKey {
        case rttMS = "rtt_ms"
        case jitterSeconds = "jitter_s"
        case packetLossRatio = "packet_loss_ratio"
        case incomingBitrateBPS = "incoming_bitrate_bps"
        case outgoingBitrateBPS = "outgoing_bitrate_bps"
        case availableIncomingBitrateBPS = "available_incoming_bitrate_bps"
        case availableOutgoingBitrateBPS = "available_outgoing_bitrate_bps"
        case targetBitrateBPS = "target_bitrate_bps"
        case framesPerSecond = "frames_per_second"
        case candidateType = "candidate_type"
        case relayProtocol = "relay_protocol"
        case candidatePairState = "candidate_pair_state"
        case packetsReceived = "packets_received"
        case packetsLost = "packets_lost"
        case packetsSent = "packets_sent"
        case bytesReceived = "bytes_received"
        case bytesSent = "bytes_sent"
        case timestampMS = "timestamp_ms"
        case inbound
        case outbound
        case candidatePairs = "candidate_pairs"
    }

    /// Decode a snapshot from the wire names `reactor_get_stats` reports.
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        rttMS = try c.decodeIfPresent(Double.self, forKey: .rttMS)
        jitterSeconds = try c.decodeIfPresent(Double.self, forKey: .jitterSeconds)
        packetLossRatio = try c.decodeIfPresent(Double.self, forKey: .packetLossRatio)
        incomingBitrateBPS = try c.decodeIfPresent(Double.self, forKey: .incomingBitrateBPS)
        outgoingBitrateBPS = try c.decodeIfPresent(Double.self, forKey: .outgoingBitrateBPS)
        availableIncomingBitrateBPS = try c.decodeIfPresent(
            Double.self, forKey: .availableIncomingBitrateBPS)
        availableOutgoingBitrateBPS = try c.decodeIfPresent(
            Double.self, forKey: .availableOutgoingBitrateBPS)
        targetBitrateBPS = try c.decodeIfPresent(Double.self, forKey: .targetBitrateBPS)
        framesPerSecond = try c.decodeIfPresent(Double.self, forKey: .framesPerSecond)
        candidateType = try c.decodeIfPresent(String.self, forKey: .candidateType)
        relayProtocol = try c.decodeIfPresent(String.self, forKey: .relayProtocol)
        candidatePairState = try c.decodeIfPresent(String.self, forKey: .candidatePairState)
        packetsReceived = try c.decode(Int.self, forKey: .packetsReceived)
        packetsLost = try c.decode(Int.self, forKey: .packetsLost)
        packetsSent = try c.decode(Int.self, forKey: .packetsSent)
        bytesReceived = try c.decode(Int.self, forKey: .bytesReceived)
        bytesSent = try c.decode(Int.self, forKey: .bytesSent)
        timestampMS = try c.decode(Double.self, forKey: .timestampMS)
        // The lists may be absent; an empty report is still a report.
        inbound = try c.decodeIfPresent([InboundStream].self, forKey: .inbound) ?? []
        outbound = try c.decodeIfPresent([OutboundStream].self, forKey: .outbound) ?? []
        candidatePairs =
            try c.decodeIfPresent([CandidatePair].self, forKey: .candidatePairs) ?? []
    }
}

extension ConnectionStats.InboundStream: Decodable {

    private enum CodingKeys: String, CodingKey {
        case ssrc
        case kind
        case packetsReceived = "packets_received"
        case packetsLost = "packets_lost"
        case bytesReceived = "bytes_received"
        case jitterSeconds = "jitter_s"
        case nackCount = "nack_count"
        case totalDecodeTimeSeconds = "total_decode_time_s"
        case framesPerSecond = "frames_per_second"
        case framesDecoded = "frames_decoded"
        case framesDropped = "frames_dropped"
        case frameWidth = "frame_width"
        case frameHeight = "frame_height"
    }
}

extension ConnectionStats.OutboundStream: Decodable {

    private enum CodingKeys: String, CodingKey {
        case ssrc
        case kind
        case packetsSent = "packets_sent"
        case retransmittedPacketsSent = "retransmitted_packets_sent"
        case bytesSent = "bytes_sent"
        case targetBitrateBPS = "target_bitrate_bps"
        case framesPerSecond = "frames_per_second"
        case framesSent = "frames_sent"
        case frameWidth = "frame_width"
        case frameHeight = "frame_height"
        case roundTripTimeSeconds = "round_trip_time_s"
        case totalRoundTripTimeSeconds = "total_round_trip_time_s"
        case fractionLost = "fraction_lost"
        case packetsLost = "packets_lost"
    }
}

extension ConnectionStats.CandidatePair: Decodable {

    private enum CodingKeys: String, CodingKey {
        case currentRoundTripTimeSeconds = "current_round_trip_time_s"
        case totalRoundTripTimeSeconds = "total_round_trip_time_s"
        case priority
        case state
        case nominated
        case writable
        case availableOutgoingBitrateBPS = "available_outgoing_bitrate_bps"
        case availableIncomingBitrateBPS = "available_incoming_bitrate_bps"
        case bytesSent = "bytes_sent"
        case bytesReceived = "bytes_received"
        case packetsSent = "packets_sent"
        case packetsReceived = "packets_received"
        case localCandidateType = "local_candidate_type"
        case localRelayProtocol = "local_relay_protocol"
    }
}

extension ConnectionStats {

    /// Read a snapshot from what `reactor_get_stats` reported.
    init(payload: String?) throws {
        guard let payload, let data = payload.data(using: .utf8) else {
            throw ReactorError(
                .decodeFailed,
                "the library answered get_stats with no report",
                operation: "get_stats")
        }
        do {
            self = try JSON.decoder().decode(ConnectionStats.self, from: data)
        } catch {
            throw ReactorError(
                .decodeFailed,
                "connection statistics could not be read (\(error)): \(payload)",
                operation: "get_stats")
        }
    }
}
