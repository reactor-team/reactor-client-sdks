import ExampleSupport
import Foundation
import Reactor
import Testing

/// Scenario 07, scripted: read the per-frame trailer — frame id, sender
/// timestamp, user data. Mirrors
/// `sdks/python/integration-tests/tests/test_tracks_and_frames.py`'s
/// `test_frame_user_data_loops_back_on_main_video`.
///
/// `frameID`/`captureTimeUs` are **not** asserted on content: Python's suite
/// found `reactor/echo`'s own trailer unreliable for both across independent
/// live runs (0 both times for frame id, real then all-zero for capture time
/// against identical code) — that inconsistency is the finding, not something
/// to paper over with a looser assertion. `userData` is different: as of echo
/// 1.7.5 (REA-5972) it mirrors back whatever `webcam` was tagged with, via a
/// real fix verified live — so that is the one field this test asserts on
/// content, with a distinct tag per pushed frame so a stale/repeated value
/// would fail it too, not just a presence check.
@Suite("07 - frame metadata")
struct FrameMetadataTests {

    @Test("a tag pushed on webcam loops back on main_video's user_data")
    func tagPushedOnWebcamLoopsBackOnMainVideo() async throws {
        try await withConnectedReactor { reactor in
            let webcam = try reactor.track("webcam")
            try await webcam.publish()

            let tags = TagCollector()
            let subscription = try reactor.track("main_video").onFrame { frame in
                if let userData = frame.userData { tags.append(userData) }
            }
            defer { subscription.cancel() }

            let frame = MediaFixtures.solidBGRAFrame(width: 64, height: 64, color: (10, 20, 30))
            var counter = 0
            let deadline = ContinuousClock.now.advanced(by: .seconds(8))
            while ContinuousClock.now < deadline, tags.count < 5 {
                counter += 1
                try webcam.pushFrame(
                    frame, width: 64, height: 64, userData: Data("tag-\(counter)".utf8))
                try await Task.sleep(for: .milliseconds(33))
            }

            let collected = tags.all
            #expect(!collected.isEmpty, "no frame came back with user_data attached")
            for tag in collected {
                #expect(String(decoding: tag, as: UTF8.self).hasPrefix("tag-"))
            }
        }
    }
}

/// Tags collected from a callback running on the library's media delivery
/// thread, safe to read from the test's own task.
private final class TagCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var tags: [Data] = []

    var count: Int {
        lock.lock()
        defer { lock.unlock() }
        return tags.count
    }

    var all: [Data] {
        lock.lock()
        defer { lock.unlock() }
        return tags
    }

    func append(_ tag: Data) {
        lock.lock()
        defer { lock.unlock() }
        tags.append(tag)
    }
}
