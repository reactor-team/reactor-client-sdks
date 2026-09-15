import ExampleSupport
import Foundation
import Reactor
import TestSupport
import Testing

/// Scenario 04, scripted: publish a track and push frames into it. Mirrors
/// `sdks/python/integration-tests/tests/test_tracks_and_frames.py`'s
/// `test_pushing_before_publish_raises` and
/// `test_publish_and_push_frame_reaches_main_video`.
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
}
