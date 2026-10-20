import Foundation

@testable import Reactor

/// A fake `ReactorStepResult`: what `FakeLibrary.completeLastStep` hands the
/// SDK, read back through the fake's `reactor_step_result_*` accessors.
///
/// Every string and buffer lives in C memory this object owns, so a pointer an
/// accessor returns stays put until the result is freed — the lifetime the
/// header promises — and an SDK reading past the free reads freed memory rather
/// than a Swift array that happened to survive.
///
/// Handed over `passRetained`, and released by the fake's
/// `reactor_step_result_free`: hold one weakly to see whether the SDK freed it.
final class FakeStepResult: @unchecked Sendable {

    struct Frame {
        var encoding = "raw"
        var dtype: String
        var shape: [UInt32]
        var data: [UInt8]
        var metadata: [UInt8]?
        var captureTimeUs: Int64?
    }

    struct Track {
        var name: String
        var kind: String
        var rate: Double = 0
        var frames: [Frame]
    }

    /// One frame, in C memory.
    final class CFrame {
        let encoding: UnsafeMutablePointer<CChar>
        let dtype: UnsafeMutablePointer<CChar>
        let shape: UnsafeMutablePointer<UInt32>
        let ndim: Int
        let data: UnsafeMutablePointer<UInt8>
        let dataCount: Int
        /// Null when the sender attached nothing; non-null with a zero count for
        /// an empty tag, as the header distinguishes them.
        let metadata: UnsafeMutablePointer<UInt8>?
        let metadataCount: Int
        let captureTimeUs: Int64?

        init(_ frame: Frame) {
            encoding = strdup(frame.encoding)
            dtype = strdup(frame.dtype)
            ndim = frame.shape.count
            shape = .allocate(capacity: max(ndim, 1))
            shape.initialize(from: frame.shape, count: ndim)
            dataCount = frame.data.count
            data = .allocate(capacity: max(dataCount, 1))
            data.initialize(from: frame.data, count: dataCount)
            if let tag = frame.metadata {
                let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: max(tag.count, 1))
                buffer.initialize(from: tag, count: tag.count)
                metadata = buffer
                metadataCount = tag.count
            } else {
                metadata = nil
                metadataCount = 0
            }
            captureTimeUs = frame.captureTimeUs
        }

        deinit {
            free(encoding)
            free(dtype)
            shape.deallocate()
            data.deallocate()
            metadata?.deallocate()
        }
    }

    /// One track, in C memory.
    final class CTrack {
        let name: UnsafeMutablePointer<CChar>
        let kind: UnsafeMutablePointer<CChar>
        let rate: Double
        let frames: [CFrame]

        init(_ track: Track) {
            name = strdup(track.name)
            kind = strdup(track.kind)
            rate = track.rate
            frames = track.frames.map(CFrame.init)
        }

        deinit {
            free(name)
            free(kind)
        }
    }

    struct File {
        var name: String
        var contentType: String
        var size: UInt64
    }

    /// One file entry, in C memory.
    final class CFile {
        let name: UnsafeMutablePointer<CChar>
        let contentType: UnsafeMutablePointer<CChar>
        let size: UInt64

        init(_ file: File) {
            name = strdup(file.name)
            contentType = strdup(file.contentType)
            size = file.size
        }

        deinit {
            free(name)
            free(contentType)
        }
    }

    let step: UInt64
    let tracks: [CTrack]
    let elapsed: Double
    let files: [CFile]
    /// Null when the step worked, as the header has it.
    let error: UnsafeMutablePointer<CChar>?

    init(
        step: UInt64, tracks: [Track], elapsed: Double = 0, files: [File] = [],
        error: String? = nil
    ) {
        self.step = step
        self.tracks = tracks.map(CTrack.init)
        self.elapsed = elapsed
        self.files = files.map(CFile.init)
        self.error = error.map { strdup($0) }
    }

    deinit {
        free(error)
    }

    func file(_ index: UInt32) -> CFile? {
        Int(index) < files.count ? files[Int(index)] : nil
    }

    func track(_ index: UInt32) -> CTrack? {
        Int(index) < tracks.count ? tracks[Int(index)] : nil
    }

    func frame(_ track: UInt32, _ frame: UInt32) -> CFrame? {
        guard let frames = self.track(track)?.frames, Int(frame) < frames.count else { return nil }
        return frames[Int(frame)]
    }

    /// The result behind a pointer the fake handed out, without consuming it.
    static func from(_ pointer: OpaquePointer?) -> FakeStepResult? {
        guard let pointer else { return nil }
        return Unmanaged<FakeStepResult>.fromOpaque(UnsafeRawPointer(pointer))
            .takeUnretainedValue()
    }
}
