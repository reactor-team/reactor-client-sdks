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

            // Publishing is PublishingTest's subject; these exist so this suite can construct Tracks.
            override fun publishState(track: String) = PublishState.UNPUBLISHED

            override suspend fun publish(track: String) = Unit

            override suspend fun unpublish(track: String) = Unit

            override suspend fun pause(track: String) = Unit

            override suspend fun resume(track: String) = Unit

            override fun pushVideoFrame(
                track: String,
                pixels: java.nio.ByteBuffer,
                width: Int,
                height: Int,
                userData: ByteArray?,
            ) = Unit

            override fun pushAudioFrame(
                track: String,
                pcm: java.nio.ByteBuffer,
                samplesPerChannel: Int,
                sampleRate: Int,
                channels: Int,
            ) = Unit
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

    /**
     * The empty-filter message must name what the *session* declared.
     *
     * Reported from the filtered list it said "nothing" on a session that declared four tracks —
     * sending a reader to a model manifest to look for an emptiness that was never there.
     */
    @Test
    fun `an empty filter names the session's own tracks, not the filtered none`() {
        val declaredOnlyVideo =
            TrackParsing.parse(
                """[{"name":"webcam","kind":"video","direction":"sendonly"},
                    {"name":"main_video","kind":"video","direction":"recvonly"}]""",
                owner,
            )
        val error =
            assertThrows(InvalidStateException::class.java) {
                declaredOnlyVideo.withKind(TrackKind.AUDIO).one()
            }
        assertTrue(
            "the message must list the declared tracks: ${error.message}",
            error.message!!.contains("webcam") && error.message!!.contains("main_video"),
        )
        assertTrue(
            "and must not claim the session declared nothing",
            !error.message!!.contains("nothing"),
        )
    }

    /** Chained filters must not lose it either. */
    @Test
    fun `the declared set survives a chain of filters`() {
        val error =
            assertThrows(InvalidStateException::class.java) {
                tracks()
                    .withKind(TrackKind.AUDIO)
                    .withDirection(TrackDirection.SENDONLY)
                    .withKind(TrackKind.VIDEO)
                    .one()
            }
        assertTrue(error.message!!.contains("webcam"))
    }

    // ── Wrong-shaped payloads ────────────────────────────────────────────────

    /**
     * A well-formed payload of the wrong shape is a platform bug, not an empty session.
     *
     * It used to become "no tracks declared", silently, while a malformed one raised — the same
     * class of fault reported two different ways, and the quiet one sends a reader to the model
     * manifest for an answer that is not there.
     */
    @Test
    fun `a well-formed payload of the wrong shape is a decode failure`() {
        for (payload in listOf("{}", "\"webcam\"", "42", "true")) {
            val error =
                assertThrows(
                    "a $payload track list must not read as an empty session",
                    DecodeFailedException::class.java,
                ) { TrackParsing.parse(payload, owner) }
            assertEquals("DECODE_FAILED", error.code)
        }
    }

    // ── Paused tracks ────────────────────────────────────────────────────────

    @Test
    fun `the paused list is parsed, not substring-matched`() {
        assertEquals(setOf("main_video"), TrackParsing.parsePaused("""["main_video"]"""))
        assertEquals(emptySet<String>(), TrackParsing.parsePaused(null))
        assertEquals(emptySet<String>(), TrackParsing.parsePaused(""))
    }

    /**
     * The two cases a substring match gets wrong, and they fail in opposite directions.
     *
     * A name the encoder escapes never matches its own entry; a name that is a substring of
     * another matches a track that is not paused.
     */
    @Test
    fun `an escaped name matches itself and a shorter name does not match a longer one`() {
        val paused = TrackParsing.parsePaused("""["a\"b", "webcam"]""")
        assertTrue("an escaped name must match itself", """a"b""" in paused)
        assertTrue("cam must not match webcam", "cam" !in paused)
        assertTrue("webcam must match webcam", "webcam" in paused)
    }

    /** Advisory, not structural: this feeds a property read, so a bad list is "none paused". */
    @Test
    fun `an unparseable paused list reads as none rather than raising`() {
        assertEquals(emptySet<String>(), TrackParsing.parsePaused("{not json"))
        assertEquals(emptySet<String>(), TrackParsing.parsePaused("{}"))
    }
}
