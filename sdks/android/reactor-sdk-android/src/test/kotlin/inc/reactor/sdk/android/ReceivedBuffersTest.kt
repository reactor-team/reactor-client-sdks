package inc.reactor.sdk.android

import inc.reactor.sdk.android.internal.ReceivedBuffers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ReadOnlyBufferException

/** A handler gets read-only views of the native layer's frame memory, reading what was there. */
class ReceivedBuffersTest {
    @Test
    fun `received pixels are read-only and read the native bytes`() {
        val native = ByteBuffer.allocateDirect(8).apply { put(0, 0x11) }
        val pixels = ReceivedBuffers.pixels(native)
        assertTrue(pixels.isReadOnly)
        assertTrue("still direct, so renderers can read it in place", pixels.isDirect)
        assertEquals(0x11.toByte(), pixels.get(0))
        assertThrows(ReadOnlyBufferException::class.java) { pixels.put(0, 0) }
    }

    @Test
    fun `received samples are read-only and keep the platform byte order`() {
        val native = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        native.putShort(0, 1234)
        native.putShort(2, -5)
        val samples = ReceivedBuffers.samples(native.order(ByteOrder.BIG_ENDIAN))
        assertTrue(samples.isReadOnly)
        assertEquals(ByteOrder.nativeOrder(), samples.order())
        assertEquals(1234.toShort(), samples.get(0))
        assertEquals((-5).toShort(), samples.get(1))
        assertThrows(ReadOnlyBufferException::class.java) { samples.put(0, 0) }
    }
}
