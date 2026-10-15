package inc.reactor.sdk.android.media

import java.nio.ByteBuffer

/**
 * YUV_420_888 to BGRA, which is what the wire wants.
 *
 * Camera frames do not arrive as pixels you can push. They arrive as three planes with their own
 * row strides and — for the chroma planes — a *pixel* stride that is 1 on some devices and 2 on
 * others, because the hardware interleaves U and V. Ignoring either stride produces an image that
 * is sheared, or green, or both, on exactly the subset of devices the developer does not own.
 *
 * Written as plain arithmetic over [ByteBuffer]s rather than against `android.media.Image`, so
 * every one of those cases is testable on a JVM with no device attached. The camera wiring hands
 * it planes; this file never imports an Android class.
 */
public object YuvToBgra {
    /**
     * Convert one frame.
     *
     * @param yRowStride bytes per row in [y] — usually but not always [width].
     * @param uvPixelStride 1 for planar U/V, 2 for the semi-planar (NV21/NV12) layouts where the
     *   two chroma planes are interleaved and every other byte belongs to the other plane.
     * @param out a direct buffer of exactly `width * height * 4` bytes.
     */
    @Suppress("LongParameterList")
    public fun convert(
        y: ByteBuffer,
        u: ByteBuffer,
        v: ByteBuffer,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        out: ByteBuffer,
    ) {
        require(width > 0 && height > 0) { "width and height must be positive" }
        require(out.remaining() >= width * height * 4) {
            "out needs ${width * height * 4} bytes for ${width}x$height, has ${out.remaining()}"
        }

        val outBase = out.position()
        for (row in 0 until height) {
            val yRow = row * yRowStride
            // Chroma is half resolution in both directions: two luma rows share one chroma row.
            val uvRow = (row / 2) * uvRowStride
            for (col in 0 until width) {
                val yValue = (y.get(yRow + col).toInt() and 0xFF) - 16
                val uvIndex = uvRow + (col / 2) * uvPixelStride
                val uValue = (u.get(uvIndex).toInt() and 0xFF) - 128
                val vValue = (v.get(uvIndex).toInt() and 0xFF) - 128

                // BT.601, the range the camera pipeline produces.
                val c = 1.164f * yValue
                val r = (c + 1.596f * vValue).toInt()
                val g = (c - 0.392f * uValue - 0.813f * vValue).toInt()
                val b = (c + 2.017f * uValue).toInt()

                val at = outBase + (row * width + col) * 4
                out.put(at, clamp(b))
                out.put(at + 1, clamp(g))
                out.put(at + 2, clamp(r))
                out.put(at + 3, 0xFF.toByte())
            }
        }
    }

    private fun clamp(value: Int): Byte =
        when {
            value < 0 -> 0
            value > 255 -> 255.toByte()
            else -> value.toByte()
        }
}
