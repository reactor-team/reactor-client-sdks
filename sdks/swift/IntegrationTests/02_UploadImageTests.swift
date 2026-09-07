import CoreGraphics
import ExampleSupport
import Foundation
import ImageIO
import Reactor
import Testing
import UniformTypeIdentifiers

/// Scenario 02, scripted: upload a file, pass the `FileRef` into a command.
/// `reactor/echo`'s `set_overlay_image` is what this suite has that actually
/// consumes an upload and produces an assertable effect — mirrors
/// `sdks/python/integration-tests/tests/test_upload_and_conditioning.py`.
@Suite("02 - upload image")
struct UploadImageTests {

    @Test("an uploaded file's reference is usable")
    func uploadedFileReferenceIsUsable() async throws {
        try await withConnectedReactor { reactor in
            let png = try makeSolidPNG(width: 8, height: 8, color: (200, 30, 90))
            let uploaded = try await reactor.uploadData(
                png, name: "overlay.png", mimeType: "image/png")

            #expect(!uploaded.uploadID.isEmpty)
            #expect(uploaded.name == "overlay.png")
            #expect(uploaded.mimeType == "image/png")
            #expect(uploaded.size == png.count)
        }
    }

    @Test("set_overlay_image at full strength dominates main_video")
    func setOverlayImageAtFullStrengthDominatesOutput() async throws {
        try await withConnectedReactor { reactor in
            let overlayColor: (b: UInt8, g: UInt8, r: UInt8) = (15, 60, 220)
            let png = try makeSolidPNG(width: 16, height: 16, color: overlayColor)
            let uploaded = try await reactor.uploadData(
                png, name: "overlay.png", mimeType: "image/png")

            let webcam = try reactor.track("webcam")
            try await webcam.publish()
            // A different colour from the overlay, so a passthrough (the effect
            // failing to apply) is distinguishable from the overlay taking over.
            let pump = pumpFrames(into: webcam, color: (10, 10, 10))
            defer { pump.cancel() }

            // Set the overlay only once frames are already flowing, mirroring a
            // caller conditioning a live session rather than one that hasn't
            // started yet. The reference travels via `uploads:`, not embedded
            // in the JSON payload — see `sendCommand`'s own doc.
            try await Task.sleep(for: .milliseconds(500))
            try await reactor.sendCommand(
                "set_overlay_image", ["overlay_strength": 1.0],
                uploads: ["overlay_image": uploaded])

            let counter = FrameCounter(label: "swift-integration-02-post-overlay")
            let frames = LatestFrameBox()
            let subscription = try reactor.track("main_video").onFrame {
                counter.submit($0)
                frames.set($0)
            }
            defer { subscription.cancel() }

            // At least a few frames past the command, not just the first one:
            // the effect takes a moment to reach a frame the model has not
            // already started encoding — Python's suite found the same.
            try await waitUntil(timeout: .seconds(8)) { counter.frames >= 3 }
            let frame = try #require(frames.value)

            // overlay_strength=1.0 replaces the frame with the (resized) overlay
            // outright — the webcam's own colour should not show through at all.
            MediaFixtures.assertDominantColor(frame, expected: overlayColor, tolerance: 35)
        }
    }
}

/// A solid-colour PNG, via ImageIO — the same reliable path
/// `ExampleSupport.writePNG` uses for frame snapshots, just encoding to `Data`
/// instead of a file.
func makeSolidPNG(width: Int, height: Int, color: (b: UInt8, g: UInt8, r: UInt8)) throws -> Data {
    var pixels = [UInt8](repeating: 0, count: width * height * 4)
    var offset = 0
    while offset < pixels.count {
        pixels[offset] = color.b
        pixels[offset + 1] = color.g
        pixels[offset + 2] = color.r
        pixels[offset + 3] = 255
        offset += 4
    }

    guard let provider = CGDataProvider(data: Data(pixels) as CFData),
        let image = CGImage(
            width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
            bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo(
                rawValue: CGImageAlphaInfo.premultipliedFirst.rawValue
                    | CGBitmapInfo.byteOrder32Little.rawValue),
            provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)
    else {
        throw IntegrationTestSetupError("could not build a \(width)x\(height) solid-colour image")
    }

    let output = NSMutableData()
    guard
        let destination = CGImageDestinationCreateWithData(
            output as CFMutableData, UTType.png.identifier as CFString, 1, nil)
    else {
        throw IntegrationTestSetupError("could not create a PNG destination")
    }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else {
        throw IntegrationTestSetupError("could not encode a solid-colour PNG")
    }
    return output as Data
}

/// The latest frame a callback has seen, safe to read from another thread.
final class LatestFrameBox: @unchecked Sendable {
    private let lock = NSLock()
    private var frame: VideoFrame?

    var value: VideoFrame? {
        lock.lock()
        defer { lock.unlock() }
        return frame
    }

    func set(_ newValue: VideoFrame) {
        lock.lock()
        defer { lock.unlock() }
        frame = newValue
    }
}
