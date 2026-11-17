package inc.reactor.sdk.android

import inc.reactor.sdk.android.internal.CodecPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step frame codecs, off-device: what is sent to the native side and when, what it answers, and
 * how a step asks for keyframes. What crosses the JNI boundary — the nullables, and the owned
 * strings freed once — is under AddressSanitizer in `asan_harness.cpp`.
 */
class StepCodecsTest {
    /** A stand-in for a native handle's `setCodecPreference`: records calls, answers [error]. */
    private class Sink(
        var error: String? = null,
    ) {
        val calls = mutableListOf<Pair<String?, String?>>()

        fun send(
            track: String?,
            codecsJson: String?,
        ): String? {
            calls += track to codecsJson
            return error
        }
    }

    @Test
    fun `codecs cross as the JSON array the native library takes`() {
        assertEquals("""["h264","vp9","av1","vp8"]""", CodecPreferences.toJson(StepCodec.entries))
        assertEquals("[]", CodecPreferences.toJson(emptyList()))
        // Null is the default, and must stay null rather than become "null".
        assertNull(CodecPreferences.toJson(null))
    }

    @Test
    fun `preferences chosen before connect are stored and replayed onto each new handle`() {
        val preferences = CodecPreferences()
        // No handle yet: the sender answers nothing and sends nothing anywhere.
        preferences.set(null, emptyList()) { _, _ -> null }
        preferences.set("main_video", listOf(StepCodec.AV1, StepCodec.H264)) { _, _ -> null }

        val first = Sink()
        preferences.replay(first::send)
        assertEquals(listOf(null to "[]", "main_video" to """["av1","h264"]"""), first.calls)

        // A handle rebuilt for another model or token hears the same, in the same order.
        val second = Sink()
        preferences.replay(second::send)
        assertEquals(first.calls, second.calls)
    }

    @Test
    fun `a preference chosen during a session is sent at once and kept for the next handle`() {
        val preferences = CodecPreferences()
        val live = Sink()
        preferences.set(null, listOf(StepCodec.VP9)) { track, json -> live.send(track, json) }
        assertEquals(listOf(null to """["vp9"]"""), live.calls)

        // A later choice for the same scope replaces the earlier one; null resets to the default.
        preferences.set(null, null) { track, json -> live.send(track, json) }
        assertEquals(null to null, live.calls.last())
        val next = Sink()
        preferences.replay(next::send)
        assertEquals(listOf<Pair<String?, String?>>(null to null), next.calls)
    }

    @Test
    fun `invalid preferences are refused before anything is sent`() {
        val preferences = CodecPreferences()
        val sink = Sink()
        assertThrows(IllegalArgumentException::class.java) {
            preferences.set(" ", listOf(StepCodec.H264), sink::send)
        }
        val twice =
            assertThrows(IllegalArgumentException::class.java) {
                preferences.set(null, listOf(StepCodec.H264, StepCodec.VP8, StepCodec.H264), sink::send)
            }
        assertTrue(twice.message!!.contains("H264"))
        assertTrue(sink.calls.isEmpty())
        assertTrue(preferences.snapshot().isEmpty())
    }

    @Test
    fun `a refusal from the native layer is the typed exception, and nothing is kept`() {
        val preferences = CodecPreferences()
        val sink = Sink(error = """{"code":"DECODE_FAILED","message":"unknown codec","recoverable":false}""")
        val error =
            assertThrows(DecodeFailedException::class.java) {
                preferences.set(null, listOf(StepCodec.H264), sink::send)
            }
        assertEquals("unknown codec", error.message)
        assertEquals(CodecPreferences.OPERATION, error.operation)
        // Refused, so the next handle does not hear it.
        assertTrue(preferences.snapshot().isEmpty())

        // And a refusal on replay is thrown the same way rather than swallowed.
        preferences.set(null, listOf(StepCodec.H264)) { _, _ -> null }
        assertThrows(DecodeFailedException::class.java) { preferences.replay(sink::send) }
    }

    @Test
    fun `a keyframe request is the only thing that sends step options`() {
        assertNull(CodecPreferences.stepOptionsJson(false))
        assertEquals("""{"request_keyframe":true}""", CodecPreferences.stepOptionsJson(true))
    }

    @Test
    fun `the negotiated encodings are read by track`() {
        assertEquals(
            mapOf("main_video" to "h264", "webcam" to "h264", "main_audio" to "raw"),
            CodecPreferences.parseEncodings("""{"main_video":"h264","webcam":"h264","main_audio":"raw"}"""),
        )
        assertEquals(emptyMap<String, String>(), CodecPreferences.parseEncodings("{}"))
        assertEquals(emptyMap<String, String>(), CodecPreferences.parseEncodings(null))
        assertThrows(DecodeFailedException::class.java) { CodecPreferences.parseEncodings("[]") }
        assertThrows(DecodeFailedException::class.java) { CodecPreferences.parseEncodings("""{"main_video":1}""") }
    }

    @Test
    fun `a client takes a preference before connect and reports no encodings yet`() {
        val reactor = Reactor(ReactorOptions(jwt = "t", generation = Generation.STEPPED), Dispatchers.Unconfined)
        reactor.setCodecPreference(listOf(StepCodec.H264))
        reactor.setCodecPreference(emptyList())
        reactor.setCodecPreference(null, track = "main_video")
        assertEquals(emptyMap<String, String>(), reactor.stepFrameEncodings)
        assertThrows(IllegalArgumentException::class.java) { reactor.setCodecPreference(listOf(StepCodec.VP8, StepCodec.VP8)) }
        reactor.close()
        assertThrows(InvalidStateException::class.java) { reactor.setCodecPreference(null) }
    }

    @Test
    fun `a keyframe request on a streamed client is refused like any step`() {
        val reactor = Reactor(ReactorOptions(jwt = "t"), Dispatchers.Unconfined)
        assertThrows(InvalidStateException::class.java) { runBlocking { reactor.step(requestKeyframe = true) } }
        reactor.close()
    }
}
