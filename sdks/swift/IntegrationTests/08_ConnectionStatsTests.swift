import ExampleSupport
import Foundation
import Reactor
import TestSupport
import Testing

/// `stats()` on a live session: the snapshot is populated once media flows both
/// ways, the measured bitrates follow the documented first-call rule, and a
/// session that has ended refuses rather than reporting zeroes.
@Suite("08 - connection stats")
struct ConnectionStatsTests {

    @Test("stats() reports a populated snapshot while media flows, and refuses once disconnected")
    func statsArePopulatedOnALiveSession() async throws {
        try await withConnectedReactor { reactor in
            let webcam = try reactor.track("webcam")
            try await webcam.publish()
            let pump = pumpFrames(into: webcam)
            defer { pump.cancel() }

            let counter = FrameCounter(label: "swift-integration-08")
            let subscription = try reactor.track("main_video").onFrame { counter.submit($0) }
            defer { subscription.cancel() }

            try await waitUntil(timeout: .seconds(10)) { counter.frames > 0 }

            // The first call after connecting has no previous sample to measure
            // a rate against.
            let first = try await reactor.stats()
            #expect(first.incomingBitrateBPS == nil)
            #expect(first.outgoingBitrateBPS == nil)

            try await Task.sleep(for: .seconds(1))
            let second = try await reactor.stats()

            #expect((second.incomingBitrateBPS ?? 0) > 0, "no incoming bitrate measured")
            #expect((second.outgoingBitrateBPS ?? 0) > 0, "no outgoing bitrate measured")
            #expect(second.rttMS != nil, "no RTT on the pair carrying the media")
            #expect(second.candidatePairState == "succeeded")
            #expect(second.candidateType != nil)
            #expect(second.packetsReceived > 0)
            #expect(second.packetsSent > 0)
            #expect(second.timestampMS > first.timestampMS)

            let video = second.inbound.first { $0.kind == "video" }
            #expect((video?.packetsReceived ?? 0) > 0, "no inbound video stream reported")
            let sent = second.outbound.first { $0.kind == "video" }
            #expect((sent?.packetsSent ?? 0) > 0, "no outbound video stream reported")
            #expect(second.candidatePairs.contains { $0.nominated && $0.state == "succeeded" })

            // Once the session is gone, a snapshot of zeroes would be
            // indistinguishable from a connection carrying nothing.
            try await reactor.disconnect()
            do {
                _ = try await reactor.stats()
                Issue.record("stats() succeeded on a disconnected client")
            } catch let error as ReactorError {
                #expect(error.code == .invalidState)
            }
        }
    }
}
