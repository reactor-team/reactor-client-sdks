package inc.reactor.sdk.android

import inc.reactor.sdk.android.internal.TrackParsing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The track list: declaration order, filters, and the refusals.
 *
 * The `_: VideoFrame ->` and `_: AudioFrame ->` annotations are load-bearing rather than noise.
 * `onFrame` is overloaded on the handler's parameter type — one frame API for both kinds, as
 * every Reactor SDK has — and a lambda whose body says nothing about its parameter gives the
 * compiler nothing to resolve on. Real call sites usually touch a field and need no annotation;
 * an empty test lambda always does.
 */
class TracksTest {
    private val owner =
        object : TrackOwner {
            val videoHandlers = mutableSetOf<String>()
            val audioHandlers = mutableSetOf<String>()

            override fun setVideoHandler(
                track: String,
                handler: (VideoFrame) -> Unit,
            ) {
                videoHandlers += track
            }

            override fun setAudioHandler(
                track: String,
                handler: (AudioFrame) -> Unit,
            ) {
                audioHandlers += track
            }

            override fun clearHandlers(track: String) {
                videoHandlers -= track
                audioHandlers -= track
            }

            override fun isPaused(track: String): Boolean = false
        }

    /** Deliberately not alphabetical: that is the whole point of the assertion. */
    private val declared =
        """
        [{"name":"webcam","kind":"video","direction":"sendonly"},
         {"name":"main_video","kind":"video","direction":"recvonly"},
         {"name":"mic","kind":"audio","direction":"sendonly"},
         {"name":"audio_out","kind":"audio","direction":"recvonly"}]
        """.trimIndent()

    private fun tracks() = TrackParsing.parse(declared, owner)

    @Test
    fun `declaration order is preserved, not sorted`() {
        assertEquals(listOf("webcam", "main_video", "mic", "audio_out"), tracks().map { it.name })
        // Sorting would put audio_out first. tracks[0] is a contract every SDK shares.
        assertEquals("webcam", tracks()[0].name)
    }

    @Test
    fun `filters chain in either order`() {
        val a = tracks().withKind(TrackKind.VIDEO).withDirection(TrackDirection.RECVONLY).one()
        val b = tracks().withDirection(TrackDirection.RECVONLY).withKind(TrackKind.VIDEO).one()
        assertEquals("main_video", a.name)
        assertEquals(a.name, b.name)
    }

    @Test
    fun `one() names the candidates when there is more than one`() {
        val error =
            assertThrows(InvalidStateException::class.java) {
                tracks().withKind(TrackKind.VIDEO).one()
            }
        assertTrue(error.message!!.contains("webcam"))
        assertTrue(error.message!!.contains("main_video"))
    }

    @Test
    fun `one() on an empty filter says what the session did declare`() {
        val error =
            assertThrows(InvalidStateException::class.java) {
                TrackParsing.parse("[]", owner).one()
            }
        assertTrue(error.message!!.contains("nothing"))
    }

    @Test
    fun `onFrame on a sendonly track is refused, naming the direction`() {
        val webcam = tracks().first { it.name == "webcam" }
        val error =
            assertThrows(InvalidStateException::class.java) { webcam.onFrame { _: VideoFrame -> } }
        assertTrue(error.message!!.contains("sendonly"))
        // The message has to point at the thing to do instead.
        assertTrue(error.message!!.contains("pushFrame"))
    }

    @Test
    fun `a video handler on an audio track is refused, naming the kind`() {
        val audioOut = tracks().first { it.name == "audio_out" }
        val error =
            assertThrows(InvalidStateException::class.java) { audioOut.onFrame { _: VideoFrame -> } }
        assertTrue(error.message!!.contains("audio"))
        assertTrue(error.message!!.contains("video"))
    }

    @Test
    fun `an audio handler on a video track is refused`() {
        val mainVideo = tracks().first { it.name == "main_video" }
        assertThrows(InvalidStateException::class.java) {
            mainVideo.onFrame { _: AudioFrame -> }
        }
    }

    @Test
    fun `a recvonly track of the right kind accepts its handler`() {
        tracks().first { it.name == "main_video" }.onFrame { _: VideoFrame -> }
        tracks().first { it.name == "audio_out" }.onFrame { _: AudioFrame -> }
        assertTrue("main_video" in owner.videoHandlers)
        assertTrue("audio_out" in owner.audioHandlers)
    }

    @Test
    fun `an unparseable track list is a decode failure, not an empty list`() {
        val error =
            assertThrows(DecodeFailedException::class.java) {
                TrackParsing.parse("{not json", owner)
            }
        assertEquals("DECODE_FAILED", error.code)
    }

    /**
     * An absent list is an answer — there is no session yet — where a malformed one is a bug.
     * Collapsing both to "no tracks" is what makes a parse failure invisible.
     */
    @Test
    fun `an absent track list is empty rather than an error`() {
        assertEquals(0, TrackParsing.parse(null, owner).size)
        assertEquals(0, TrackParsing.parse("", owner).size)
    }

    /**
     * A kind or direction this SDK does not know is skipped rather than guessed at: inventing a
     * direction would turn a refusal this SDK owes the caller into a silent no-op at the FFI.
     */
    @Test
    fun `an unrecognised kind or direction is skipped, not guessed`() {
        val parsed =
            TrackParsing.parse(
                """[{"name":"odd","kind":"haptic","direction":"recvonly"},
                {"name":"fine","kind":"video","direction":"recvonly"}]""",
                owner,
            )
        assertEquals(listOf("fine"), parsed.map { it.name })
    }

    @Test
    fun `mid is carried when the session has negotiated one`() {
        val parsed =
            TrackParsing.parse(
                """[{"name":"a","kind":"video","direction":"recvonly","mid":"0"}]""",
                owner,
            )
        assertEquals("0", parsed[0].mid)
        assertNull(tracks()[0].mid)
    }
}
