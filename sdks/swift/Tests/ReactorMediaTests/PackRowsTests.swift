import Testing

@testable import ReactorMedia

@Suite("Packing padded rows")
struct PackRowsTests {

    /// A frame whose rows are `stride` apart, with the padding filled with a
    /// byte that must never reach the output.
    private func paddedFrame(
        width: Int, height: Int, stride: Int, padding: UInt8 = 0xEE
    ) -> [UInt8] {
        var bytes = [UInt8](repeating: padding, count: stride * height)
        for row in 0..<height {
            for column in 0..<(width * 4) {
                // Distinct per pixel, so a row copied from the wrong offset shows
                // up as a wrong value rather than as a plausible one.
                bytes[row * stride + column] = UInt8((row * 7 + column * 3) % 251)
            }
        }
        return bytes
    }

    @Test("padded rows are packed contiguously, and the padding is left behind")
    func paddingIsDropped() {
        let width = 5, height = 4, rowBytes = width * 4
        let stride = rowBytes + 12  // the alignment a rotated buffer arrives with
        let source = paddedFrame(width: width, height: height, stride: stride)

        var destination = [UInt8](repeating: 0, count: rowBytes * height)
        source.withUnsafeBytes { input in
            destination.withUnsafeMutableBytes { output in
                packRows(
                    from: input.baseAddress!, stride: stride, rowBytes: rowBytes,
                    height: height, into: output.baseAddress!)
            }
        }

        for row in 0..<height {
            for column in 0..<rowBytes {
                #expect(
                    destination[row * rowBytes + column]
                        == source[row * stride + column],
                    "row \(row), byte \(column)")
            }
        }
        #expect(!destination.contains(0xEE), "padding reached the packed frame")
    }

    @Test("an unpadded frame is copied unchanged")
    func unpaddedIsIdentity() {
        // The case where stride already equals rowBytes. The capture path skips
        // packing entirely there, but the copy must still be exact for the day
        // something else uses it.
        let width = 3, height = 3, rowBytes = width * 4
        let source = paddedFrame(width: width, height: height, stride: rowBytes)

        var destination = [UInt8](repeating: 0, count: rowBytes * height)
        source.withUnsafeBytes { input in
            destination.withUnsafeMutableBytes { output in
                packRows(
                    from: input.baseAddress!, stride: rowBytes, rowBytes: rowBytes,
                    height: height, into: output.baseAddress!)
            }
        }

        #expect(destination == source)
    }
}
