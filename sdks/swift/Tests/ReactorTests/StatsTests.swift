import Foundation
import Testing

@testable import Reactor

/// Connection statistics.
///
/// The numbers themselves are computed in `reactor-core` and tested there. What
/// is testable here is the binding's half — the decode — and the case worth
/// reading first: a snapshot is read to decide whether a connection is healthy,
/// so a field that quietly became a zero says "healthy".
@Suite("Connection statistics")
struct StatsTests {

    /// What the core serializes for a healthy connection, with both derived
    /// bitrates present — i.e. not the first sample.
    private static let fullPayload = #"""
        {
          "rtt_ms": 21.5,
          "jitter_s": 0.004,
          "packet_loss_ratio": 0.002,
          "incoming_bitrate_bps": 1843200.0,
          "outgoing_bitrate_bps": 96000.0,
          "available_incoming_bitrate_bps": 3000000.0,
          "available_outgoing_bitrate_bps": 1500000.0,
          "target_bitrate_bps": 2500000.0,
          "frames_per_second": 29.97,
          "candidate_type": "relay",
          "relay_protocol": "tls",
          "candidate_pair_state": "succeeded",
          "packets_received": 4021,
          "packets_lost": -2,
          "packets_sent": 512,
          "bytes_received": 5123456,
          "bytes_sent": 65536,
          "timestamp_ms": 1757000000000.0,
          "inbound": [
            {"ssrc": 4294967295, "kind": "video", "packets_received": 4021, "packets_lost": -2,
             "bytes_received": 5123456, "jitter_s": 0.004, "nack_count": 2,
             "total_decode_time_s": 1.25, "frames_per_second": 29.97, "frames_decoded": 900,
             "frames_dropped": 1, "frame_width": 1920, "frame_height": 1080}
          ],
          "outbound": [
            {"ssrc": 222, "kind": null, "packets_sent": 512, "retransmitted_packets_sent": 1,
             "bytes_sent": 65536, "target_bitrate_bps": 2500000.0, "frames_per_second": 0.0,
             "frames_sent": 0, "frame_width": 0, "frame_height": 0, "round_trip_time_s": 0.0215,
             "total_round_trip_time_s": 2.15, "fraction_lost": 0.001, "packets_lost": 3}
          ],
          "candidate_pairs": [
            {"current_round_trip_time_s": 0.0215, "total_round_trip_time_s": 2.15,
             "priority": 9115038255631187199, "state": "succeeded", "nominated": true,
             "writable": true, "available_outgoing_bitrate_bps": 1500000.0,
             "available_incoming_bitrate_bps": 3000000.0, "bytes_sent": 65536,
             "bytes_received": 5123456, "packets_sent": 5000000000,
             "packets_received": 5000000001, "local_candidate_type": "relay",
             "local_relay_protocol": "tls"}
          ]
        }
        """#

    /// What the core serializes on the first sample after connecting: counters,
    /// but nothing to have derived a rate from yet, and no pair nominated.
    private static let firstSamplePayload = #"""
        {
          "rtt_ms": null, "jitter_s": null, "packet_loss_ratio": null,
          "incoming_bitrate_bps": null, "outgoing_bitrate_bps": null,
          "available_incoming_bitrate_bps": null, "available_outgoing_bitrate_bps": null,
          "target_bitrate_bps": null, "frames_per_second": null,
          "candidate_type": null, "relay_protocol": null, "candidate_pair_state": null,
          "packets_received": 0, "packets_lost": 0, "packets_sent": 0,
          "bytes_received": 0, "bytes_sent": 0, "timestamp_ms": 1757000000000.0,
          "inbound": [], "outbound": [], "candidate_pairs": []
        }
        """#

    private func makeClient(fake: FakeLibrary) throws -> Reactor {
        try Reactor(
            model: "reactor/helios",
            jwt: nil,
            apiURL: Reactor.defaultAPIURL,
            local: false,
            eventQueue: nil,
            ffi: fake.table)
    }

    /// Run `work`, answer the library's completion, and give back what the
    /// caller got.
    private func answering<T: Sendable>(
        _ fake: FakeLibrary,
        ok: Bool = true,
        result: String?,
        error: String? = nil,
        _ work: @escaping @Sendable () async throws -> T
    ) async throws -> T {
        let task = Task { try await work() }
        let deadline = Date().addingTimeInterval(2)
        while !fake.hasPendingCompletion, Date() < deadline {
            try await Task.sleep(for: .milliseconds(5))
        }
        fake.completeLastCall(ok: ok, result: result, error: error)
        return try await task.value
    }

    // MARK: - Through the client

    @Test("stats() reaches reactor_get_stats and decodes every field")
    func everyFieldIsDecoded() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let stats = try await answering(fake, result: Self.fullPayload) {
            try await client.stats()
        }

        #expect(fake.statsCalls == 1)
        #expect(stats.rttMS == 21.5)
        #expect(stats.jitterSeconds == 0.004)
        #expect(stats.packetLossRatio == 0.002)
        #expect(stats.incomingBitrateBPS == 1_843_200)
        #expect(stats.outgoingBitrateBPS == 96_000)
        #expect(stats.availableIncomingBitrateBPS == 3_000_000)
        #expect(stats.availableOutgoingBitrateBPS == 1_500_000)
        #expect(stats.targetBitrateBPS == 2_500_000)
        #expect(stats.framesPerSecond == 29.97)
        #expect(stats.candidateType == "relay")
        #expect(stats.relayProtocol == "tls")
        #expect(stats.candidatePairState == "succeeded")
        #expect(stats.packetsReceived == 4021)
        #expect(stats.packetsSent == 512)
        #expect(stats.bytesReceived == 5_123_456)
        #expect(stats.bytesSent == 65_536)
        #expect(stats.timestampMS == 1_757_000_000_000)

        let inbound = try #require(stats.inbound.first)
        #expect(inbound.kind == "video")
        #expect(inbound.nackCount == 2)
        #expect(inbound.totalDecodeTimeSeconds == 1.25)
        #expect(inbound.framesDecoded == 900)
        #expect(inbound.framesDropped == 1)
        #expect(inbound.frameWidth == 1920)
        #expect(inbound.frameHeight == 1080)

        let outbound = try #require(stats.outbound.first)
        #expect(outbound.ssrc == 222)
        #expect(outbound.retransmittedPacketsSent == 1)
        #expect(outbound.roundTripTimeSeconds == 0.0215)
        #expect(outbound.totalRoundTripTimeSeconds == 2.15)
        #expect(outbound.fractionLost == 0.001)
        #expect(outbound.packetsLost == 3)

        let pair = try #require(stats.candidatePairs.first)
        #expect(pair.state == "succeeded")
        #expect(pair.nominated)
        #expect(pair.writable)
        #expect(pair.localCandidateType == "relay")
        #expect(pair.localRelayProtocol == "tls")
    }

    @Test("the first sample's unmeasured fields are nil, not zero")
    func unmeasuredIsNil() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let stats = try await answering(fake, result: Self.firstSamplePayload) {
            try await client.stats()
        }

        #expect(stats.rttMS == nil)
        #expect(stats.jitterSeconds == nil)
        #expect(stats.packetLossRatio == nil)
        #expect(stats.incomingBitrateBPS == nil)
        #expect(stats.outgoingBitrateBPS == nil)
        #expect(stats.availableIncomingBitrateBPS == nil)
        #expect(stats.availableOutgoingBitrateBPS == nil)
        #expect(stats.targetBitrateBPS == nil)
        #expect(stats.framesPerSecond == nil)
        #expect(stats.candidateType == nil)
        #expect(stats.relayProtocol == nil)
        #expect(stats.candidatePairState == nil)
        #expect(stats.packetsReceived == 0)
        #expect(stats.inbound.isEmpty)
        #expect(stats.outbound.isEmpty)
        #expect(stats.candidatePairs.isEmpty)
    }

    @Test("a session that is not ready is the library's INVALID_STATE, passed through")
    func notReadyThrowsInvalidState() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        do {
            _ = try await answering(
                fake, ok: false, result: nil,
                error: #"{"code":"INVALID_STATE","message":"not ready","operation":"get_stats"}"#
            ) {
                try await client.stats()
            }
            Issue.record("expected a throw")
        } catch let error as ReactorError {
            #expect(error.code == .invalidState)
        }
    }

    @Test("a closed client refuses rather than calling the library")
    func closedClientRefuses() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        client.close()

        do {
            _ = try await client.stats()
            Issue.record("expected a throw")
        } catch let error as ReactorError {
            #expect(error.code == .invalidState)
        }
        #expect(fake.statsCalls == 0)
    }

    // MARK: - The decode, refusing rather than guessing

    @Test("wide and signed counters survive the decode")
    func countersKeepTheirRange() throws {
        let stats = try ConnectionStats(payload: Self.fullPayload)

        // Signed: RFC 3550 lets duplicates push loss below zero.
        #expect(stats.packetsLost == -2)
        #expect(stats.inbound.first?.packetsLost == -2)
        // The top of a 32-bit SSRC, which a signed 32-bit field would wrap.
        #expect(stats.inbound.first?.ssrc == UInt32.max)
        // Past 32 bits.
        #expect(stats.candidatePairs.first?.packetsSent == 5_000_000_000)
        #expect(stats.candidatePairs.first?.packetsReceived == 5_000_000_001)
        #expect(stats.candidatePairs.first?.priority == 9_115_038_255_631_187_199)
        // A null kind is "the engine did not say", not an empty string.
        #expect(stats.outbound.first?.kind == nil)
    }

    @Test("absent per-stream lists decode as empty")
    func absentListsAreEmpty() throws {
        let stats = try ConnectionStats(
            payload: #"""
                {"packets_received": 1, "packets_lost": 0, "packets_sent": 1,
                 "bytes_received": 1, "bytes_sent": 1, "timestamp_ms": 1.0}
                """#)
        #expect(stats.inbound.isEmpty)
        #expect(stats.outbound.isEmpty)
        #expect(stats.candidatePairs.isEmpty)
        #expect(stats.rttMS == nil)
    }

    @Test(
        "a report that is not the documented shape is a decode failure",
        arguments: [
            // No report at all.
            nil,
            // Not JSON.
            "<html>nope</html>",
            // Not an object.
            "[]",
            // A required counter missing: a zero here would say "idle".
            #"{"packets_lost": 0, "packets_sent": 0, "bytes_received": 0, "bytes_sent": 0, "timestamp_ms": 1.0}"#,
            // A counter of the wrong type.
            #"{"packets_received": "lots", "packets_lost": 0, "packets_sent": 0, "bytes_received": 0, "bytes_sent": 0, "timestamp_ms": 1.0}"#,
            // A stream missing a field.
            #"{"packets_received": 0, "packets_lost": 0, "packets_sent": 0, "bytes_received": 0, "bytes_sent": 0, "timestamp_ms": 1.0, "inbound": [{"ssrc": 1}]}"#,
        ] as [String?])
    func malformedIsRefused(payload: String?) throws {
        do {
            _ = try ConnectionStats(payload: payload)
            Issue.record("expected a throw for \(payload ?? "nil")")
        } catch let error as ReactorError {
            #expect(error.code == .decodeFailed)
            #expect(error.operation == "get_stats")
        }
    }
}
