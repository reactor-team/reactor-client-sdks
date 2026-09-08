import Foundation
import ImageIO
import Reactor
import UniformTypeIdentifiers

/// The one file the examples share.
///
/// Everything here is about *seeing* the frames and reading the environment.
/// Nothing about using the SDK lives here: a reader who has to open two files to
/// understand one example is reading one file too many.

// MARK: - Environment

/// What the examples read from the environment, in one place.
public enum Env {

    /// `REACTOR_API_KEY` — required against the cloud, unused locally.
    ///
    /// A key belongs in the environment and nowhere else: not in a file, not in a
    /// commit, not in an argument that lands in a shell history.
    public static var apiKey: String? { value("REACTOR_API_KEY") }

    /// `REACTOR_MODEL`, defaulting to whatever the example needs.
    public static func model(default fallback: String) -> String {
        value("REACTOR_MODEL") ?? fallback
    }

    /// `REACTOR_API_URL`, defaulting to the cloud.
    public static var apiURL: String { value("REACTOR_API_URL") ?? Reactor.defaultAPIURL }

    /// `REACTOR_LOCAL=1` — talk to a coordinator running locally.
    public static var local: Bool { value("REACTOR_LOCAL") == "1" }

    /// `REACTOR_SECONDS`, how long an example watches for frames.
    public static func seconds(default fallback: Double) -> Double {
        value("REACTOR_SECONDS").flatMap(Double.init) ?? fallback
    }

    /// `REACTOR_SHOW=1` — write PNG snapshots of the frames that arrive.
    public static var show: Bool { value("REACTOR_SHOW") == "1" }

    private static func value(_ name: String) -> String? {
        guard let raw = ProcessInfo.processInfo.environment[name], !raw.isEmpty else { return nil }
        return raw
    }
}

/// Run an example's body, and report a failure the way a script should.
///
/// Without this, a throw out of top-level code prints
/// `Swift/ErrorType.swift:254: Fatal error: Error raised at top level` and a
/// trap, which buries the one line that matters — and the process dies before
/// the event queue drains, so the status events that would explain it never
/// print either. Learned by watching example 03 fail against production.
public func runExample(_ body: () async throws -> Void) async {
    do {
        try await body()
    } catch let error as ReactorError {
        // Let the event queue drain before exiting. Status and runtime events are
        // delivered on a queue, and `exit` does not wait for it — so the very
        // events that explain a failure (a status leaving `ready`, a runtime
        // message saying why the session ended) are lost exactly when they are
        // wanted. A quarter of a second is cheap and it is the difference between
        // "connect failed" and knowing why.
        try? await Task.sleep(for: .milliseconds(250))
        // Give the reader the code, the message, and what to do about it.
        var lines = ["failed: \(error)"]
        if error.recoverable {
            lines.append("this one is marked recoverable — the same call may work shortly")
        }
        if error.code == .unauthorized {
            lines.append("check REACTOR_API_KEY, and that the key may reach this model")
        }
        fail(lines.joined(separator: "\n"))
    } catch {
        fail("failed: \(error)")
    }
}

/// Stop with a message, the way a script should.
public func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data((message + "\n").utf8))
    exit(1)
}

/// A client from the environment, or a message saying what is missing.
///
/// Every example starts here, and every example ends with `defer { reactor.close() }`
/// — a creator that goes away without disconnecting orphans the session, and the
/// next run cannot start until it clears.
public func connectedClient(model: String) async throws -> Reactor {
    if Env.local {
        return try Reactor(
            model: model, jwt: nil, apiURL: Env.apiURL, local: true, eventQueue: nil)
    }
    guard let key = Env.apiKey else {
        fail("set REACTOR_API_KEY — https://www.reactor.inc/account/api-keys")
    }
    // Scoped to this model: a token that can reach everything the key can is
    // fine server-to-server and wrong everywhere else.
    return try await Reactor(
        model: model, apiKey: key, apiURL: Env.apiURL,
        options: .init(models: [model]), local: Env.local)
}

/// Print status, errors and both message channels.
///
/// The returned subscriptions have to be kept: they cancel when released.
///
/// **The runtime channel is not optional decoration.** When a session ends, the
/// reason arrives there — not on the model's channel, and not as an error on the
/// call that fails afterwards. Example 06 failed with "the clip was not ready
/// when the session ended" and I could not say *why the session ended*, because
/// this helper was not listening. A failed production run is not automatically a
/// bug in the binding, and this is how you tell.
public func watch(_ reactor: Reactor) -> [Subscription] {
    [
        reactor.onStatus { print("status: \($0.rawValue)") },
        reactor.onError { print("error: \($0)") },
        reactor.onMessage { print("message: \($0)") },
        reactor.onRuntimeMessage { print("runtime: \($0)") },
    ]
}

// MARK: - Seeing the frames

/// Counts frames, and writes a few of them out as PNGs.
///
/// **A frame count proves something arrived, not that it was the right
/// something.** So with `REACTOR_SHOW=1` this writes the first frame and then
/// one a second into a temporary directory and prints the paths. A PNG rather
/// than a window on purpose: it works over ssh and in CI, and it is a file
/// someone can look at afterwards.
public final class FrameCounter: @unchecked Sendable {

    private let label: String
    private let lock = NSLock()
    private var count = 0
    private var lastSnapshot = Date.distantPast
    private var firstSize: (width: UInt32, height: UInt32)?

    /// Where snapshots go, printed on the first write.
    public let directory: URL

    /// Count frames for one example, labelling its snapshot directory.
    public init(label: String) {
        self.label = label
        self.directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("reactor-\(label)", isDirectory: true)
    }

    /// How many frames have arrived.
    public var frames: Int {
        lock.lock()
        defer { lock.unlock() }
        return count
    }

    /// Take a frame. Called inline on the library's delivery thread, so this does
    /// as little as it can get away with.
    public func submit(_ frame: VideoFrame) {
        lock.lock()
        count += 1
        let index = count
        let isFirst = firstSize == nil
        if isFirst { firstSize = (frame.width, frame.height) }
        let due = Env.show && (isFirst || Date().timeIntervalSince(lastSnapshot) > 1)
        if due { lastSnapshot = Date() }
        lock.unlock()

        if isFirst {
            print("first frame: \(frame.width)x\(frame.height)")
        }
        if due {
            write(frame, index: index)
        }
    }

    /// Say what happened, in the numbers a reader can check.
    public func report() {
        // Both read under one lock. Every example calls this while its frame
        // subscription is still live, so reading `firstSize` outside the lock was
        // a real data race with `submit` on the library's delivery thread — and
        // taking the count through `frames` separately could pair a total from
        // one moment with a size from another.
        lock.lock()
        let total = count
        let first = firstSize
        lock.unlock()

        let size = first.map { "\($0.width)x\($0.height)" } ?? "no frames"
        print("frames: \(total) (\(size))")
        if !Env.show {
            print("REACTOR_SHOW=1 writes PNG snapshots, so you can see what arrived")
        }
    }

    private func write(_ frame: VideoFrame, index: Int) {
        let url = directory.appendingPathComponent(String(format: "frame-%04d.png", index))
        do {
            try FileManager.default.createDirectory(
                at: directory, withIntermediateDirectories: true)
            try writePNG(bgra: frame.pixels, width: frame.width, height: frame.height, to: url)
            print("snapshot: \(url.path)")
        } catch {
            print("snapshot failed: \(error)")
        }
    }
}

/// Write a BGRA frame as a PNG.
///
/// The pixel order is the library's: blue, green, red, alpha. Getting that
/// backwards produces a picture that looks plausible and is wrong, which is
/// precisely the thing a snapshot exists to catch.
func writePNG(bgra: Data, width: UInt32, height: UInt32, to url: URL) throws {
    let provider = CGDataProvider(data: bgra as CFData)
    guard let provider,
        let image = CGImage(
            width: Int(width),
            height: Int(height),
            bitsPerComponent: 8,
            bitsPerPixel: 32,
            bytesPerRow: Int(width) * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo(
                rawValue: CGImageAlphaInfo.premultipliedFirst.rawValue
                    | CGBitmapInfo.byteOrder32Little.rawValue),
            provider: provider,
            decode: nil,
            shouldInterpolate: false,
            intent: .defaultIntent)
    else {
        throw ExampleError("could not build an image from \(bgra.count) bytes")
    }

    guard
        let destination = CGImageDestinationCreateWithURL(
            url as CFURL, UTType.png.identifier as CFString, 1, nil)
    else {
        throw ExampleError("could not create \(url.path)")
    }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else {
        throw ExampleError("could not write \(url.path)")
    }
}

/// Something went wrong in the example rather than in the SDK.
public struct ExampleError: Error, CustomStringConvertible {

    /// What went wrong.
    public let description: String

    /// Fail with a message.
    public init(_ description: String) { self.description = description }
}

/// Wait, and say so.
public func hold(_ seconds: Double, _ what: String) async throws {
    print("watching \(what) for \(Int(seconds))s")
    try await Task.sleep(for: .seconds(seconds))
}

/// Write a small gradient PNG, so an example that needs a file has one.
public func makeGradientPNG(at url: URL, width: Int, height: Int) throws {
    var pixels = [UInt8](repeating: 0, count: width * height * 4)
    for y in 0..<height {
        for x in 0..<width {
            let offset = (y * width + x) * 4
            // BGRA, which is the order the library speaks everywhere.
            pixels[offset] = UInt8(255 * x / max(width - 1, 1))
            pixels[offset + 1] = UInt8(255 * y / max(height - 1, 1))
            pixels[offset + 2] = 128
            pixels[offset + 3] = 255
        }
    }
    try writePNG(bgra: Data(pixels), width: UInt32(width), height: UInt32(height), to: url)
}

/// Records what the per-frame trailer carried, for example 07.
///
/// Kept here rather than in the example only because it needs a lock; what it
/// reads is spelled out in the example itself.
public final class TrailerLog: @unchecked Sendable {

    private let lock = NSLock()
    private var count = 0
    private var withFrameID = 0
    private var withCaptureTime = 0
    private var withUserData = 0
    private var firstTag: String?

    /// Start with nothing recorded.
    public init() {}

    /// Take a frame and note what its trailer had.
    public func submit(_ frame: VideoFrame) {
        lock.lock()
        count += 1
        if frame.frameID != 0 { withFrameID += 1 }
        if frame.captureTimeUs != 0 { withCaptureTime += 1 }
        if let tag = frame.userData {
            withUserData += 1
            if firstTag == nil { firstTag = String(decoding: tag, as: UTF8.self) }
        }
        let isFirst = count == 1
        lock.unlock()

        if isFirst {
            print(
                "first frame trailer: frameID \(frame.frameID), captureTimeUs "
                    + "\(frame.captureTimeUs), userData "
                    + (frame.userData.map { "\($0.count) bytes" } ?? "none"))
        }
    }

    /// Say what the trailers carried across the whole run.
    public func report() {
        lock.lock()
        defer { lock.unlock() }
        print("frames: \(count)")
        print("  with a frame id:      \(withFrameID)")
        print("  with a capture time:  \(withCaptureTime)")
        print("  with a tag:           \(withUserData)\(firstTag.map { " (first: \($0))" } ?? "")")
        if count > 0, withFrameID == 0, withUserData == 0 {
            print(
                "all zeros, which is expected: no published model attaches a trailer today. "
                    + "Example 04 pushes tagged frames, which is the other side of this.")
        }
    }
}
