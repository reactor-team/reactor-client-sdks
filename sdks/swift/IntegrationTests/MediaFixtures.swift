import Foundation
import Reactor
import Testing

/// Push solid-`color` 64x64 BGRA frames into `track` at ~30fps until the
/// returned task is cancelled.
///
/// `reactor/echo` only emits `main_video` once it has read a `webcam` frame
/// (`echo_model.py`'s `run()` skips a tick with nothing to read), so every
/// scenario that wants output publishes `webcam` and keeps this running for as
/// long as it wants `main_video` to keep producing — mirrors
/// `sdks/python/integration-tests/tests/test_tracks_and_frames.py`'s `_pump`.
@discardableResult
func pumpFrames(
    into track: Track, color: (b: UInt8, g: UInt8, r: UInt8) = (10, 20, 30),
    width: Int = 64, height: Int = 64, fps: Double = 30
) -> Task<Void, Never> {
    let frame = MediaFixtures.solidBGRAFrame(width: width, height: height, color: color)
    return Task {
        while !Task.isCancelled {
            // A push after unpublish()/disconnect() throws — swallowed here
            // rather than propagated, since a pump's job ends with the task
            // being cancelled, not with reporting its own last frame's fate.
            try? track.pushFrame(frame, width: UInt32(width), height: UInt32(height))
            try? await Task.sleep(for: .seconds(1 / fps))
        }
    }
}

/// Deterministic, synthetic frames — not a webcam or mic — so pixel assertions
/// against reactor/echo's effects are exact rather than dependent on whatever a
/// fake device happens to generate. Same reasoning as the Python suite's
/// `solid_rgb_frame`/`sine_wave_samples` and the JS harness's synthetic
/// canvas/audio-tone fixtures.
enum MediaFixtures {

    /// A BGRA frame of `color`, `width * height * 4` bytes — exactly what
    /// `Track.pushFrame` accepts.
    static func solidBGRAFrame(
        width: Int, height: Int, color: (b: UInt8, g: UInt8, r: UInt8)
    )
        -> Data
    {
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        var index = 0
        while index < pixels.count {
            pixels[index] = color.b
            pixels[index + 1] = color.g
            pixels[index + 2] = color.r
            pixels[index + 3] = 255
            index += 4
        }
        return Data(pixels)
    }

    /// `numSamples` of a `frequencyHz` tone — exactly what `Track.pushAudioFrame`
    /// accepts. A4, comfortably audible; nothing in this suite rides on the
    /// exact frequency.
    static func sineWaveSamples(
        numSamples: Int, sampleRate: Int = 48_000, frequencyHz: Double = 440.0
    ) -> [Int16] {
        (0..<numSamples).map { i in
            let t = Double(i) / Double(sampleRate)
            let tone = sin(2 * Double.pi * frequencyHz * t) * 8000  // headroom under Int16 max
            return Int16(tone.rounded())
        }
    }

    /// Assert `frame`'s mean colour is within `tolerance` per channel of
    /// `expected`.
    ///
    /// Not exact equality: `main_video` has gone through a real WebRTC
    /// video encode/decode round trip by the time it reaches `onFrame`, and a
    /// lossy codec does not reproduce a solid fill exactly, especially at its
    /// edges. The mean over the whole frame is what a solid-colour input
    /// actually guarantees survives that.
    static func assertDominantColor(
        _ frame: VideoFrame, expected: (b: UInt8, g: UInt8, r: UInt8), tolerance: Double = 30,
        sourceLocation: SourceLocation = #_sourceLocation
    ) {
        let count = Int(frame.width) * Int(frame.height)
        guard count > 0 else {
            Issue.record("frame has no pixels to average", sourceLocation: sourceLocation)
            return
        }

        var sums = (b: 0.0, g: 0.0, r: 0.0)
        frame.pixels.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            let bytes = raw.bindMemory(to: UInt8.self)
            for i in 0..<count {
                sums.b += Double(bytes[i * 4])
                sums.g += Double(bytes[i * 4 + 1])
                sums.r += Double(bytes[i * 4 + 2])
            }
        }
        let mean = (
            b: sums.b / Double(count), g: sums.g / Double(count), r: sums.r / Double(count)
        )

        #expect(
            abs(mean.b - Double(expected.b)) <= tolerance,
            "blue \(mean.b) is not within \(tolerance) of \(expected.b)",
            sourceLocation: sourceLocation)
        #expect(
            abs(mean.g - Double(expected.g)) <= tolerance,
            "green \(mean.g) is not within \(tolerance) of \(expected.g)",
            sourceLocation: sourceLocation)
        #expect(
            abs(mean.r - Double(expected.r)) <= tolerance,
            "red \(mean.r) is not within \(tolerance) of \(expected.r)",
            sourceLocation: sourceLocation)
    }
}
