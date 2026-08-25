import AVFoundation
import Foundation
import Reactor
import os

/// A camera, pushing what it sees into a sendonly video track.
///
/// ```swift
/// let source = try reactor.track("source")
/// try await source.publish()
/// let camera = try Camera(pushingInto: source)
/// try camera.start()
/// ```
///
/// ## Why this is a separate product
///
/// `import ReactorMedia` is what asks for a camera and a microphone. `import
/// Reactor` alone touches no device and needs no usage description in an app's
/// Info.plist — which matters on iOS, where a camera usage string is a line in
/// the App Store review and a permission prompt for every user, including the
/// ones whose app only *receives* video.
///
/// ## The capture format is asked for, not converted
///
/// The session is configured to emit `kCVPixelFormatType_32BGRA`, which is what
/// the FFI wants, so nothing here converts pixels. A device that cannot emit it
/// is refused rather than silently fed through a conversion nobody asked for.
public final class Camera: NSObject, @unchecked Sendable {

    private let track: Track
    private let session = AVCaptureSession()
    private let output = AVCaptureVideoDataOutput()
    private let queue = DispatchQueue(label: "inc.reactor.media.camera")
    private let gate = CaptureGate()
    private let log = Logger(subsystem: "inc.reactor.sdk", category: "camera")

    private let refused = Counter()

    /// How many frames were captured but not pushed.
    ///
    /// Two causes, counted together because the answer to both is the same: the
    /// camera was stopping, or the track refused the push — which almost always
    /// means it is not published. **This is the number to read when the camera is
    /// running and the model sees nothing.**
    public var droppedFrames: Int { gate.droppedCaptures + refused.current }

    /// How many frames the track accepted.
    public var pushedFrames: Int { gate.delivered - refused.current }

    /// Capture from `device` — the default camera unless one is named — into
    /// `track`.
    ///
    /// - Throws: ``ReactorError`` when there is no camera, when the platform does
    ///   not give apps one, or when the track is not one this client can send on.
    public init(pushingInto track: Track, device: AVCaptureDevice? = nil) throws {
        self.track = track
        super.init()

        if let direction = track.direction, direction != .sendonly {
            throw ReactorError(
                .invalidState,
                "Camera cannot push into '\(track.name)': the session declares it "
                    + "\(direction.rawValue), so the model sends on it and this client receives.",
                operation: "camera")
        }

        guard let camera = device ?? AVCaptureDevice.default(for: .video) else {
            // visionOS in compatibility mode is the case worth naming: an iPad
            // app there gets no camera at all, so this is not a missing
            // permission and not a device that will appear later.
            throw ReactorError(
                .notFound,
                "no video capture device. On visionOS an app running in "
                    + "compatibility mode has no camera, so pushing captured video is not "
                    + "possible there — receiving video and pushing app-generated frames are.",
                operation: "camera")
        }

        session.beginConfiguration()
        defer { session.commitConfiguration() }

        let input = try AVCaptureDeviceInput(device: camera)
        guard session.canAddInput(input) else {
            throw ReactorError(
                .invalidState, "this camera cannot be added to a capture session",
                operation: "camera")
        }
        session.addInput(input)

        // BGRA, because that is what the FFI reads. Asking the device for it
        // means no conversion sits between the sensor and the wire.
        let bgra = kCVPixelFormatType_32BGRA
        guard output.availableVideoPixelFormatTypes.contains(bgra) else {
            throw ReactorError(
                .invalidState,
                "this camera cannot emit 32BGRA, which is the only format the SDK pushes. "
                    + "Converting here would cost a copy per frame and hide the surprise.",
                operation: "camera")
        }
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: bgra]
        // Newest frame wins, matching what the FFI does with a slow handler:
        // a stale frame costs latency and buys nothing.
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: queue)

        guard session.canAddOutput(output) else {
            throw ReactorError(
                .invalidState, "this capture session will not take a video output",
                operation: "camera")
        }
        session.addOutput(output)
    }

    deinit {
        // Same order as stop(): flag first, device second, no lock held across
        // the teardown.
        gate.close()
        if session.isRunning { session.stopRunning() }
    }

    /// Start capturing.
    ///
    /// The gate opens before the device starts, so a frame that arrives
    /// immediately is delivered rather than dropped.
    public func start() throws {
        guard !session.isRunning else { return }
        gate.open()
        session.startRunning()
    }

    /// Stop capturing.
    ///
    /// Closes the gate first and releases its lock, **then** stops the session.
    /// `stopRunning()` waits for the delegate callback that may be running right
    /// now, and that callback takes the same lock — so holding it here would be
    /// waiting for the thread that is waiting for us.
    public func stop() {
        gate.close()
        guard session.isRunning else { return }
        session.stopRunning()
    }
}

extension Camera: AVCaptureVideoDataOutputSampleBufferDelegate {

    /// One captured frame, on the capture queue.
    public func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard let pixels = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        gate.withDelivery { [self] in
            CVPixelBufferLockBaseAddress(pixels, .readOnly)
            defer { CVPixelBufferUnlockBaseAddress(pixels, .readOnly) }

            guard let base = CVPixelBufferGetBaseAddress(pixels) else { return }
            let width = UInt32(CVPixelBufferGetWidth(pixels))
            let height = UInt32(CVPixelBufferGetHeight(pixels))
            let stride = CVPixelBufferGetBytesPerRow(pixels)

            // A padded row stride would make `width * height * 4` a lie, and the
            // FFI reads exactly that many bytes. Refusing here beats sending a
            // sheared picture.
            guard stride == Int(width) * 4 else {
                refused.increment()
                log.error(
                    "dropping a frame: row stride \(stride) is not \(width) * 4, so the buffer is padded and cannot be pushed as-is"
                )
                return
            }

            do {
                // The capture time comes from the SDK's clock rather than the
                // sample buffer's: it is the epoch the far end reads, and sharing
                // one reading across tracks is what synchronises them.
                try track.pushFrame(
                    UnsafeRawBufferPointer(start: base, count: Int(width) * Int(height) * 4),
                    width: width,
                    height: height,
                    captureTimeUs: Reactor.timeMicros())
            } catch {
                // Almost always "not published yet". Logged rather than thrown,
                // because a delegate cannot throw — and counted, so
                // `droppedFrames` can answer "why does the model see nothing".
                refused.increment()
                log.debug("dropping a captured frame: \(error, privacy: .public)")
            }
        }
    }
}
