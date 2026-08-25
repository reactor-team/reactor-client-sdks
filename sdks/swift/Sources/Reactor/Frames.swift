import Foundation

/// A decoded video frame, copied so it can be kept.
///
/// The pixels arrive from the library valid only for the duration of the
/// callback. This form copies them, which is what most callers want; the one
/// that does not — a renderer handing bytes straight to a texture — should use
/// ``Track/onRawFrame(_:)`` and not copy at all.
public struct VideoFrame: Sendable {

    /// The declared track this frame arrived on.
    ///
    /// Every recvonly video track decodes into one callback in the library, so
    /// the name is the only thing that tells them apart. Empty when the
    /// transceiver could not be matched to a declared track.
    public let trackName: String

    /// BGRA pixels — blue, green, red, alpha — `width * height * 4` bytes.
    public let pixels: Data

    /// Frame width in pixels.
    public let width: UInt32

    /// Frame height in pixels.
    public let height: UInt32

    /// The sender's frame counter, or 0 when the frame carried no trailer.
    public let frameID: UInt64

    /// When the sender says it captured this frame, in microseconds **on the
    /// sender's own clock**.
    ///
    /// Differences between stamps from one sender are what this supports; it is
    /// not comparable with a local clock. 0 when the frame carried no trailer.
    public let captureTimeUS: UInt64

    /// The bytes the sender tagged this frame with, if any.
    ///
    /// Their meaning is between the caller and the model — JSON, protobuf,
    /// anything. `nil` when the frame carried no trailer, and **also** when the
    /// far end never declared that it writes tags: no published model attaches
    /// one today.
    public let userData: Data?
}

/// A video frame as the library handed it over: borrowed, and gone when the
/// handler returns.
///
/// The buffers point into the library's own memory for exactly the duration of
/// the callback. Copy anything you keep — retaining a pointer here is a
/// use-after-free that reproduces under load and not in tests.
public struct RawVideoFrame: ~Copyable {

    /// The declared track this frame arrived on.
    public let trackName: String

    /// BGRA pixels, `width * height * 4` bytes, valid until this handler returns.
    public let pixels: UnsafeRawBufferPointer

    /// Frame width in pixels.
    public let width: UInt32

    /// Frame height in pixels.
    public let height: UInt32

    /// The sender's frame counter, or 0 when the frame carried no trailer.
    public let frameID: UInt64

    /// The sender's capture time in microseconds, on its own clock. 0 without a
    /// trailer.
    public let captureTimeUS: UInt64

    /// The sender's tag, valid until this handler returns. `nil` when absent.
    public let userData: UnsafeRawBufferPointer?
}

/// A decoded audio frame.
///
/// Interleaved 16-bit PCM, copied so it can be kept. Audio arrives in short
/// buffers — roughly 10 ms each — on a queue that keeps its backlog rather than
/// dropping, because there the queue is the jitter buffer and a hole in it is
/// audible.
public struct AudioFrame: Sendable {

    /// The declared track this frame arrived on.
    public let trackName: String

    /// Interleaved signed 16-bit samples, `numSamples` of them in total across
    /// all channels.
    public let samples: [Int16]

    /// Samples per second, per channel.
    public let sampleRate: UInt32

    /// How many channels are interleaved in ``samples``.
    public let channels: UInt32
}
