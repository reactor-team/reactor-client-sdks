package inc.reactor.sdk.android

import inc.reactor.sdk.android.internal.JsonException
import inc.reactor.sdk.android.internal.Uploads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Upload decoding and staging.
 *
 * Staging is tested here rather than on a device because the interesting parts — the byte
 * ceiling, and what is left on disk when a copy fails — are plain file I/O. `uploadContent` is a
 * thin wrapper over this that only adds `ContentResolver`.
 */
class UploadsTest {
    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    // ── Decoding ─────────────────────────────────────────────────────────────

    @Test
    fun `a full result becomes a FileRef`() {
        val ref =
            Uploads.decode(
                """{"upload_id":"u-1","name":"cat.png","mime_type":"image/png","size":2048}""",
            )
        assertEquals(FileRef("u-1", "cat.png", "image/png", 2048L), ref)
    }

    /**
     * An upload with no id is not an upload with an empty id: handing back a FileRef that names
     * nothing fails later, inside a command, with nothing pointing back here.
     */
    @Test
    fun `a result with no upload_id is a decode failure`() {
        assertThrows(JsonException::class.java) {
            Uploads.decode("""{"name":"cat.png"}""")
        }
    }

    @Test
    fun `an absent result is a decode failure rather than an empty FileRef`() {
        assertThrows(JsonException::class.java) { Uploads.decode(null) }
        assertThrows(JsonException::class.java) { Uploads.decode("") }
    }

    @Test
    fun `a missing mime type falls back to octet-stream rather than empty`() {
        val ref = Uploads.decode("""{"upload_id":"u-2"}""")
        assertEquals("application/octet-stream", ref.mimeType)
        assertEquals(0L, ref.size)
    }

    // ── Staging ──────────────────────────────────────────────────────────────

    private fun stream(bytes: ByteArray): () -> InputStream = { ByteArrayInputStream(bytes) }

    @Test
    fun `a stream is staged verbatim`() {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val staged = Uploads.stage("pic.png", temp.root, 1_000_000, stream(payload))
        assertTrue(staged.isFile)
        assertTrue(payload.contentEquals(staged.readBytes()))
        assertTrue("the extension has to survive — MIME is inferred from it", staged.name.endsWith("pic.png"))
    }

    /**
     * The ceiling is enforced *while* copying. Checking afterwards would mean the bytes are
     * already on disk, which is the thing being prevented.
     */
    @Test
    fun `a stream over the limit is refused and leaves nothing behind`() {
        val before = temp.root.listFiles()!!.size
        val error =
            assertThrows(MessageTooLargeException::class.java) {
                Uploads.stage("big.bin", temp.root, maxBytes = 1024, open = stream(ByteArray(64 * 1024)))
            }
        assertTrue(error.message!!.contains("1024"))
        assertEquals("the partial copy must not survive the refusal", before, temp.root.listFiles()!!.size)
    }

    @Test
    fun `a stream that fails mid-copy leaves nothing behind`() {
        val before = temp.root.listFiles()!!.size
        assertThrows(IOException::class.java) {
            Uploads.stage("broken.bin", temp.root, 1_000_000) {
                object : InputStream() {
                    var served = 0

                    override fun read(): Int = throw IOException("device went away")

                    override fun read(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int {
                        if (served++ == 0) return len // one good chunk, then fail
                        throw IOException("device went away")
                    }
                }
            }
        }
        assertEquals(before, temp.root.listFiles()!!.size)
    }

    @Test
    fun `an empty stream stages an empty file rather than failing`() {
        val staged = Uploads.stage("empty.txt", temp.root, 1024, stream(ByteArray(0)))
        assertEquals(0L, staged.length())
    }

    @Test
    fun `a non-positive limit is a caller error`() {
        assertThrows(IllegalArgumentException::class.java) {
            Uploads.stage("x", temp.root, 0, stream(ByteArray(1)))
        }
    }
}
