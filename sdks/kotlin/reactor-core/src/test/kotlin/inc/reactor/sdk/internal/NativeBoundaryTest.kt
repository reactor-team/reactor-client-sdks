package inc.reactor.sdk.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBoundaryTest {
    init {
        val library = System.getProperty("reactor.jni.test.library")
        if (library == null) System.loadLibrary("reactor_jni_test") else System.load(library)
    }

    private external fun setAbi(value: Int)

    private external fun freeCount(): Int

    private external fun roundTrip(
        value: ByteArray?,
        owned: Boolean,
    ): ByteArray

    private external fun buffer(size: Long): ByteArray?

    private external fun lateCallback(receiver: Receiver)

    private external fun callbacks(receiver: Receiver): Boolean

    class Receiver(
        private val throwing: Boolean = false,
    ) {
        var count = 0
        var message: ByteArray? = null
        var payload: ByteArray? = null

        @Suppress("UNUSED_PARAMETER")
        fun accept(
            kind: Int,
            text: ByteArray?,
            data: ByteArray?,
        ) {
            count++
            message = text
            payload = data
            if (throwing && count == 1) error("handler failure")
        }
    }

    @Test
    fun failedDestroyRetainsCallbackTicket() {
        val receiver = Receiver()
        lateCallback(receiver)
        assertEquals(1, receiver.count)
        assertEquals("after destroy", receiver.message!!.toString(Charsets.UTF_8))
    }

    @Test
    fun checksHeaderAbiAndPreservesInt64() {
        NativeAbi.checkAbi()
        assertEquals(0x123456789abcdefL, NativeAbi.timeMicros())
        setAbi(999)
        try {
            val error = assertThrows(UnsatisfiedLinkError::class.java) { NativeAbi.checkAbi() }
            assertTrue(error.message!!.contains("library=999"))
        } finally {
            setAbi(2)
        }
    }

    @Test
    fun standardUtf8AndStringOwnership() {
        val value = "ação 日本語 🌍".toByteArray(Charsets.UTF_8)
        val before = freeCount()
        assertArrayEquals(value, roundTrip(value, false))
        assertEquals(before, freeCount())
        assertArrayEquals(value, roundTrip(value, true))
        assertEquals(before + 1, freeCount())
        assertArrayEquals(byteArrayOf(), roundTrip(byteArrayOf(), true))
        assertThrows(IllegalArgumentException::class.java) { roundTrip(null, false) }
        assertThrows(IllegalArgumentException::class.java) { roundTrip(byteArrayOf(65, 0, 66), false) }
    }

    @Test
    fun nullAndOverflowAreCheckedBeforeCopying() {
        assertNull(buffer(0))
        assertThrows(IllegalArgumentException::class.java) { buffer(Int.MAX_VALUE.toLong() + 1) }
        assertThrows(IllegalArgumentException::class.java) { buffer(-1) }
    }

    @Test
    fun foreignThreadCopiesDataAndContainsExceptions() {
        val receiver = Receiver()
        assertFalse(callbacks(receiver))
        assertEquals(1000, receiver.count)
        assertEquals("🌍", receiver.message!!.toString(Charsets.UTF_8))
        assertArrayEquals(byteArrayOf(0, 127, -128, -1), receiver.payload)
        val throwing = Receiver(true)
        assertTrue(callbacks(throwing))
        assertEquals(1000, throwing.count)
    }
}
