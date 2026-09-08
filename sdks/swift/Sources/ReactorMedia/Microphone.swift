import AVFoundation
import Foundation
import Reactor
import os

/// A microphone, pushing what it hears into a sendonly audio track.
///
/// ```swift
/// let mic = try Microphone(pushingInto: try reactor.track("mic"))
/// try mic.start()
/// ```
///
/// The engine is asked for 48 kHz mono 16-bit — what the FFI takes — and a
/// converter bridges whatever the hardware actually gives. That conversion is
/// unavoidable: an input device's native format is its own business, and
/// `AVAudioEngine` will not resample an input tap for you.
public final class Microphone: @unchecked Sendable {

    /// What the FFI accepts, and therefore what this pushes.
    public static let sampleRate: Double = 48000

    /// Mono, which is what every model declares today.
    public static let channels: AVAudioChannelCount = 1

    private let track: Track
    private let engine = AVAudioEngine()
    private let gate = CaptureGate()

    private let tapLock = NSLock()

    /// Whether a tap is installed on the input bus.
    ///
    /// `engine.isRunning` cannot answer this, and using it as if it could is what
    /// crashed the app. An interruption stops the engine underneath us, so by the
    /// time `.began` reached `stop()` the engine already reported `false`; the
    /// `guard engine.isRunning` there skipped `removeTap`, and the `.ended`
    /// handler's `start()` installed a *second* tap on the same bus.
    /// `AVAudioEngine` treats that as a fatal precondition failure — so an
    /// ordinary phone call or Siri took the app with it.
    private var tapInstalled = false
    private let refused = Counter()
    private let log = Logger(subsystem: "inc.reactor.sdk", category: "microphone")

    /// How many buffers were captured but not pushed.
    public var droppedBuffers: Int { gate.droppedCaptures + refused.current }

    /// How many buffers the track accepted.
    public var pushedBuffers: Int { gate.delivered - refused.current }

    /// Capture the default input into `track`.
    public init(pushingInto track: Track) throws {
        self.track = track

        if let direction = track.direction, direction != .sendonly {
            throw ReactorError(
                .invalidState,
                "Microphone cannot push into '\(track.name)': the session declares it "
                    + "\(direction.rawValue).",
                operation: "microphone")
        }
        if let kind = track.kind, kind != .audio {
            throw ReactorError(
                .invalidState,
                "Microphone cannot push into '\(track.name)': the session declares it "
                    + "\(kind.rawValue).",
                operation: "microphone")
        }
    }

    deinit {
        gate.close()
        engine.stop()
    }

    /// Start capturing.
    ///
    /// On iOS this also puts the audio session into a category that permits
    /// recording. That belongs here rather than in the core SDK: an app that only
    /// receives audio has no business changing its own audio session, and on iOS
    /// changing it is audible — it can duck other apps.
    public func start() throws {
        // The tap, not the engine, is what makes this idempotent: an interrupted
        // engine is stopped but may still be tapped.
        guard !isTapped else { return }

        #if os(iOS)
            try configureAudioSession()
        #endif

        let input = engine.inputNode
        let hardware = input.inputFormat(forBus: 0)
        guard hardware.sampleRate > 0 else {
            throw ReactorError(
                .notFound,
                "no audio input device. On visionOS an app in compatibility mode has none.",
                operation: "microphone")
        }

        guard
            let wanted = AVAudioFormat(
                commonFormat: .pcmFormatInt16,
                sampleRate: Self.sampleRate,
                channels: Self.channels,
                interleaved: true),
            let converter = AVAudioConverter(from: hardware, to: wanted)
        else {
            throw ReactorError(
                .invalidState,
                "cannot convert the input's \(hardware) into 48 kHz mono int16",
                operation: "microphone")
        }

        gate.open()
        input.installTap(onBus: 0, bufferSize: 4800, format: hardware) { [self] buffer, _ in
            gate.withDelivery {
                push(buffer, through: converter, as: wanted)
            }
        }
        setTapped(true)

        do {
            try engine.start()
        } catch {
            // Undo the gate and the tap, or a failed start leaves both behind.
            gate.close()
            input.removeTap(onBus: 0)
            setTapped(false)
            throw ReactorError(
                .invalidState, "the audio engine would not start: \(error)",
                operation: "microphone")
        }
    }

    /// Stop capturing.
    ///
    /// The gate closes first, then the tap comes off, then the engine stops.
    /// `AVAudioEngine.stop()` waits for a tap that is running — the same shape as
    /// a capture device — so no lock is held across it.
    public func stop() {
        gate.close()

        // Removed regardless of `engine.isRunning`: an interruption has already
        // stopped the engine by the time this runs, and a tap left behind is a
        // fatal precondition failure on the next start rather than a leak.
        if takeTapped() {
            engine.inputNode.removeTap(onBus: 0)
        }
        if engine.isRunning {
            engine.stop()
        }
    }

    private var isTapped: Bool {
        tapLock.lock()
        defer { tapLock.unlock() }
        return tapInstalled
    }

    private func setTapped(_ value: Bool) {
        tapLock.lock()
        tapInstalled = value
        tapLock.unlock()
    }

    /// Clear the flag and answer what it was, so only one caller removes the tap.
    private func takeTapped() -> Bool {
        tapLock.lock()
        defer { tapLock.unlock() }
        let was = tapInstalled
        tapInstalled = false
        return was
    }

    private func push(
        _ buffer: AVAudioPCMBuffer,
        through converter: AVAudioConverter,
        as format: AVAudioFormat
    ) {
        let capacity = AVAudioFrameCount(
            Double(buffer.frameLength) * Self.sampleRate / buffer.format.sampleRate + 1)
        guard let converted = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else {
            refused.increment()
            return
        }

        var consumed = false
        var conversionError: NSError?
        converter.convert(to: converted, error: &conversionError) { _, status in
            if consumed {
                status.pointee = .noDataNow
                return nil
            }
            consumed = true
            status.pointee = .haveData
            return buffer
        }

        if let conversionError {
            refused.increment()
            log.error("dropping audio: \(conversionError, privacy: .public)")
            return
        }

        guard let channel = converted.int16ChannelData, converted.frameLength > 0 else {
            refused.increment()
            return
        }

        let samples = Array(
            UnsafeBufferPointer(start: channel[0], count: Int(converted.frameLength)))
        do {
            try track.pushAudioFrame(
                samples, sampleRate: UInt32(Self.sampleRate), channels: UInt32(Self.channels))
        } catch {
            refused.increment()
            log.debug("dropping a captured buffer: \(error, privacy: .public)")
        }
    }

    #if os(iOS)
        /// Put the session into a category that records, and cope with being
        /// interrupted.
        ///
        /// An interruption — a phone call, another app taking the session — stops
        /// the engine underneath us. Without handling it the microphone goes quiet
        /// and the track stays published, which is the silent failure this SDK
        /// exists to refuse: from the model's side it is indistinguishable from a
        /// room that went silent.
        private func configureAudioSession() throws {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker])
            try session.setActive(true)

            NotificationCenter.default.addObserver(
                forName: AVAudioSession.interruptionNotification,
                object: session,
                queue: nil
            ) { [weak self] notification in
                guard let self,
                    let raw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                    let type = AVAudioSession.InterruptionType(rawValue: raw)
                else { return }

                switch type {
                case .began:
                    log.info("audio session interrupted; the microphone is stopping")
                    stop()
                case .ended:
                    log.info("audio session interruption ended; restarting the microphone")
                    try? start()
                @unknown default:
                    break
                }
            }
        }
    #endif
}
