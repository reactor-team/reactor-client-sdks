package inc.reactor.sdk.android

import inc.reactor.sdk.android.internal.TrackParsing
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The refuse-don't-fail-quietly table, which is the reason this slice exists.
 *
 * Every row here reaches the native layer, finds nothing to do, and returns. The caller sees a
 * loop pushing at 30 fps and a model receiving nothing. Each must raise instead, with the fix in
 * the message.
 */
class PublishingTest {
    private val owner =
        object : TrackOwner {
            val states = mutableMapOf<String, PublishState>()
            var pushedVideo = 0
            var pushedAudio = 0
            var lastPixels: ByteBuffer? = null
            var lastPcm: ByteBuffer? = null
            var paused = 0

            override fun setVideoHandler(
                track: String,
                handler: (VideoFrame) -> Unit,
            ) = Unit

            override fun setAudioHandler(
                track: String,
                handler: (AudioFrame) -> Unit,
            ) = Unit

            override fun clearHandlers(track: String) = Unit

            override fun isPaused(track: String) = false

            override fun publishState(track: String) = states[track] ?: PublishState.UNPUBLISHED

            override suspend fun publish(track: String) {
                states[track] = PublishState.PUBLISHED
            }

            override suspend fun unpublish(track: String) {
                states.remove(track)
            }

            override suspend fun pause(track: String) {
                paused++
            }

            override suspend fun resume(track: String) = Unit

            override fun pushVideoFrame(
                track: String,
                pixels: ByteBuffer,
                width: Int,
                height: Int,
                userData: ByteArray?,
            ) {
                pushedVideo++
                lastPixels = pixels
            }

            override fun pushAudioFrame(
                track: String,
                pcm: ByteBuffer,
                samplesPerChannel: Int,
                sampleRate: Int,
                channels: Int,
            ) {
                pushedAudio++
                lastPcm = pcm
            }
        }

    private val declared =
        """
        [{"name":"webcam","kind":"video","direction":"sendonly"},
         {"name":"main_video","kind":"video","direction":"recvonly"},
         {"name":"mic","kind":"audio","direction":"sendonly"}]
        """.trimIndent()

    private fun track(name: String) = TrackParsing.parse(declared, owner).first { it.name == name }

    private fun frame(
        width: Int,
        height: Int,
    ): ByteBuffer = ByteBuffer.allocateDirect(width * height * 4)

    @Test
    fun `pushFrame on a recvonly track is refused, naming the direction`() {
        val error =
            assertThrows(InvalidStateException::class.java) {
                track("main_video").pushFrame(frame(2, 2), 2, 2)
            }
        assertTrue(error.message!!.contains("recvonly"))
        assertTrue("the message must point at what to do instead", error.message!!.contains("onFrame"))
    }

    @Test
    fun `pushFrame before publish is refused, and says publishing is what attaches a sender`() {
        val error =
            assertThrows(InvalidStateException::class.java) {
                track("webcam").pushFrame(frame(2, 2), 2, 2)
            }
        assertTrue(error.message!!.contains("publish()"))
        assertTrue(error.message!!.contains("dropped"))
    }

    /** The third state earning its place: in-flight is neither published nor unpublished. */
    @Test
    fun `pushFrame while still publishing says to await the publish`() {
        owner.states["webcam"] = PublishState.PUBLISHING
        val error =
            assertThrows(InvalidStateException::class.java) {
                track("webcam").pushFrame(frame(2, 2), 2, 2)
            }
        assertTrue(error.message!!.contains("still publishing"))
        assertTrue(
            "telling a caller who just called publish() to call publish() is the wrong advice",
            !error.message!!.contains("Call publish() first"),
        )
    }

    @Test
    fun `a wrong-length buffer is refused, naming both numbers`() =
        runTest {
            track("webcam").publish()
            val error =
                assertThrows(BadRequestException::class.java) {
                    // A 2x2 buffer is 16 bytes; a 4x4 BGRA frame needs 4 * 4 * 4 = 64.
                    track("webcam").pushFrame(frame(2, 2), 4, 4)
                }
            assertTrue("must name what it got: ${error.message}", error.message!!.contains("16"))
            assertTrue("and what it needed: ${error.message}", error.message!!.contains("64"))
        }

    @Test
    fun `a heap buffer is refused — the native layer reads it in place`() =
        runTest {
            track("webcam").publish()
            val heap = ByteBuffer.allocate(2 * 2 * 4)
            val error =
                assertThrows(IllegalArgumentException::class.java) {
                    track("webcam").pushFrame(heap, 2, 2)
                }
            assertTrue(error.message!!.contains("allocateDirect"))
        }

    @Test
    fun `audio pushed into a video track is refused, naming both kinds`() =
        runTest {
            track("webcam").publish()
            val error =
                assertThrows(InvalidStateException::class.java) {
                    track("webcam").pushFrame(ByteBuffer.allocateDirect(64), 16, 48000, 1)
                }
            assertTrue(error.message!!.contains("video"))
            assertTrue(error.message!!.contains("audio"))
        }

    @Test
    fun `video pushed into an audio track is refused`() =
        runTest {
            track("mic").publish()
            assertThrows(InvalidStateException::class.java) {
                track("mic").pushFrame(frame(2, 2), 2, 2)
            }
        }

    @Test
    fun `publish on a recvonly track is refused`() =
        runTest {
            val error =
                assertThrows(InvalidStateException::class.java) {
                    kotlinx.coroutines.runBlocking { track("main_video").publish() }
                }
            assertTrue(error.message!!.contains("recvonly"))
        }

    @Test
    fun `a published sendonly track accepts a correctly sized direct buffer`() =
        runTest {
            val webcam = track("webcam")
            webcam.publish()
            assertTrue(webcam.published)
            webcam.pushFrame(frame(4, 4), 4, 4)
            assertEquals(1, owner.pushedVideo)
        }

    @Test
    fun `publishState has three values and published means only the last`() {
        assertEquals(3, PublishState.entries.size)
        owner.states["webcam"] = PublishState.PUBLISHING
        assertTrue(!track("webcam").published)
        owner.states["webcam"] = PublishState.PUBLISHED
        assertTrue(track("webcam").published)
    }

    // ── What crosses JNI must describe the buffer exactly ───────────────────

    /** The native layer takes both as unsigned: -1 x -1 "needs" 4 bytes and reads gigabytes. */
    @Test
    fun `non-positive dimensions are refused before they reach the native layer`() =
        runTest {
            track("webcam").publish()
            for ((w, h) in listOf(-1 to -1, 0 to 4, 4 to 0, -2 to 2)) {
                assertThrows("${w}x$h", BadRequestException::class.java) {
                    track("webcam").pushFrame(ByteBuffer.allocateDirect(4), w, h)
                }
            }
            assertEquals(0, owner.pushedVideo)
        }

    @Test
    fun `a video buffer with a nonzero position is passed from that position`() =
        runTest {
            track("webcam").publish()
            val buffer = ByteBuffer.allocateDirect(8 + 2 * 2 * 4)
            buffer.put(7, 0x5A)
            buffer.position(8)
            buffer.put(8, 0x11)
            track("webcam").pushFrame(buffer, 2, 2)
            val passed = owner.lastPixels!!
            assertEquals("the native side reads from the base address", 0, passed.position())
            assertEquals(16, passed.remaining())
            assertEquals(0x11.toByte(), passed.get(0))
        }

    @Test
    fun `audio of the right length in a supported format is pushed`() =
        runTest {
            track("mic").publish()
            track("mic").pushFrame(ByteBuffer.allocateDirect(480 * 2 * 2), 480, 48000, 2)
            assertEquals(1, owner.pushedAudio)
        }

    @Test
    fun `an audio buffer shorter than its counts say is refused, naming both numbers`() =
        runTest {
            track("mic").publish()
            val error =
                assertThrows(BadRequestException::class.java) {
                    // 480 samples x 2 channels x 2 bytes = 1920; give it half.
                    track("mic").pushFrame(ByteBuffer.allocateDirect(960), 480, 48000, 2)
                }
            assertTrue(error.message!!.contains("960"))
            assertTrue(error.message!!.contains("1920"))
            assertEquals(0, owner.pushedAudio)
        }

    @Test
    fun `audio counts the native layer would read as huge are refused`() =
        runTest {
            track("mic").publish()
            for ((samples, rate, channels) in listOf(
                Triple(-1, 48000, 1),
                Triple(0, 48000, 1),
                Triple(480, -48000, 1),
                Triple(480, 48000, -1),
            )) {
                assertThrows("$samples/$rate/$channels", BadRequestException::class.java) {
                    track("mic").pushFrame(ByteBuffer.allocateDirect(4), samples, rate, channels)
                }
            }
            assertEquals(0, owner.pushedAudio)
        }

    @Test
    fun `an unsupported audio format is refused, listing the supported ones`() =
        runTest {
            track("mic").publish()
            val error =
                assertThrows(BadRequestException::class.java) {
                    track("mic").pushFrame(ByteBuffer.allocateDirect(480 * 3 * 2), 480, 22050, 3)
                }
            assertTrue(error.message!!.contains("48000"))
        }

    @Test
    fun `an audio buffer with a nonzero position is passed from that position`() =
        runTest {
            track("mic").publish()
            val buffer = ByteBuffer.allocateDirect(6 + 160 * 2)
            buffer.position(6)
            track("mic").pushFrame(buffer, 160, 16000, 1)
            assertEquals(0, owner.lastPcm!!.position())
            assertEquals(320, owner.lastPcm!!.remaining())
        }

    @Test
    fun `pause and resume on a sendonly track are refused, pointing at publishing`() =
        runTest {
            val pause = assertThrows(InvalidStateException::class.java) { kotlinx.coroutines.runBlocking { track("webcam").pause() } }
            assertTrue(pause.message!!.contains("unpublish()"))
            val resume = assertThrows(InvalidStateException::class.java) { kotlinx.coroutines.runBlocking { track("webcam").resume() } }
            assertTrue(resume.message!!.contains("publish()"))
            assertEquals(0, owner.paused)
        }

    @Test
    fun `pause on a recvonly track reaches the native layer`() =
        runTest {
            track("main_video").pause()
            assertEquals(1, owner.paused)
        }

    @Test
    fun `PCM at an odd position is refused, since the samples would be misaligned`() =
        runTest {
            track("mic").publish()
            val buffer = ByteBuffer.allocateDirect(1 + 160 * 2)
            buffer.position(1)
            val error =
                assertThrows(BadRequestException::class.java) {
                    track("mic").pushFrame(buffer, 160, 16000, 1)
                }
            assertTrue(error.message!!.contains("even"))
            assertEquals(0, owner.pushedAudio)
        }
}
