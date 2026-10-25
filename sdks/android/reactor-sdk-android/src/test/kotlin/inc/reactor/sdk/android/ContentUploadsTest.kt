package inc.reactor.sdk.android

import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException

/** How a content stream that will not open becomes the documented [NotFoundException]. */
class ContentUploadsTest {
    @Test
    fun `a FileNotFoundException from the resolver becomes NotFoundException, keeping the cause`() {
        val original = FileNotFoundException("No content provider: content://gone/1")
        val thrown =
            assertThrows(NotFoundException::class.java) {
                openContent("content://gone/1") { throw original }
            }
        assertSame(original, thrown.cause)
        assertTrue(thrown.message!!.contains("content://gone/1"))
    }

    @Test
    fun `a SecurityException from a revoked grant becomes NotFoundException, keeping the cause`() {
        val original = SecurityException("Permission Denial: content://revoked/1")
        val thrown =
            assertThrows(NotFoundException::class.java) {
                openContent("content://revoked/1") { throw original }
            }
        assertSame(original, thrown.cause)
        assertTrue(thrown.message!!.contains("content://revoked/1"))
    }

    @Test
    fun `a null stream from the resolver becomes NotFoundException`() {
        assertThrows(NotFoundException::class.java) { openContent("content://gone/1") { null } }
    }

    @Test
    fun `an opened stream is returned as is`() {
        val stream = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        assertSame(stream, openContent("content://ok/1") { stream })
    }
}
