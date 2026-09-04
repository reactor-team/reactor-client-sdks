import AVFoundation
import Foundation
import Reactor
import os

/// Plays the audio a recvonly track delivers.
///
/// ```swift
/// let speaker = try Speaker()
/// let playing = try reactor.track("audio_out").onAudio { speaker.play($0) }
/// try speaker.start()
/// ```
///
/// ## Why this one may hold a lock across teardown and the microphone may not
///
/// This is the asymmetry the C++ SDK found the hard way, and it is worth stating
/// where both live. A **render** path pulls: `AVAudioPlayerNode` asks for the
/// next buffer, and stopping it does not wait for anything this code holds. A
/// **capture** path pushes: stopping a capture device waits for the callback
/// currently running, so a lock shared with that callback deadlocks.
///
/// So the ring buffer here is guarded by an ordinary lock, and ``Microphone``
/// uses ``CaptureGate`` instead. **The symmetric fix deadlocks**, which is why
/// the two are written differently on purpose rather than by accident.
public final class Speaker: @unchecked Sendable {

    /// What the FFI delivers, and therefore what this plays.
    public static let sampleRate: Double = 48000

    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let format: AVAudioFormat
    private let lock = NSLock()
    private var queued = 0
    private let dropped = Counter()
    private let log = Logger(subsystem: "inc.reactor.sdk", category: "speaker")

    /// How many buffers were discarded because playback was already behind.
    ///
    /// Audio is the one place the FFI keeps a backlog rather than dropping,
    /// because there the queue *is* the jitter buffer. This is what happens when
    /// even that is not enough.
    public var droppedBuffers: Int { dropped.current }

    /// How deep the playback queue is, in buffers.
    public var queuedBuffers: Int {
        lock.lock()
        defer { lock.unlock() }
        return queued
    }

    /// A speaker on the default output.
    public init() throws {
        guard
            let format = AVAudioFormat(
                commonFormat: .pcmFormatInt16,
                sampleRate: Self.sampleRate,
                channels: 1,
                interleaved: true)
        else {
            throw ReactorError(
                .invalidState, "cannot describe 48 kHz mono int16 audio", operation: "speaker")
        }
        self.format = format

        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
    }

    deinit {
        player.stop()
        engine.stop()
    }

    /// Start playback.
    public func start() throws {
        guard !engine.isRunning else { return }

        #if os(iOS)
            let session = AVAudioSession.sharedInstance()
            // `.voiceChat` is only compatible with `.playAndRecord`. Asked for
            // with `.playback` it throws an incompatible-category error, so
            // start() could not start on iOS at all — the mode has to match the
            // category, and this side only plays.
            //
            // And the category is not simply overwritten: a `Microphone` in the
            // same app has already put the session into `.playAndRecord` with the
            // voice-processing mode it needs, and taking that away here would
            // silently end its capture. So playback-only is requested only when
            // nothing has asked to record.
            if session.category != .playAndRecord {
                try session.setCategory(.playback, mode: .default)
            }
            try session.setActive(true)
        #endif

        do {
            try engine.start()
        } catch {
            throw ReactorError(
                .invalidState, "the audio engine would not start: \(error)",
                operation: "speaker")
        }
        player.play()
    }

    /// Stop playback and discard whatever is queued.
    public func stop() {
        player.stop()
        engine.stop()
        lock.lock()
        queued = 0
        lock.unlock()
    }

    /// Queue a frame the SDK delivered.
    ///
    /// Called from ``Reactor/Track/onAudio(_:)``, which runs **inline on the
    /// library's own thread** — so this does as little as it can: copy the samples
    /// into a buffer and hand it to the player. It never blocks on playback.
    ///
    /// - Note: a deep queue is discarded rather than grown. Latency that keeps
    ///   climbing is worse than a gap, and a gap here is the honest signal that
    ///   the consumer cannot keep up.
    public func play(_ frame: AudioFrame) {
        guard frame.sampleRate == UInt32(Self.sampleRate), frame.channels == 1 else {
            dropped.increment()
            log.error(
                "dropping audio: \(frame.sampleRate) Hz / \(frame.channels)ch is not 48000 Hz mono, which is what this speaker was built for"
            )
            return
        }

        // Half a second of audio is already more delay than a conversation
        // tolerates; past that, throwing it away is the kinder answer.
        let limit = 25
        lock.lock()
        let behind = queued >= limit
        if !behind { queued += 1 }
        lock.unlock()

        guard !behind else {
            dropped.increment()
            return
        }

        guard
            let buffer = AVAudioPCMBuffer(
                pcmFormat: format, frameCapacity: AVAudioFrameCount(frame.samples.count)),
            let channel = buffer.int16ChannelData
        else {
            lock.lock()
            queued -= 1
            lock.unlock()
            dropped.increment()
            return
        }

        buffer.frameLength = AVAudioFrameCount(frame.samples.count)
        frame.samples.withUnsafeBufferPointer { samples in
            guard let base = samples.baseAddress else { return }
            channel[0].update(from: base, count: samples.count)
        }

        player.scheduleBuffer(buffer) { [self] in
            lock.lock()
            queued -= 1
            lock.unlock()
        }
    }
}
