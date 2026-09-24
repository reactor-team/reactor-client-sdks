package inc.reactor.sdk.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The pixel and PCM conversions, which is where camera frames actually go wrong. */
class ConversionTest {
    private fun direct(size: Int) = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())

    @Test
    fun `a neutral frame converts to grey, opaque`() {
        val y = direct(16).apply { repeat(capacity()) { put(it, 128.toByte()) } }
        val chroma = direct(16).apply { repeat(capacity()) { put(it, 128.toByte()) } }
        val out = direct(4 * 4 * 4)
        YuvToBgra.convert(y, chroma, chroma, 4, 4, 4, 4, 1, out)
        for (pixel in 0 until 16) {
            val b = out.get(pixel * 4).toInt() and 0xFF
            assertTrue("expected mid grey, got $b", b in 120..140)
            assertEquals(255, out.get(pixel * 4 + 3).toInt() and 0xFF)
        }
    }

    /**
     * Row stride is not width. A device that pads rows produces a sheared image if the stride is
     * ignored — and only on the devices that pad, which is rarely the one on the desk.
     */
    @Test
    fun `a padded row stride does not shear the image`() {
        val width = 4
        val height = 4
        val padded = 8
        val y = direct(padded * height)
        for (row in 0 until height) {
            for (col in 0 until padded) {
                y.put(row * padded + col, if (col < width) (16 + row * 40).toByte() else 0)
            }
        }
        val chroma = direct(padded * height).apply { repeat(capacity()) { put(it, 128.toByte()) } }
        val out = direct(width * height * 4)
        YuvToBgra.convert(y, chroma, chroma, width, height, padded, padded, 1, out)

        val rowValues = (0 until height).map { row -> out.get(row * width * 4).toInt() and 0xFF }
        assertEquals("rows bled into the padding", height, rowValues.distinct().size)
        for (row in 0 until height) {
            val first = out.get(row * width * 4).toInt() and 0xFF
            for (col in 1 until width) {
                assertEquals(first, out.get((row * width + col) * 4).toInt() and 0xFF)
            }
        }
    }

    /**
     * uvPixelStride is 2 on semi-planar devices, where U and V interleave. Reading it as 1 takes
     * every other byte from the wrong plane — the classic green-and-magenta image.
     */
    @Test
    fun `an interleaved chroma plane is read with its pixel stride`() {
        val width = 4
        val height = 4
        val y = direct(width * height).apply { repeat(capacity()) { put(it, 128.toByte()) } }
        val uv =
            direct(width * height).apply {
                repeat(capacity()) { put(it, if (it % 2 == 0) 128.toByte() else 0) }
            }
        val out = direct(width * height * 4)
        YuvToBgra.convert(y, uv, uv, width, height, width, width, 2, out)
        for (pixel in 0 until width * height) {
            val b = out.get(pixel * 4).toInt() and 0xFF
            assertTrue("chroma pixel stride ignored; got $b", b in 120..140)
        }
    }

    @Test
    fun `an undersized output buffer is refused, naming both sizes`() {
        val y = direct(16).apply { repeat(capacity()) { put(it, 128.toByte()) } }
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                YuvToBgra.convert(y, y, y, 4, 4, 4, 4, 1, direct(16))
            }
        assertTrue(error.message!!.contains("64"))
        assertTrue(error.message!!.contains("16"))
    }

    // ── PCM ──────────────────────────────────────────────────────────────────

    @Test
    fun `stereo mixes to mono by averaging both channels`() {
        assertTrue(
            PcmConversion
                .toMono(shortArrayOf(100, 200, 300, 400), 2)
                .contentEquals(shortArrayOf(150, 350)),
        )
    }

    @Test
    fun `mono passes through untouched`() {
        val mono = shortArrayOf(1, 2, 3)
        assertTrue(PcmConversion.toMono(mono, 1) === mono)
    }

    @Test
    fun `resampling to the same rate is a no-op, not a copy`() {
        val samples = shortArrayOf(1, 2, 3)
        assertTrue(PcmConversion.resample(samples, 48000, 48000) === samples)
    }

    @Test
    fun `halving the rate halves the sample count`() {
        assertEquals(50, PcmConversion.resample(ShortArray(100) { it.toShort() }, 48000, 24000).size)
    }

    @Test
    fun `doubling the rate doubles it`() {
        assertEquals(100, PcmConversion.resample(ShortArray(50) { it.toShort() }, 24000, 48000).size)
    }

    /** Nearest-neighbour rounding is exactly where an off-by-one lands. */
    @Test
    fun `resampling never reads past the end`() {
        val samples = ShortArray(7) { it.toShort() }
        PcmConversion.resample(samples, 44100, 48000)
        PcmConversion.resample(samples, 48000, 44100)
    }

    @Test
    fun `a non-positive rate or channel count is a caller error`() {
        assertThrows(IllegalArgumentException::class.java) {
            PcmConversion.resample(shortArrayOf(1), 0, 48000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PcmConversion.toMono(shortArrayOf(1), 0)
        }
    }
}
