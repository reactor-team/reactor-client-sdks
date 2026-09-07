import ExampleSupport
import Foundation
import Reactor
import Testing

/// Scenario 06, scripted: request a clip and download it. Mirrors
/// `sdks/python/integration-tests/tests/test_recording.py`.
///
/// Readiness is in media time, not wall clock: the manifest appears once the
/// recording passes the end of the chunk holding the window. Python's own
/// suite found this the hard way — unpublishing (stopping generation
/// entirely) before requesting the clip left the boundary chunk never closing,
/// because `echo_model.py`'s `run()` only advances while it keeps reading
/// input. So generation keeps running here until after both the wait and the
/// download, not just up to the request.
@Suite("06 - record clip")
struct RecordClipTests {

    static let clipSeconds = 3.0

    @Test("a clip of generated media downloads to a playable fragmented-MP4 file")
    func clipOfGeneratedMediaDownloads() async throws {
        try await withConnectedReactor { reactor in
            let webcam = try reactor.track("webcam")
            try await webcam.publish()
            let pump = pumpFrames(into: webcam, color: (80, 40, 200))
            defer { pump.cancel() }

            // Generate past the window this test asks for before asking, so the
            // window is already fully generated — but keep pumping, so the
            // boundary chunk still has something to close.
            try await Task.sleep(for: .seconds(Self.clipSeconds + 2))

            let clip = try await reactor.requestClip(.seconds(Self.clipSeconds))
            #expect(clip.sessionID == reactor.sessionID)
            #expect(!clip.playlistURL.isEmpty)

            let destination = FileManager.default.temporaryDirectory
                .appendingPathComponent("reactor-integration-tests-clip-\(UUID()).mp4")
            defer { try? FileManager.default.removeItem(at: destination) }

            let result = try await reactor.download(
                clip, to: destination, readyTimeout: .seconds(30))
            #expect(result.bytes > 0)

            let handle = try FileHandle(forReadingFrom: destination)
            defer { try? handle.close() }
            let header = try handle.read(upToCount: 256) ?? Data()
            // Fragmented MP4: the init segment (ftyp/moov) goes in first — see
            // sdks/js/integration-tests/README.md's note on the same format.
            #expect(
                header.range(of: Data("ftyp".utf8)) != nil,
                "downloaded clip does not start with an MP4 init segment")
        }
    }
}
