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

    // ── Streaming resample ───────────────────────────────────────────────────

    /**
     * The defect a stateless resampler has and this one does not.
     *
     * Called once per capture buffer, `PcmConversion.resample` restarts at source index 0 every
     * time, so the fractional part of the position is discarded at every boundary. At 44.1k to
     * 48k that is a repeated or dropped sample every few milliseconds, for as long as the capture
     * lasts — each buffer individually plausible, the stream drifting.
     *
     * Measured as output length against what the ratio demands over a second of audio, because
     * that is where the lost fractions accumulate into whole samples.
     */
    @Test
    fun `a streaming resample keeps its phase across buffers`() {
        val from = 44_100
        val to = 48_000
        // 1024, an ordinary AudioRecord buffer, and deliberately *not* a whole number of
        // milliseconds. 441 samples is exactly 10ms and resamples to exactly 480 — it divides
        // evenly, hides the defect entirely, and was this test's first buffer size.
        val bufferSize = 1024
        val buffers = 100
        val buffer = ShortArray(bufferSize) { it.toShort() }

        val streaming = PcmConversion.Resampler(from, to)
        var streamed = 0
        var perBuffer = 0
        repeat(buffers) {
            streamed += streaming.resample(buffer).size
            perBuffer += PcmConversion.resample(buffer, from, to).size
        }

        val input = bufferSize.toLong() * buffers
        val expected = ((input * to + from - 1) / from).toInt()
        assertEquals("the stream must hold the ratio across boundaries", expected, streamed)
        assertTrue(
            "the per-buffer resample must be the one that drifts, or this test proves nothing",
            perBuffer != expected,
        )
        assertTrue(
            "the drift must be whole samples, not a rounding tie: lost $expected - $perBuffer",
            expected - perBuffer > 10,
        )
    }

    @Test
    fun `a streaming resample is a no-op when the rates match`() {
        val resampler = PcmConversion.Resampler(48_000, 48_000)
        val samples = shortArrayOf(1, 2, 3, 4)
        assertTrue(samples.contentEquals(resampler.resample(samples)))
    }

    /** Downward too: 48k to 16k is exactly one sample in three, buffer boundaries notwithstanding. */
    @Test
    fun `a streaming resample holds its ratio downward`() {
        val resampler = PcmConversion.Resampler(48_000, 16_000)
        var total = 0
        repeat(50) { total += resampler.resample(ShortArray(480)).size }
        assertEquals(50 * 160, total)
    }

    @Test
    fun `a streaming resample refuses a non-positive rate`() {
        assertThrows(IllegalArgumentException::class.java) { PcmConversion.Resampler(0, 48_000) }
        assertThrows(IllegalArgumentException::class.java) { PcmConversion.Resampler(48_000, -1) }
    }

    // ── The bridge to pushFrame ──────────────────────────────────────────────

    @Test
    fun `samples become little-endian s16 ready to push`() {
        val buffer = PcmConversion.directBufferFor(frames = 4)
        val out = PcmConversion.toDirectBuffer(shortArrayOf(1, -2, 256, Short.MIN_VALUE), buffer)

        assertEquals("the buffer must be positioned for a read", 0, out.position())
        assertEquals("and limited to what was written", 8, out.limit())
        assertEquals(1.toShort(), out.getShort(0))
        assertEquals((-2).toShort(), out.getShort(2))
        assertEquals(256.toShort(), out.getShort(4))
        assertEquals(Short.MIN_VALUE, out.getShort(6))
    }

    /**
     * Little-endian explicitly, not nativeOrder(). Every Android device is little-endian today,
     * which is a fact about the present rather than a contract — and a buffer written in the wrong
     * order is white noise, not an error.
     */
    @Test
    fun `the byte order is the wire's, whatever the buffer arrived as`() {
        val buffer =
            java.nio.ByteBuffer
                .allocateDirect(4)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
        val out = PcmConversion.toDirectBuffer(shortArrayOf(0x0102, 0x0304), buffer)
        assertEquals(0x02.toByte(), out.get(0))
        assertEquals(0x01.toByte(), out.get(1))
    }

    @Test
    fun `a heap buffer is refused, naming the fix`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                PcmConversion.toDirectBuffer(shortArrayOf(1), java.nio.ByteBuffer.allocate(2))
            }
        assertTrue(error.message!!.contains("directBufferFor"))
    }

    @Test
    fun `a buffer too small for the samples is refused with both numbers`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                PcmConversion.toDirectBuffer(ShortArray(10), PcmConversion.directBufferFor(4))
            }
        assertTrue(error.message!!.contains("8"))
        assertTrue(error.message!!.contains("20"))
    }

    @Test
    fun `the buffer is reusable across frames`() {
        val buffer = PcmConversion.directBufferFor(frames = 480)
        repeat(3) { round ->
            val out = PcmConversion.toDirectBuffer(ShortArray(480) { round.toShort() }, buffer)
            assertEquals(960, out.limit())
            assertEquals(round.toShort(), out.getShort(0))
        }
    }

    @Test
    fun `a non-positive buffer request is a caller error`() {
        assertThrows(IllegalArgumentException::class.java) { PcmConversion.directBufferFor(0) }
        assertThrows(IllegalArgumentException::class.java) { PcmConversion.directBufferFor(10, 0) }
    }
}
