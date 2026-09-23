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

            override suspend fun pause(track: String) = Unit

            override suspend fun resume(track: String) = Unit

            override fun pushVideoFrame(
                track: String,
                pixels: ByteBuffer,
                width: Int,
                height: Int,
                userData: ByteArray?,
            ) {
                pushedVideo++
            }

            override fun pushAudioFrame(
                track: String,
                pcm: ByteBuffer,
                samplesPerChannel: Int,
                sampleRate: Int,
                channels: Int,
            ) {
                pushedAudio++
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
}
