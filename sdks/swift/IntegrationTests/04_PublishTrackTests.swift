import ExampleSupport
import Foundation
import Reactor
import TestSupport
import Testing

/// Scenario 04, scripted: publish a track and push frames into it. Mirrors
/// `sdks/python/integration-tests/tests/test_tracks_and_frames.py`'s
/// `test_pushing_before_publish_raises` and
/// `test_publish_and_push_frame_reaches_main_video`. The republish-after-
/// reconnect test has no Python counterpart yet.
@Suite("04 - publish track")
struct PublishTrackTests {

    @Test("pushing before publish is refused")
    func pushBeforePublishIsRefused() async throws {
        try await withConnectedReactor { reactor in
            let webcam = try reactor.track("webcam")
            do {
                try webcam.pushFrame(
                    MediaFixtures.solidBGRAFrame(width: 64, height: 64, color: (1, 2, 3)),
                    width: 64, height: 64)
                Issue.record("expected pushFrame before publish() to throw")
            } catch let error as ReactorError {
                #expect(error.code == .invalidState)
            }
        }
    }

    @Test("a published, pushed frame reaches main_video")
    func publishedPushedFrameReachesMainVideo() async throws {
        try await withConnectedReactor { reactor in
            let webcam = try reactor.track("webcam")
            try await webcam.publish()
            #expect(webcam.published)

            let pump = pumpFrames(into: webcam, color: (30, 20, 10))
            defer { pump.cancel() }

            let frames = LatestFrameBox()
            let subscription = try reactor.track("main_video").onFrame { frames.set($0) }
            defer { subscription.cancel() }

            try await waitUntil(timeout: .seconds(10)) { frames.value != nil }
            let frame = try #require(frames.value)
            #expect(frame.width == 64)
            #expect(frame.height == 64)

            try webcam.unpublish()
            #expect(!webcam.published)
        }
    }

    /// A publish does not survive a reconnect: the client drops it, and the
    /// session has to drop its side too, or the second `publish()` is refused as
    /// "track already published" while the client has no sender left. Two blues
    /// far apart, one per connection, so a frame read after the republish can
    /// only have come from a frame pushed after it.
    @Test("a track published again after reconnect() is granted, and its frames come back")
    func republishAfterReconnectDeliversFramesAgain() async throws {
        try await withConnectedReactor { reactor in
            let webcam = try reactor.track("webcam")
            let frames = LatestFrameBox()
            // Registered once: a frame handler stays registered across a reconnect.
            let subscription = try reactor.track("main_video").onFrame { frames.set($0) }
            defer { subscription.cancel() }

            try await webcam.publish()
            let before = pumpFrames(into: webcam, color: (0x10, 40, 40))
            defer { before.cancel() }
            try await waitUntil(timeout: .seconds(30)) {
                frames.value.flatMap(MediaFixtures.meanColor).map { $0.b <= 0x28 } ?? false
            }
            before.cancel()

            try await reactor.reconnect()
            try await waitUntil(timeout: .seconds(60)) { reactor.status == .ready }

            #expect(!webcam.published, "a publish must not survive reconnect()")
            do {
                try webcam.pushFrame(
                    MediaFixtures.solidBGRAFrame(width: 64, height: 64, color: (0x10, 40, 40)),
                    width: 64, height: 64)
                Issue.record("expected pushFrame after reconnect() to throw until republished")
            } catch let error as ReactorError {
                #expect(error.code == .invalidState)
            }

            try await webcam.publish()
            #expect(webcam.published, "the republish must be granted")

            // Waits for a frame of the new colour rather than any frame: the
            // first few after the reconnect can still be the decoder catching up.
            let after = pumpFrames(into: webcam, color: (0xF0, 40, 40))
            defer { after.cancel() }
            try await waitUntil(timeout: .seconds(30)) {
                frames.value.flatMap(MediaFixtures.meanColor).map { $0.b >= 0xB0 } ?? false
            }

            try webcam.unpublish()
        }
    }
}
