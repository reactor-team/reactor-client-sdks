package inc.reactor.sdk.android

import inc.reactor.sdk.android.internal.Completions
import inc.reactor.sdk.android.internal.ErrorPayloads
import inc.reactor.sdk.android.internal.Json
import inc.reactor.sdk.android.internal.NativeClient
import inc.reactor.sdk.android.internal.StepResultSource
import inc.reactor.sdk.android.internal.StepResults
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteOrder

/**
 * Stepped generation, off-device: the request this SDK builds, the result it reads, and the
 * refusals that keep a stepped client from looking healthy while receiving nothing.
 *
 * What crosses the JNI boundary — the trampoline that frees each native result exactly once, and
 * the accessors that copy before that free — is under AddressSanitizer in `asan_harness.cpp`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SteppedGenerationTest {
    // ── The request ─────────────────────────────────────────────────────────

    @Test
    fun `a step command becomes the object the native library takes`() {
        assertEquals("""{"command":"reset","data":null}""", StepCommand("reset").toWire())
        assertEquals(
            """{"command":"advance","data":{"frames": 4}}""",
            StepCommand("advance", """{"frames": 4}""").toWire(),
        )
        // The name is quoted, not pasted: a quote in it must not end the string early.
        val quoted = Json.parseObject(StepCommand("say \"hi\"").toWire())
        assertEquals("say \"hi\"", quoted["command"])
    }

    @Test
    fun `a step command refuses what the runtime would`() {
        assertThrows(IllegalArgumentException::class.java) { StepCommand("") }
        val notAnObject = assertThrows(IllegalArgumentException::class.java) { StepCommand("advance", "[1, 2]") }
        assertTrue(notAnObject.message!!.contains("advance"))
        assertThrows(IllegalArgumentException::class.java) { StepCommand("advance", "{not json") }
        // JSON null is no arguments, as a missing object is.
        assertEquals("""{"command":"advance","data":null}""", StepCommand("advance", "null").toWire())
    }

    @Test
    fun `a step timeout needs a stepped session and a real duration`() {
        assertThrows(IllegalArgumentException::class.java) { ReactorOptions(stepTimeoutSeconds = 10.0) }
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                ReactorOptions(generation = Generation.STEPPED, stepTimeoutSeconds = bad)
            }
        }
        assertEquals(12.5, ReactorOptions(generation = Generation.STEPPED, stepTimeoutSeconds = 12.5).stepTimeoutSeconds)
        assertEquals(Generation.STREAMED, ReactorOptions().generation)
    }

    @Test
    fun `a stepped client asks for the mode and keeps the synthetic audio module`() {
        val options =
            Json.parseObject(
                NativeClient.createOptionsJson(
                    local = true,
                    autoResumeTracks = false,
                    sdkVersion = "1.0.0",
                    sdkType = "android",
                    generation = Generation.STEPPED,
                    stepTimeoutSeconds = 12.5,
                ),
            )
        assertEquals("stepped", options["generation"])
        // The one key that decides whether a microphone can open: pinned as on the streamed path.
        assertEquals("synthetic", options["adm"])
        assertEquals(12.5, options["step_timeout_seconds"])
        assertEquals(true, options["local"])
        assertEquals(false, options["auto_resume_tracks"])
        assertEquals("android", options["sdk_type"])

        // The library refuses an unknown key, so nothing is sent that was not asked for.
        val defaults =
            Json.parseObject(NativeClient.createOptionsJson(false, true, null, null, Generation.STEPPED, null))
        assertFalse("step_timeout_seconds" in defaults)
        assertFalse("sdk_version" in defaults)
    }

    // ── The result ──────────────────────────────────────────────────────────

    @Test
    fun `a result is copied out whole`() {
        val result = StepResults.read(FakeResult(), 1L)
        assertEquals(7L, result.step)
        assertEquals(0.25, result.elapsed, 0.0)
        assertNull(result.error)
        assertEquals(listOf(StepFile("depth.npy", "application/octet-stream", 64L)), result.files)

        val video = result.track("main_video")
        assertEquals(TrackKind.VIDEO, video.kind)
        assertEquals(30.0, video.rate, 0.0)
        val frame = video.frames.single()
        assertArrayEquals(intArrayOf(2, 2, 3), frame.shape)
        assertEquals("uint8", frame.dtype)
        assertEquals(12, frame.byteSize)
        assertArrayEquals(byteArrayOf(9, 8, 7), frame.metadata)
        assertEquals(424242L, frame.captureTimeUs)

        val audio = result.track("main_audio").frames.single()
        assertEquals("int16", audio.dtype)
        // Little-endian, as the ABI says, so the buffer reads samples directly.
        assertEquals(ByteOrder.LITTLE_ENDIAN, audio.data().order())
        assertEquals(258.toShort(), audio.data().asShortBuffer().get(0))
        // No tag and no capture time are absences, not empty values.
        assertNull(audio.metadata)
        assertNull(audio.captureTimeUs)
    }

    @Test
    fun `a frame whose bytes do not fill its shape is a decode failure`() {
        val short = FakeResult(videoData = ByteArray(11))
        val error = assertThrows(DecodeFailedException::class.java) { StepResults.read(short, 1L) }
        assertTrue(error.message!!.contains("main_video"))
        assertEquals("step", error.operation)
    }

    @Test
    fun `a frame of any dtype the core knows is size-checked as the core does`() {
        val floats = StepResults.read(FakeResult(videoDtype = "float32", videoData = ByteArray(48)), 1L)
        assertEquals(
            "float32",
            floats
                .track("main_video")
                .frames
                .single()
                .dtype,
        )
        assertThrows(DecodeFailedException::class.java) {
            StepResults.read(FakeResult(videoDtype = "float32", videoData = ByteArray(12)), 1L)
        }
    }

    @Test
    fun `a frame of a dtype the core does not size is kept as raw bytes`() {
        val result = StepResults.read(FakeResult(videoDtype = "complex64", videoData = ByteArray(5)), 1L)
        val frame = result.track("main_video").frames.single()
        assertEquals("complex64", frame.dtype)
        assertEquals(5, frame.byteSize)
    }

    @Test
    fun `a track of a kind this SDK does not know is a decode failure`() {
        assertThrows(DecodeFailedException::class.java) { StepResults.read(FakeResult(videoKind = "depth"), 1L) }
    }

    @Test
    fun `a track that produced nothing is told apart from a typo`() {
        val result = StepResults.read(FakeResult(), 1L)
        assertNull(result.findTrack("main_vidoe"))
        val error = assertThrows(NotFoundException::class.java) { result.track("main_vidoe") }
        assertTrue(error.message!!.contains("main_video"))
    }

    @Test
    fun `a frame is a value whose bytes nothing outside can change`() {
        val first = StepResults.read(FakeResult(), 1L)
        val second = StepResults.read(FakeResult(), 1L)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())

        val frame = first.track("main_video").frames.single()
        frame.toByteArray()[0] = 99
        frame.metadata!![0] = 99
        frame.shape[0] = 99
        assertEquals(first, second)
        assertTrue(frame.data().isReadOnly)
        // An absent tag and an empty one are different frames, as they are on the wire.
        val untagged = StepResults.read(FakeResult(videoTag = null), 1L)
        val emptyTag = StepResults.read(FakeResult(videoTag = ByteArray(0)), 1L)
        assertNotEquals(untagged, emptyTag)
    }

    // ── Completions ─────────────────────────────────────────────────────────

    @Test
    fun `a step settles with what the reader copied`() =
        runTest {
            val ticket = CompletableDeferred<Long>()
            val result =
                async {
                    Completions.awaitResult("step", read = { StepResults.read(FakeResult(), it) }) { ticket.complete(it) }
                }
            Completions.settleStepFromNative(ticket.await(), ok = true, result = 1L, errorJson = null)
            assertEquals(7L, result.await().step)
        }

    @Test
    fun `a refused step throws the typed error with the entry at fault`() =
        runTest {
            val ticket = CompletableDeferred<Long>()
            val result =
                async {
                    runCatching { Completions.awaitResult("step", read = { error("not read") }) { ticket.complete(it) } }
                }
            Completions.settleStepFromNative(
                ticket.await(),
                ok = false,
                result = 0L,
                errorJson = """{"code":"step_command_failed","message":"no","entry":"commands[1]","command_code":"bad_frames"}""",
            )
            val error = awaitFailure<StepCommandFailedException>(result)
            assertEquals("commands[1]", error.entry)
            assertEquals("bad_frames", error.commandCode)
        }

    @Test
    fun `a result that cannot be read fails the step rather than hanging it`() =
        runTest {
            val ticket = CompletableDeferred<Long>()
            val result =
                async {
                    runCatching {
                        Completions.awaitResult("step", read = { StepResults.read(FakeResult(videoData = ByteArray(1)), it) }) {
                            ticket.complete(it)
                        }
                    }
                }
            Completions.settleStepFromNative(ticket.await(), ok = true, result = 1L, errorJson = null)
            awaitFailure<DecodeFailedException>(result)
        }

    @Test
    fun `a step nobody waits on any more is dropped without reading it`() {
        // The ticket is unknown: the caller was cancelled. Nothing may read the result — the
        // trampoline frees it the moment this returns.
        Completions.settleStepFromNative(Long.MAX_VALUE, ok = true, result = 1L, errorJson = null)
    }

    @Test
    fun `entry and command code are read from any error payload`() {
        val refused = ErrorPayloads.toException("""{"code":"step_refused","message":"m","entry":"state.prompt"}""")
        assertTrue(refused is StepRefusedException)
        assertEquals("state.prompt", refused.entry)
        assertNull(refused.commandCode)
        val plain = ErrorPayloads.toException("""{"code":"CONFLICT","message":"m"}""")
        assertNull(plain.entry)
    }

    // ── The client ──────────────────────────────────────────────────────────

    @Test
    fun `step on a streamed client is refused, saying how to ask for one`() {
        val reactor = Reactor(ReactorOptions(jwt = "t"), Dispatchers.Unconfined)
        val error = assertThrows(InvalidStateException::class.java) { runBlocking { reactor.step() } }
        assertTrue(error.message!!.contains("Generation.STEPPED"))
        // A no-op rather than an error: there is nothing buffered to drop.
        reactor.clearStepInputs()
    }

    @Test
    fun `step before connect is refused rather than left waiting`() {
        val reactor = Reactor(ReactorOptions(jwt = "t", generation = Generation.STEPPED), Dispatchers.Unconfined)
        assertEquals(Generation.STEPPED, reactor.generation)
        assertThrows(InvalidStateException::class.java) { runBlocking { reactor.step() } }
        reactor.clearStepInputs()
    }

    @Test
    fun `a frame handler is refused in a stepped session, which streams nothing`() {
        val reactor = Reactor(ReactorOptions(jwt = "t", generation = Generation.STEPPED), Dispatchers.Unconfined)
        val error =
            assertThrows(InvalidStateException::class.java) {
                reactor.trackOwner.setVideoHandler("main_video") { }
            }
        assertTrue(error.message!!.contains("result.track(\"main_video\")"))
        assertThrows(InvalidStateException::class.java) { reactor.trackOwner.setAudioHandler("main_audio") { } }
    }

    private suspend inline fun <reified E : Throwable> awaitFailure(deferred: Deferred<Result<*>>): E {
        val error = deferred.await().exceptionOrNull()
        assertTrue("expected ${E::class.simpleName}, got $error", error is E)
        return error as E
    }

    /**
     * A result with one video track (a 2x2 RGB frame, tagged and timed), one audio track (one
     * stereo sample pair, untagged) and one kept file. Ignores the pointer it is given.
     */
    private class FakeResult(
        private val videoData: ByteArray = ByteArray(12) { (it + 1).toByte() },
        private val videoKind: String = "video",
        private val videoTag: ByteArray? = byteArrayOf(9, 8, 7),
        private val videoDtype: String = "uint8",
    ) : StepResultSource {
        private val audioData = byteArrayOf(2, 1, 4, 3)

        override fun step(result: Long) = 7L

        override fun elapsed(result: Long) = 0.25

        override fun error(result: Long): String? = null

        override fun fileCount(result: Long) = 1

        override fun fileName(
            result: Long,
            file: Int,
        ) = "depth.npy"

        override fun fileContentType(
            result: Long,
            file: Int,
        ): String? = null

        override fun fileSize(
            result: Long,
            file: Int,
        ) = 64L

        override fun trackCount(result: Long) = 2

        override fun trackName(
            result: Long,
            track: Int,
        ) = if (track == 0) "main_video" else "main_audio"

        override fun trackKind(
            result: Long,
            track: Int,
        ) = if (track == 0) videoKind else "audio"

        override fun trackRate(
            result: Long,
            track: Int,
        ) = if (track == 0) 30.0 else 48000.0

        override fun frameCount(
            result: Long,
            track: Int,
        ) = 1

        override fun frameEncoding(
            result: Long,
            track: Int,
            frame: Int,
        ) = "raw"

        override fun frameDtype(
            result: Long,
            track: Int,
            frame: Int,
        ) = if (track == 0) videoDtype else "int16"

        override fun frameShape(
            result: Long,
            track: Int,
            frame: Int,
        ) = if (track == 0) intArrayOf(2, 2, 3) else intArrayOf(2, 1)

        override fun frameData(
            result: Long,
            track: Int,
            frame: Int,
        ) = (if (track == 0) videoData else audioData).copyOf()

        override fun frameMetadata(
            result: Long,
            track: Int,
            frame: Int,
        ) = if (track == 0) videoTag?.copyOf() else null

        override fun frameCaptureTimeUs(
            result: Long,
            track: Int,
            frame: Int,
        ) = if (track == 0) 424242L else null
    }
}
