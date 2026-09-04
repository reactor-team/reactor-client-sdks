import AVFoundation
import Foundation
import Reactor
import os

#if os(iOS)
    import UIKit
#endif

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

    /// Scratch space for compacting padded rows, reused across frames.
    ///
    /// Touched only from `queue`, which delivers serially.
    private var packed = [UInt8]()

    #if os(iOS)
        private var orientationObserver: NSObjectProtocol?

        /// Whether *this* object asked UIKit to generate orientation
        /// notifications, so stopping does not switch them off underneath an app
        /// that wanted them for itself.
        private var startedOrientationNotifications = false
    #endif

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
        #if os(iOS)
            beginTrackingOrientation()
        #endif
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
        #if os(iOS)
            endTrackingOrientation()
        #endif
        guard session.isRunning else { return }
        session.stopRunning()
    }

    #if os(iOS)

        /// Follow the device's orientation for as long as capture runs.
        ///
        /// The rotation is asked of the capture connection rather than done in
        /// the delegate below: AVFoundation applies it inside the capture
        /// pipeline, where it costs far less than rotating every frame by hand.
        ///
        /// UIKit's device orientation is main-thread state, so the whole of this
        /// happens there.
        private func beginTrackingOrientation() {
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                let device = UIDevice.current
                if !device.isGeneratingDeviceOrientationNotifications {
                    device.beginGeneratingDeviceOrientationNotifications()
                    startedOrientationNotifications = true
                }
                orientationObserver = NotificationCenter.default.addObserver(
                    forName: UIDevice.orientationDidChangeNotification,
                    object: device,
                    queue: .main
                ) { [weak self] _ in
                    self?.applyRotation(CaptureOrientation.current(UIDevice.current.orientation))
                }
                // The orientation the device is already in: waiting for a change
                // would leave the first seconds of every session sideways.
                applyRotation(CaptureOrientation.current(device.orientation))
            }
        }

        private func endTrackingOrientation() {
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                if let orientationObserver {
                    NotificationCenter.default.removeObserver(orientationObserver)
                }
                orientationObserver = nil
                if startedOrientationNotifications {
                    UIDevice.current.endGeneratingDeviceOrientationNotifications()
                    startedOrientationNotifications = false
                }
            }
        }

        /// Point the capture connection the right way up.
        ///
        /// `nil` means the device reported face up, face down or unknown — none
        /// of which name a way up — so whatever was set last is kept.
        private func applyRotation(_ orientation: CaptureOrientation?) {
            guard let orientation, let connection = output.connection(with: .video) else { return }
            if #available(iOS 17.0, *) {
                let angle = orientation.degrees
                guard connection.isVideoRotationAngleSupported(angle) else { return }
                connection.videoRotationAngle = angle
            } else if connection.isVideoOrientationSupported {
                connection.videoOrientation = orientation.videoOrientation
            }
        }
    #endif
}

/// Copy `height` rows of `rowBytes` each out of a buffer whose rows sit `stride`
/// apart, into a contiguous destination.
///
/// A free function, and internal, so it can be tested against a buffer built by
/// hand: this is the one place in the capture path where an off-by-one produces
/// a picture that is plausible and wrong — sheared by a few pixels a row, which
/// reads as a bad camera rather than as a bug here.
///
/// `destination` must have room for `rowBytes * height`.
func packRows(
    from base: UnsafeRawPointer,
    stride: Int,
    rowBytes: Int,
    height: Int,
    into destination: UnsafeMutableRawPointer
) {
    for row in 0..<height {
        memcpy(destination + row * rowBytes, base + row * stride, rowBytes)
    }
}

extension Camera {

    /// Pack a padded frame into ``packed``, sizing it to fit.
    ///
    /// Sized exactly, not merely grown: `pushFrame` is handed the whole buffer,
    /// and a longer one would pass its length check while describing a frame that
    /// is not the one in it. Dimensions change on a rotation and almost never
    /// otherwise, so this reallocates about as often as someone turns the phone.
    fileprivate func pack(
        from base: UnsafeRawPointer, stride: Int, rowBytes: Int, height: UInt32
    ) {
        let needed = rowBytes * Int(height)
        if packed.count != needed {
            packed = [UInt8](repeating: 0, count: needed)
        }
        packed.withUnsafeMutableBytes { destination in
            guard let target = destination.baseAddress else { return }
            packRows(
                from: base, stride: stride, rowBytes: rowBytes, height: Int(height), into: target)
        }
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
            let rowBytes = Int(width) * 4

            do {
                // The capture time comes from the SDK's clock rather than the
                // sample buffer's: it is the epoch the far end reads, and sharing
                // one reading across tracks is what synchronises them.
                let captureTime = Reactor.timeMicros()

                if stride == rowBytes {
                    // The sensor's own memory, pushed untouched. Every Mac lands
                    // here, and so does a phone whose frames need no packing.
                    try track.pushFrame(
                        UnsafeRawBufferPointer(start: base, count: rowBytes * Int(height)),
                        width: width,
                        height: height,
                        captureTimeUs: captureTime)
                } else {
                    // A padded stride makes `width * height * 4` a lie, and the
                    // FFI reads exactly that many bytes, so the rows are packed
                    // into a contiguous buffer rather than sent sheared.
                    //
                    // This used to refuse the frame. That became the wrong answer
                    // the moment capture rotation arrived: AVFoundation aligns
                    // the rows of a rotated buffer, so a portrait iPhone would
                    // have pushed nothing at all and said so only in a log line —
                    // trading sideways video for no video. A copy on this path is
                    // the cheaper mistake, and the unpadded path above still does
                    // no copying at all.
                    pack(from: base, stride: stride, rowBytes: rowBytes, height: height)
                    try packed.withUnsafeBytes { buffer in
                        try track.pushFrame(
                            buffer, width: width, height: height, captureTimeUs: captureTime)
                    }
                }
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
