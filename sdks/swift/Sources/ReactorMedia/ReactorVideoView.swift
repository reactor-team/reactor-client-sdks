import CoreGraphics
import Foundation
import Reactor
import SwiftUI
import os

#if canImport(UIKit)
    import UIKit
#elseif canImport(AppKit)
    import AppKit
#endif

/// Shows what a recvonly video track is delivering.
///
/// ```swift
/// ReactorVideoView(track: try reactor.track("main_video"))
///     .aspectRatio(16 / 9, contentMode: .fit)
/// ```
///
/// ## What happens to a frame on its way to the screen
///
/// Frames arrive **inline on the library's delivery thread**, and that thread is
/// not one to draw from. So each frame becomes a `CGImage` there and is handed to
/// the main actor to be shown; if the main actor is behind, the newest image
/// replaces the one waiting. That is deliberately the same bargain the FFI makes
/// with a slow handler — newest frame wins, because a stale frame costs latency
/// and buys nothing.
///
/// Converting on the delivery thread rather than on the main one is the point:
/// the conversion is the expensive part, and doing it there means blocking *that*
/// thread, which is what backpressure is supposed to feel like.
public struct ReactorVideoView: View {

    @StateObject private var presenter: FramePresenter
    private let track: Track

    /// Show `track`.
    ///
    /// - Throws: nothing. A track this client sends on shows the placeholder
    ///   rather than refusing to build a view — a SwiftUI initialiser is not a
    ///   place to fail, and ``Reactor/Track/onFrame(_:)`` has already said so in a
    ///   log line.
    public init(track: Track) {
        self.track = track
        self._presenter = StateObject(wrappedValue: FramePresenter(track: track))
    }

    /// The frame, or a placeholder before the first one arrives.
    public var body: some View {
        ZStack {
            if let image = presenter.image {
                Image(decorative: image, scale: 1, orientation: .up)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
            } else {
                Color.black
                    .overlay(
                        Text("waiting for \(track.name)")
                            .font(.caption)
                            .foregroundStyle(.secondary))
            }
        }
        .onAppear { presenter.start() }
        .onDisappear { presenter.stop() }
    }
}

/// Turns frames into images, and keeps only the newest one.
@MainActor
final class FramePresenter: ObservableObject {

    /// The most recent frame, as an image.
    @Published private(set) var image: CGImage?

    private let track: Track
    private var subscription: Subscription?
    private let pending = PendingImage()
    private let log = Logger(subsystem: "inc.reactor.sdk", category: "video-view")

    init(track: Track) {
        self.track = track
    }

    /// Begin receiving.
    func start() {
        guard subscription == nil else { return }
        let pending = self.pending
        do {
            subscription = try track.onFrame { [weak self] frame in
                // On the library's thread: convert here, then hop.
                guard let image = frame.cgImage else { return }
                // At most one hop is ever in flight, and it reads whatever the
                // newest image is when it runs. A Task per frame was not the
                // documented "newest replaces the one waiting": with a busy main
                // actor they piled up and rendered in order, so latency and
                // memory grew with the backlog instead of frames being dropped.
                guard pending.offer(image) else { return }
                Task { @MainActor [weak self] in
                    guard let newest = pending.take() else { return }
                    self?.image = newest
                }
            }
        } catch {
            // A sendonly or audio track. The view shows its placeholder; the
            // error already says which method to use instead.
            let name = track.name
            log.error("not showing '\(name, privacy: .public)': \(error, privacy: .public)")
        }
    }

    /// Stop receiving.
    func stop() {
        subscription?.cancel()
        subscription = nil
    }
}

/// The newest frame waiting to be shown, and whether a hop to the main actor is
/// already on its way to show it.
///
/// `@unchecked Sendable` for the `CGImage`: it is immutable once made, and the
/// lock is what orders the two threads that touch this.
private final class PendingImage: @unchecked Sendable {

    private let lock = NSLock()
    private var image: CGImage?
    private var scheduled = false

    /// Store the newest image, replacing any that has not been shown yet.
    ///
    /// Answers `true` only to the caller that should schedule the hop; everyone
    /// else has just handed their frame to a hop that is already coming.
    func offer(_ newest: CGImage) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        image = newest
        guard !scheduled else { return false }
        scheduled = true
        return true
    }

    /// Take the newest image, and let the next frame schedule a hop again.
    func take() -> CGImage? {
        lock.lock()
        defer { lock.unlock() }
        scheduled = false
        defer { image = nil }
        return image
    }
}

extension VideoFrame {

    /// This frame as a `CGImage`, or `nil` if the bytes do not describe one.
    ///
    /// BGRA in, which is why the bitmap info says little-endian with
    /// premultiplied-first alpha: those two together *are* B, G, R, A in memory.
    /// Swap them and the picture looks plausible and is wrong, which is the whole
    /// reason this is written once here rather than in each caller.
    public var cgImage: CGImage? {
        guard let provider = CGDataProvider(data: pixels as CFData) else { return nil }
        return CGImage(
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
    }
}
