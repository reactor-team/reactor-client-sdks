package inc.reactor.sdk.android.media

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * YUV_420_888 to upright BGRA in one pass: what [CameraCapture] runs on every frame.
 *
 * The same result as [YuvToBgra.convert] followed by [CameraSelection.rotateBgra], and those two
 * stay as the readable reference this is tested against. They are not fast enough to run per
 * frame: one `ByteBuffer.get` or `put` per byte, float arithmetic per pixel and a second full pass
 * to rotate held a mid-range phone's camera thread at a full core and the stream at about 17fps
 * against the 30 the camera delivers.
 *
 * Here each plane is copied out in one bulk read, the colour maths is fixed-point integers, every
 * pixel is written straight to its rotated position, and the frame leaves in one bulk write. The
 * scratch arrays are kept between frames, so a steady stream allocates nothing; an instance is
 * therefore single-threaded — one per capture thread.
 */
internal class FrameConverter {
    private var yBytes = ByteArray(0)
    private var uBytes = ByteArray(0)
    private var vBytes = ByteArray(0)
    private var pixels = IntArray(0)

    /**
     * Convert one frame into [out], rotated clockwise by [degrees], and return the rotated size.
     *
     * The plane and stride parameters mean what they do for [YuvToBgra.convert]. [out] needs
     * `width * height * 4` bytes and is left rewound with its limit at the frame, ready to push.
     */
    @Suppress("LongParameterList")
    fun convert(
        y: ByteBuffer,
        u: ByteBuffer,
        v: ByteBuffer,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        degrees: Int,
        out: ByteBuffer,
    ): CameraSelection.Size {
        require(degrees == 0 || degrees == 90 || degrees == 180 || degrees == 270) {
            "degrees must be 0, 90, 180 or 270, got $degrees"
        }
        require(width > 0 && height > 0) { "width and height must be positive" }
        val count = width * height
        require(out.capacity() >= count * 4) {
            "out needs ${count * 4} bytes for ${width}x$height, has ${out.capacity()}"
        }

        yBytes = copyPlane(y, yBytes)
        uBytes = copyPlane(u, uBytes)
        vBytes = copyPlane(v, vBytes)
        if (pixels.size < count) pixels = IntArray(count)
        val ys = yBytes
        val us = uBytes
        val vs = vBytes
        val px = pixels

        val swap = CameraSelection.swapsDimensions(degrees)
        val outWidth = if (swap) height else width
        val outHeight = if (swap) width else height
        for (row in 0 until height) {
            val yRow = row * yRowStride
            val uvRow = (row / 2) * uvRowStride
            // The rotated index of this row's first pixel, and how far it moves per column: every
            // rotation is a linear walk, so no per-pixel branch on the angle.
            val start: Int
            val step: Int
            when (degrees) {
                90 -> {
                    start = height - 1 - row
                    step = outWidth
                }
                180 -> {
                    start = (height - 1 - row) * outWidth + (width - 1)
                    step = -1
                }
                270 -> {
                    start = (width - 1) * outWidth + row
                    step = -outWidth
                }
                else -> {
                    start = row * width
                    step = 1
                }
            }
            var target = start
            for (col in 0 until width) {
                val c = 298 * ((ys[yRow + col].toInt() and 0xFF) - 16)
                val uvIndex = uvRow + (col shr 1) * uvPixelStride
                val d = (us[uvIndex].toInt() and 0xFF) - 128
                val e = (vs[uvIndex].toInt() and 0xFF) - 128
                // BT.601 limited range, as YuvToBgra, in 8.8 fixed point.
                val r = clamp((c + 409 * e + 128) shr 8)
                val g = clamp((c - 100 * d - 208 * e + 128) shr 8)
                val b = clamp((c + 516 * d + 128) shr 8)
                // B, G, R, A in memory once written through a little-endian view.
                px[target] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                target += step
            }
        }

        val view = out.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        (view as java.nio.Buffer).clear()
        view.asIntBuffer().put(px, 0, count)
        // Through Buffer: the covariant ByteBuffer.clear() does not exist below API 33.
        (out as java.nio.Buffer).clear()
        (out as java.nio.Buffer).limit(count * 4)
        return CameraSelection.Size(outWidth, outHeight)
    }

    /** The whole plane, from position 0, into [scratch] — grown only when a frame needs more. */
    private fun copyPlane(
        plane: ByteBuffer,
        scratch: ByteArray,
    ): ByteArray {
        val source = plane.duplicate()
        (source as java.nio.Buffer).clear()
        val size = source.remaining()
        val target = if (scratch.size >= size) scratch else ByteArray(size)
        source.get(target, 0, size)
        return target
    }

    private fun clamp(value: Int): Int =
        when {
            value < 0 -> 0
            value > 255 -> 255
            else -> value
        }
}
