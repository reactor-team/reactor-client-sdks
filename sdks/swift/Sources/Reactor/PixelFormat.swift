import Foundation

/// How a pushed video frame's pixels are laid out.
public enum PixelFormat: Sendable, Hashable {
    /// Four bytes a pixel: B, G, R, A. What the native library takes, as it is.
    case bgra
    /// Three bytes a pixel: R, G, B, as decoders and image libraries tend to hand
    /// frames over. Converted to BGRA, opaque, before the push.
    case rgb

    /// How many bytes one pixel takes in this layout.
    public var bytesPerPixel: Int {
        switch self {
        case .bgra: 4
        case .rgb: 3
        }
    }
}

extension Reactor {
    /// `pixels` as the BGRA the library takes: as they are for ``PixelFormat/bgra``,
    /// converted, the alpha added opaque, for ``PixelFormat/rgb``, as the Python
    /// SDK does with an RGB array. An RGB buffer must hold exactly the frame its
    /// size describes; a BGRA one is checked where it is pushed.
    static func bgra(
        _ pixels: Data, format: PixelFormat, width: UInt32, height: UInt32, track name: String
    ) throws -> Data {
        guard format == .rgb else { return pixels }
        let pixelCount = Int(width).multipliedReportingOverflow(by: Int(height))
        let byteCount =
            pixelCount.overflow
            ? (partialValue: 0, overflow: true)
            : pixelCount.partialValue.multipliedReportingOverflow(by: 3)
        guard !byteCount.overflow, pixels.count == byteCount.partialValue else {
            throw ReactorError(
                .badRequest,
                "pushFrame on '\(name)' was given \(pixels.count) bytes for a "
                    + "\(width)x\(height) RGB frame, which needs exactly "
                    + "\(byteCount.overflow ? "more than an Int can count" : "\(byteCount.partialValue)").",
                operation: "pushFrame")
        }
        var bgra = Data(count: pixelCount.partialValue * 4)
        pixels.withUnsafeBytes { rgb in
            bgra.withUnsafeMutableBytes { out in
                for i in 0..<pixelCount.partialValue {
                    out[i * 4] = rgb[i * 3 + 2]
                    out[i * 4 + 1] = rgb[i * 3 + 1]
                    out[i * 4 + 2] = rgb[i * 3]
                    out[i * 4 + 3] = 0xFF
                }
            }
        }
        return bgra
    }
}
