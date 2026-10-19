package inc.reactor.sdk.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * The one-pass converter against the two-pass reference it replaces on the camera thread.
 *
 * The reference rounds through floats and this through fixed point, so a channel may differ by a
 * unit or two; anything more is a wrong pixel, and a wrong *position* — the rotation — shows up as
 * a large difference on random input.
 */
class FrameConverterTest {
    private class Planes(
        val y: ByteBuffer,
        val u: ByteBuffer,
        val v: ByteBuffer,
        val yRowStride: Int,
        val uvRowStride: Int,
        val uvPixelStride: Int,
    )

    /** Random planes with padded rows, in the layout a camera hands over for [uvPixelStride]. */
    private fun planes(
        width: Int,
        height: Int,
        uvPixelStride: Int,
        random: Random,
    ): Planes {
        val yRowStride = width + 16
        val chromaRows = (height + 1) / 2
        val chromaCols = (width + 1) / 2
        val y = ByteBuffer.wrap(random.nextBytes(yRowStride * height))
        return if (uvPixelStride == 1) {
            val uvRowStride = chromaCols + 8
            Planes(
                y,
                ByteBuffer.wrap(random.nextBytes(uvRowStride * chromaRows)),
                ByteBuffer.wrap(random.nextBytes(uvRowStride * chromaRows)),
                yRowStride,
                uvRowStride,
                1,
            )
        } else {
            // Semi-planar: one interleaved buffer, V one byte after U, the way NV12 cameras do.
            val uvRowStride = width + 16
            val interleaved = random.nextBytes(uvRowStride * chromaRows)
            val u = ByteBuffer.wrap(interleaved, 0, interleaved.size - 1).slice()
            val v = ByteBuffer.wrap(interleaved, 1, interleaved.size - 1).slice()
            Planes(y, u, v, yRowStride, uvRowStride, 2)
        }
    }

    private fun direct(size: Int) = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())

    @Test
    fun `one pass matches convert-then-rotate for every rotation and chroma layout`() {
        val random = Random(42)
        for (uvPixelStride in listOf(1, 2)) {
            for (degrees in listOf(0, 90, 180, 270)) {
                val width = 64
                val height = 48
                val p = planes(width, height, uvPixelStride, random)

                val converted = direct(width * height * 4)
                YuvToBgra.convert(p.y, p.u, p.v, width, height, p.yRowStride, p.uvRowStride, p.uvPixelStride, converted)
                val expected = direct(width * height * 4)
                val expectedSize = CameraSelection.rotateBgra(converted, width, height, degrees, expected)

                val actual = direct(width * height * 4)
                val actualSize =
                    FrameConverter().convert(
                        p.y,
                        p.u,
                        p.v,
                        width,
                        height,
                        p.yRowStride,
                        p.uvRowStride,
                        p.uvPixelStride,
                        degrees,
                        actual,
                    )

                assertEquals("size at $degrees°, pixel stride $uvPixelStride", expectedSize, actualSize)
                assertEquals(0, actual.position())
                assertEquals(width * height * 4, actual.limit())
                var worst = 0
                for (i in 0 until width * height * 4) {
                    val diff = Math.abs((expected.get(i).toInt() and 0xFF) - (actual.get(i).toInt() and 0xFF))
                    if (diff > worst) worst = diff
                }
                assertTrue("worst channel difference $worst at $degrees°, pixel stride $uvPixelStride", worst <= 2)
            }
        }
    }

    /** The scratch arrays are kept: a second, smaller frame must not read the first one's tail. */
    @Test
    fun `a converter reused across frame sizes stays correct`() {
        val random = Random(7)
        val converter = FrameConverter()
        for ((width, height) in listOf(64 to 48, 32 to 24, 64 to 48)) {
            val p = planes(width, height, 2, random)
            val converted = direct(width * height * 4)
            YuvToBgra.convert(p.y, p.u, p.v, width, height, p.yRowStride, p.uvRowStride, p.uvPixelStride, converted)
            val expected = direct(width * height * 4)
            CameraSelection.rotateBgra(converted, width, height, 90, expected)
            val actual = direct(width * height * 4)
            converter.convert(p.y, p.u, p.v, width, height, p.yRowStride, p.uvRowStride, p.uvPixelStride, 90, actual)
            for (i in 0 until width * height * 4) {
                val diff = Math.abs((expected.get(i).toInt() and 0xFF) - (actual.get(i).toInt() and 0xFF))
                assertTrue("byte $i differs by $diff at ${width}x$height", diff <= 2)
            }
        }
    }

    @Test
    fun `alpha is opaque`() {
        val p = planes(8, 8, 1, Random(1))
        val out = direct(8 * 8 * 4)
        FrameConverter().convert(p.y, p.u, p.v, 8, 8, p.yRowStride, p.uvRowStride, 1, 0, out)
        for (i in 0 until 64) assertEquals(0xFF.toByte(), out.get(i * 4 + 3))
    }
}
