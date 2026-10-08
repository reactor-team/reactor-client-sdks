package inc.reactor.sdk.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind the audio adapters.
 *
 * `AudioRecord` and `AudioTrack` cannot be constructed on a JVM, so this is the half of those
 * classes a test can reach — and it is the half that is wrong in practice. A buffer sized at the
 * platform's own minimum drops audio on every scheduling hiccup; a frame count confused with a
 * sample count halves or doubles the duration of every buffer in a stereo stream.
 */
class AudioFormatsTest {
    @Test
    fun `frames are counted per channel, not per sample`() {
        // 960 bytes = 480 s16 samples = 480 frames mono, 240 frames stereo.
        assertEquals(480, AudioFormats.framesIn(960, channels = 1))
        assertEquals(240, AudioFormats.framesIn(960, channels = 2))
    }

    /**
     * The platform's minimum is the point at which the device *just* keeps up. Handing it back is
     * what makes an app drop audio whenever anything else happens on the system.
     */
    @Test
    fun `the buffer is comfortably larger than the platform minimum`() {
        val minimum = 1_920
        val size = AudioFormats.bufferSizeBytes(minimum, sampleRate = 48_000, channels = 1)
        assertTrue("got $size for a minimum of $minimum", size >= minimum * 4)
    }

    /** And large enough in *time*, which is the constraint a tiny minimum does not express. */
    @Test
    fun `the buffer holds at least the requested duration even when the minimum is tiny`() {
        // 48kHz mono s16 is 96 bytes per millisecond; 100ms is 9600.
        val size = AudioFormats.bufferSizeBytes(64, sampleRate = 48_000, channels = 1, millis = 100)
        assertTrue("got $size, wanted at least 9600", size >= 9_600)
    }

    @Test
    fun `a stereo buffer is twice a mono one for the same duration`() {
        val mono = AudioFormats.bufferSizeBytes(64, 48_000, 1, millis = 100)
        val stereo = AudioFormats.bufferSizeBytes(64, 48_000, 2, millis = 100)
        assertEquals(mono * 2, stereo)
    }

    /**
     * AudioRecord reports "I cannot do this format" as a negative return, not an exception.
     * Passing it on builds an object whose state is UNINITIALIZED and whose failure surfaces much
     * later as silence.
     */
    @Test
    fun `a negative platform minimum is refused, naming what it means`() {
        for (reported in listOf(0, -1, -2)) {
            val error =
                assertThrows(IllegalArgumentException::class.java) {
                    AudioFormats.bufferSizeBytes(reported, 48_000, 1)
                }
            assertTrue(error.message!!.contains("cannot do"))
        }
    }

    @Test
    fun `an unsupported channel count is refused`() {
        assertThrows(IllegalArgumentException::class.java) { AudioFormats.requireSupported(48_000, 0) }
        assertThrows(IllegalArgumentException::class.java) { AudioFormats.requireSupported(48_000, 3) }
        assertThrows(IllegalArgumentException::class.java) { AudioFormats.requireSupported(0, 1) }
        AudioFormats.requireSupported(44_100, 1)
        AudioFormats.requireSupported(48_000, 2)
    }

    /**
     * The two causes want different fixes, so they must not share a message. "Could not open the
     * microphone" sends a developer to the audio format when the answer is a permission dialog.
     */
    @Test
    fun `a missing permission and a refused format say different things`() {
        val permission = AudioFormats.captureFailureMessage(48_000, 1, hasPermission = false)
        val format = AudioFormats.captureFailureMessage(48_000, 1, hasPermission = true)

        assertTrue(permission.contains("RECORD_AUDIO"))
        assertTrue("the app is the one that must ask", permission.contains("app's call"))

        assertTrue("the format message must name the format", format.contains("48000"))
        assertTrue("and point at one that works", format.contains("44100"))
        assertTrue("and must not blame the permission", !format.contains("RECORD_AUDIO"))
    }
}
