import Foundation
import Reactor
import Testing

/// The audio data plane — `pushFrame`/`onFrame` on an audio track — new to
/// this suite, not mirrored from Python or JS: neither of those suites' own
/// scenarios cover it either. Mirrors `sdks/cpp/integration-tests/audio_test.cpp`.
///
/// Real device I/O (`ReactorMedia`'s `Speaker`/`Microphone`) is deliberately
/// out of scope: what's testable without any hardware, in CI, is
/// `Track.pushFrame`/`onFrame` itself — synthetic PCM in, synthetic PCM out —
/// the same way the rest of this suite pushes synthetic BGRA rather than
/// reading a webcam.
///
/// `reactor/echo` declares `mic` (sendonly) and `main_audio` (recvonly), and
/// passes audio through unchanged. Its own tick loop only advances on a
/// `webcam` read, though: `main_audio` is never emitted unless `webcam` is
/// also being pumped, regardless of whether `mic` has anything queued. Every
/// test here publishes and pumps both for that reason, even the one that only
/// asserts on audio.
@Suite("Audio: push_frame/on_frame on an audio track")
struct AudioTests {

    @Test("publish(mic) + pushFrame reaches main_audio")
    func publishMicAndPushFrameReachesMainAudio() async throws {
        try await withConnectedReactor { reactor in
            let webcam = try reactor.track("webcam")
            try await webcam.publish()
            let videoPump = pumpFrames(into: webcam)
            defer { videoPump.cancel() }

            let mic = try reactor.track("mic")
            try await mic.publish()
            let audioPump = pumpAudio(into: mic)
            defer { audioPump.cancel() }

            let mainAudio = try reactor.track("main_audio")
            let audibleChunks = AudibleChunkCounter()
            let subscription = try mainAudio.onFrame { (frame: AudioFrame) in
                guard !frame.samples.isEmpty else { return }
                let sumAbs = frame.samples.reduce(0.0) { $0 + abs(Double($1)) }
                let meanAbs = sumAbs / Double(frame.samples.count)
                // A real tone's mean absolute amplitude sits well above digital
                // silence (near-zero); no attempt to match the pushed tone's
                // exact amplitude — this has gone through a real Opus
                // encode/decode round trip, the audio equivalent of the video
                // suite's colour-tolerance assertions.
                if meanAbs > 50.0 {
                    audibleChunks.increment()
                }
            }
            defer { subscription.cancel() }

            try await waitUntil(timeout: .seconds(10)) { audibleChunks.value >= 3 }

            try mic.unpublish()
            try webcam.unpublish()
        }
    }

    @Test("pushing audio before publish() raises invalidState")
    func pushingAudioBeforePublishIsRefused() async throws {
        try await withConnectedReactor { reactor in
            let mic = try reactor.track("mic")

            let pcm = MediaFixtures.sineWaveSamples(numSamples: 960)  // 20ms @ 48kHz mono
            #expect(throws: ReactorError.self) {
                try mic.pushFrame(pcm, sampleRate: 48000, channels: 1)
            }
        }
    }

    @Test("an AudioFrame handler on a video track is refused")
    func audioFrameHandlerOnVideoTrackIsRefused() async throws {
        try await withConnectedReactor { reactor in
            let webcam = try reactor.track("webcam")  // video kind
            do {
                _ = try webcam.onFrame { (_: AudioFrame) in }
                Issue.record("expected a throw")
            } catch let error as ReactorError {
                #expect(error.code == .invalidState)
            }
        }
    }

    @Test("a VideoFrame handler on an audio track is refused")
    func videoFrameHandlerOnAudioTrackIsRefused() async throws {
        try await withConnectedReactor { reactor in
            let mic = try reactor.track("mic")  // audio kind
            do {
                _ = try mic.onFrame { (_: VideoFrame) in }
                Issue.record("expected a throw")
            } catch let error as ReactorError {
                #expect(error.code == .invalidState)
            }
        }
    }

    @Test("pushFrame with a sample count that doesn't divide evenly by channels is refused")
    func raggedSampleCountIsRefused() async throws {
        // Track.swift's own docs state the requirement without saying what
        // happens if it isn't met — probed here rather than assumed.
        try await withConnectedReactor { reactor in
            let mic = try reactor.track("mic")
            try await mic.publish()

            let oddPCM = MediaFixtures.sineWaveSamples(numSamples: 961)  // 961 does not divide by 2
            do {
                try mic.pushFrame(oddPCM, sampleRate: 48000, channels: 2)
                Issue.record("expected a throw")
            } catch let error as ReactorError {
                #expect(error.code == .badRequest)
            }

            try mic.unpublish()
        }
    }
}

/// A plain thread-safe counter — this suite's `onFrame` callback runs inline
/// on the library's own delivery thread, so a bare `var` would race with the
/// test body reading it.
private final class AudibleChunkCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0

    var value: Int {
        lock.lock()
        defer { lock.unlock() }
        return count
    }

    func increment() {
        lock.lock()
        count += 1
        lock.unlock()
    }
}
