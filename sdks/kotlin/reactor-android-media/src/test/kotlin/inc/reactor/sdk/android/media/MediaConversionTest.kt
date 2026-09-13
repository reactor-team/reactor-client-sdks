package inc.reactor.sdk.android.media

import inc.reactor.sdk.AudioFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class MediaConversionTest {
    @Test fun resamplingIsContinuousAcrossBlocksAndMixesChannels() {
        val samples = ShortArray(960) { (it * 20 - 9600).toShort() }
        val whole = PcmConverter(16000, 2).convert(AudioFrame(samples, 48000, 1)).samples
        val converter = PcmConverter(16000, 2)
        val pieces =
            converter.convert(AudioFrame(samples.copyOfRange(0, 480), 48000, 1)).samples +
                converter.convert(AudioFrame(samples.copyOfRange(480, 960), 48000, 1)).samples
        assertArrayEquals(whole, pieces)
        assertEquals(640, whole.size)
        assertEquals(whole[0], whole[1])
        val mono = PcmConverter(48000, 1).convert(AudioFrame(shortArrayOf(-32768, 32767, -1000, 1000), 48000, 2))
        assertArrayEquals(shortArrayOf(0, 0), mono.samples)
    }

    @Test fun upsamplingInterpolatesAndRejectsFormatChangesOrUnboundedBlocks() {
        val converter = PcmConverter(16000, 1)
        assertArrayEquals(shortArrayOf(0, 500, 1000), converter.convert(AudioFrame(shortArrayOf(0, 1000), 8000, 1)).samples)
        assertArrayEquals(shortArrayOf(1500, 2000), converter.convert(AudioFrame(shortArrayOf(2000), 8000, 1)).samples)
        assertTrue(runCatching { converter.convert(AudioFrame(shortArrayOf(1), 16000, 1)) }.isFailure)
        assertTrue(runCatching { PcmConverter(48000, 1).convert(AudioFrame(ShortArray(48001), 48000, 1)) }.isFailure)
    }

    @Test fun rgbaHonorsBufferPositionRowStrideCropRotationAndMirror() {
        val buffer = ByteBuffer.allocate(40)
        buffer.position(3)
        // Two rows, with padding after each two RGBA pixels.
        for ((offset, red) in listOf(3 to 1, 11 to 2, 23 to 3, 31 to 4)) {
            buffer.put(offset, red.toByte())
            buffer.put(offset + 3, (-1).toByte())
        }
        val planes = listOf(PixelPlane(buffer, 20, 8))
        val rotated = convertImage(1, 2, 2, PixelCrop(0, 0, 2, 2), planes, 90, false, YuvColorSpace.BT601_LIMITED)
        assertEquals(listOf<Byte>(3, 1, 4, 2), rotated.pixels.toList().filterIndexed { i, _ -> i % 4 == 2 })
        val mirrored = convertImage(1, 2, 2, PixelCrop(1, 0, 2, 2), planes, 90, true, YuvColorSpace.BT601_LIMITED)
        assertEquals(2, mirrored.width)
        assertEquals(1, mirrored.height)
        assertEquals(listOf<Byte>(2, 4), mirrored.pixels.toList().filterIndexed { i, _ -> i % 4 == 2 })
        assertEquals(3, buffer.position())
    }

    @Test fun yuvHandlesPaddedInterleavedChromaAndRejectsTruncatedPlanes() {
        val y = ByteBuffer.wrap(byteArrayOf(16, 235.toByte(), 0, 81, 145.toByte()))
        val uv = ByteBuffer.wrap(byteArrayOf(128.toByte(), 128.toByte()))
        val planes = listOf(PixelPlane(y, 3, 1), PixelPlane(uv, 2, 2), PixelPlane(uv.duplicate().apply { position(1) }, 2, 2))
        val frame = convertImage(35, 2, 2, PixelCrop(0, 0, 2, 2), planes, 0, false, YuvColorSpace.BT601_LIMITED)
        assertArrayEquals(byteArrayOf(0, 0, 0, -1, -1, -1, -1, -1), frame.pixels.copyOfRange(0, 8))
        assertTrue(runCatching { convertImage(35, 3, 2, PixelCrop(0, 0, 3, 2), planes, 0, false, YuvColorSpace.BT601_LIMITED) }.isFailure)
    }

    @Test fun timestampMappingUsesCameraClockOnlyWhenDeclaredRealtime() {
        assertEquals(4990L, cameraTime(10000, CameraTimestampSource.REALTIME, 20000, 5000))
        assertEquals(5000L, cameraTime(Long.MAX_VALUE, CameraTimestampSource.UNKNOWN, 20000, 5000))
        assertEquals(0L, cameraTime(0, CameraTimestampSource.REALTIME, 20000, 1))
        assertTrue(runCatching { cameraTime(30000, CameraTimestampSource.REALTIME, 20000, 5000) }.isFailure)
    }
}
