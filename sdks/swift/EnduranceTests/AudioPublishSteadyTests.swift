import EnduranceReporting
import Foundation
import Reactor
import TestSupport
import Testing

/// Endurance: publish a single audio track once and hold it, continuously
/// pushing PCM chunks at a steady rate for the whole run — no pause, no
/// unpublish, no reconnect, no other operation in between. Mirrors
/// `sdks/python/endurance-tests/tests/test_audio_publish_steady.py`.
///
/// Same steady-state shape as VideoPublishSteadyTests.swift, mirrored for
/// audio rather than parameterizing one scenario over both — the two paths
/// share nothing below `pushFrame()` (separate encoder/decoder threads in
/// the native runtime), so a leak in one is not evidence about the other,
/// and a report that says "audio-publish-steady failed" is more useful
/// standing on its own than folded into a single parameterized
/// "media-publish-steady[audio]" name.
///
/// Run with:
///
///     ENDURANCE_DURATION_SECONDS=3600 mise run test:swift:endurance-tests
extension EnduranceTests {

    // reactor/echo declares `mic` (sendonly) and `main_audio` (recvonly) —
    // see sdks/python/integration-tests/tests/test_audio.py's own comment.
    // This scenario only ever publishes `mic`; it never subscribes to
    // `main_audio`, so unlike the churn scenarios' `main_video` subscription
    // there is nothing here that depends on `webcam` also being pumped.
    private static let audioPublishSteadyTrackName = "mic"
    private static let audioPublishSteadyFPS = 30
    private static let audioPublishSteadySampleRate = 48_000
    private static let audioPublishSteadyChannels: UInt32 = 1
    // One chunk per tick, same convention as
    // TestSupport/Fixtures.swift's pumpAudio.
    private static let audioPublishSteadyChunkSamples =
        audioPublishSteadySampleRate / audioPublishSteadyFPS

    private static let audioPublishSteadyDescription =
        "One audio track, published once and held for the whole run: continuous push_frame() "
        + "PCM chunks at \(audioPublishSteadyFPS)/s (\(audioPublishSteadySampleRate) Hz), no "
        + "pause, no unpublish, no reconnect — isolates the audio send path (separate from "
        + "video's) in the same steady-state shape as video-publish-steady."

    @Test("Audio publish steady: continuous streaming leaves no sustained growth")
    func audioPublishSteadyHasNoSustainedGrowth() async throws {
        let sampler = ResourceSampler()
        let live = LiveReporter(
            testName: "audio-publish-steady", durationS: EnduranceConfig.durationSeconds)
        let tone = MediaFixtures.sineWaveSamples(
            numSamples: Self.audioPublishSteadyChunkSamples,
            sampleRate: Self.audioPublishSteadySampleRate)
        var iteration = 0
        var metrics: [MetricResult] = []
        let startedAt = nowISO()
        var pending: (any Error)?

        do {
            try await withConnectedReactor { reactor in
                let track = try reactor.track(Self.audioPublishSteadyTrackName)
                try await track.publish()
                defer { try? track.unpublish() }

                while !sampler.deadlineReached {
                    // One second of continuous streaming, then one sample —
                    // same reasoning as video-publish-steady's identical
                    // shape: keeps the sample count bounded to roughly one
                    // per second rather than one per push_frame() call.
                    for _ in 0..<Self.audioPublishSteadyFPS {
                        try? track.pushFrame(
                            tone, sampleRate: UInt32(Self.audioPublishSteadySampleRate),
                            channels: Self.audioPublishSteadyChannels)
                        try await Task.sleep(
                            for: .seconds(1.0 / Double(Self.audioPublishSteadyFPS)))
                    }

                    sampler.sample(cycle: iteration)
                    live.update(sampler.samples)
                    iteration += 1
                }
            }
        } catch {
            pending = error
        }

        if pending == nil {
            if iteration >= 3 {
                do {
                    metrics = try standardResourceMetrics(sampler.samples)
                } catch {
                    pending = error
                }
            } else {
                pending = EnduranceAssertionError(
                    "only completed \(iteration) second(s) of streaming — raise "
                        + "ENDURANCE_DURATION_SECONDS to get enough data for a trend")
            }
        }

        try finishAndCheck(
            testName: "audio-publish-steady", sdk: "Swift",
            description: Self.audioPublishSteadyDescription, sdkVersion: ReactorSDK.version,
            durationS: EnduranceConfig.durationSeconds, startedAt: startedAt,
            samples: sampler.samples, live: live, metrics: metrics, iterations: iteration,
            runError: pending.map { "\(type(of: $0)): \($0)" })
        if let pending {
            throw pending
        }
    }
}
