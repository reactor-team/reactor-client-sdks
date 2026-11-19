package inc.reactor.sdk.android.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import inc.reactor.sdk.android.Generation
import inc.reactor.sdk.android.GenerationModeMismatchException
import inc.reactor.sdk.android.MessageTooLargeException
import inc.reactor.sdk.android.Reactor
import inc.reactor.sdk.android.SessionTerminalException
import inc.reactor.sdk.android.StepCodec
import inc.reactor.sdk.android.StepResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/**
 * Scenario 09: stepped generation against a real runtime, as every other binding's suite runs it.
 *
 * `reactor/echo` always runs: a stepped connect to it must step or fail, never stream. Echo steps
 * from 1.10.0; an older echo drives its own `run()`, and a runtime refuses that stepped start (or, if
 * it ignores the mode, the client fails it typed). Until 1.10.0 is deployed everywhere this suite runs,
 * the test accepts either. The generic cases need a model with stepped generation — set
 * REACTOR_STEPPED_MODEL_NAME (and REACTOR_STEPPED_INPUT_TRACK for the push) or they are skipped.
 *
 * Each test pays for its own session, because each is about how a session starts.
 */
@RunWith(AndroidJUnit4::class)
internal class LiveSteppedTest {
    @get:Rule
    public val timeout: Timeout = Timeout.seconds(240)

    @Test
    fun aSteppedConnectToEchoStepsOrFails() =
        runBlocking {
            val reactor =
                try {
                    Live.connected(generation = Generation.STEPPED)
                } catch (refused: GenerationModeMismatchException) {
                    // A runtime that ignores the mode streams, and the client fails the connect.
                    assertEquals("GENERATION_MODE_MISMATCH", refused.code)
                    return@runBlocking
                } catch (refused: SessionTerminalException) {
                    // An echo before 1.10.0 drives its own run(), the runtime refuses the stepped
                    // start, and the platform reports the session closed.
                    assertEquals("SESSION_TERMINAL", refused.code)
                    return@runBlocking
                }
            try {
                Live.awaitReady(reactor)
                // Echo from 1.10.0 steps: the pushed webcam frame comes back inverted on main_video.
                val webcam = reactor.track(Live.VIDEO_IN)
                webcam.publish()
                webcam.pushFrame(solidBgra(64, 64, ECHO_IN), 64, 64)
                val result = reactor.step(state = """{"effect": "invert"}""")
                assertNull(result.error)

                // The frame travels encoded both ways when both ends share a codec (H.264 by
                // default), so the inverted pixel comes back close, not exact.
                assertEchoed(result, tolerance = ENCODED_TOLERANCE)
            } finally {
                runCatching { reactor.disconnect() }
                reactor.close()
            }
        }

    @Test
    fun aSteppedSessionCarriesFramesInVp8() = carriesFramesInTheCodecItChose(listOf(StepCodec.VP8), "vp8")

    @Test
    fun aSteppedSessionWithEncodingOffCarriesRawFrames() = carriesFramesInTheCodecItChose(emptyList(), "raw")

    /**
     * The echo round trip with the step frames' codec chosen before connect: VP8 both ways (libvpx
     * is in every build on either end, unlike H.264), or encoding off so every frame travels raw. The client pushes BGRA and receives RGB either way.
     * An echo or a runtime that does not step is accepted, as in [aSteppedConnectToEchoStepsOrFails].
     */
    private fun carriesFramesInTheCodecItChose(
        codecs: List<StepCodec>,
        expected: String,
    ) = runBlocking {
        val reactor =
            try {
                Live.connected(generation = Generation.STEPPED) { it.setCodecPreference(codecs) }
            } catch (refused: GenerationModeMismatchException) {
                assertEquals("GENERATION_MODE_MISMATCH", refused.code)
                return@runBlocking
            } catch (refused: SessionTerminalException) {
                assertEquals("SESSION_TERMINAL", refused.code)
                return@runBlocking
            }
        try {
            Live.awaitReady(reactor)
            val webcam = reactor.track(Live.VIDEO_IN)
            webcam.publish()

            val encodings = reactor.stepFrameEncodings
            // A runtime from before encoded step frames answers without them, so every track
            // travels raw. Accepted, as an echo that does not step is, until a runtime with them
            // is deployed.
            assumeFalse(
                "the runtime offers no encoded step frames: $encodings",
                expected != "raw" && encodings.values.all { it == "raw" },
            )
            assertEquals(encodings.toString(), expected, encodings[Live.VIDEO_OUT])
            if (expected == "raw") {
                // With encoding off a raw input track may not be listed at all; whatever is, is raw.
                assertEquals(encodings.toString(), "raw", encodings[Live.VIDEO_IN] ?: "raw")
                assertTrue(encodings.toString(), encodings.values.all { it == "raw" })
            } else {
                assertEquals(encodings.toString(), expected, encodings[Live.VIDEO_IN])
            }
            val tolerance = if (expected == "raw") 0 else ENCODED_TOLERANCE

            webcam.pushFrame(solidBgra(64, 64, ECHO_IN), 64, 64)
            val result = reactor.step(state = """{"effect": "invert"}""")
            assertNull(result.error)
            assertEchoed(result, tolerance)

            // The next step's frames continue the same stream.
            webcam.pushFrame(solidBgra(64, 64, ECHO_IN), 64, 64)
            val again = reactor.step()
            assertEquals(result.step + 1, again.step)
            assertEchoed(again, tolerance)
        } finally {
            runCatching { reactor.disconnect() }
            reactor.close()
        }
    }

    /** One 64x64 frame on main_video whose centre is echo's inverse of [ECHO_IN], within [tolerance]. */
    private fun assertEchoed(
        result: StepResult,
        tolerance: Int,
    ) {
        val frame = result.track(Live.VIDEO_OUT).frames.single()
        assertTrue(frame.shape.contentEquals(intArrayOf(64, 64, 3)))
        val bytes = frame.data()
        bytes.position((32 * 64 + 32) * 3)
        val pixel = ByteArray(3).also { bytes.get(it) }
        val worst = pixel.indices.maxOf { kotlin.math.abs((pixel[it].toInt() and 0xFF) - (ECHO_OUT[it].toInt() and 0xFF)) }
        assertTrue(
            "centre ${pixel.map { it.toInt() and 0xFF }} is $worst off ${ECHO_OUT.map { it.toInt() and 0xFF }}",
            worst <= tolerance,
        )
    }

    @Test
    fun stepsResolveInOrder() =
        withSteppedReactor { reactor ->
            val first = reactor.step(state = """{"prompt": "a lighthouse in a storm"}""")
            val second = reactor.step()
            assertEquals(first.step + 1, second.step)
            assertTrue(first.elapsed >= 0.0)
            assertNull(first.error)
            for (track in first.tracks) {
                assertTrue(track.rate >= 0.0)
                for (frame in track.frames) assertTrue(frame.shape.isNotEmpty())
            }
        }

    @Test
    fun pushedFramesRideTheNextStep() {
        val input = Live.steppedInputTrack
        assumeNotNull("REACTOR_STEPPED_INPUT_TRACK is not set", input)
        withSteppedReactor { reactor ->
            val source = reactor.track(input!!)
            source.publish()
            source.pushFrame(solidBgra(64, 64), 64, 64)
            val result = reactor.step(state = """{"prompt": "repaint it"}""")
            assertTrue(result.step >= 1)

            // A frame over the 64 MiB a chunked data channel carries. A runtime that takes pushed
            // frames gets each one as it is pushed, so this one is dropped at the push, raised as
            // an error event, and the step goes through. An older runtime takes them only with
            // the next step, which this frame then fails.
            source.pushFrame(solidBgra(OVERSIZED, OVERSIZED), OVERSIZED, OVERSIZED)
            try {
                val after = reactor.step()
                assertTrue(after.step > result.step)
            } catch (expected: MessageTooLargeException) {
                assertEquals("MESSAGE_TOO_LARGE", expected.code)
            }
        }
    }

    private fun withSteppedReactor(body: suspend (Reactor) -> Unit) {
        val model = Live.steppedModel
        assumeNotNull("REACTOR_STEPPED_MODEL_NAME is not set", model)
        runBlocking {
            val reactor = Live.connected(model = model!!, generation = Generation.STEPPED)
            try {
                Live.awaitReady(reactor)
                body(reactor)
            } finally {
                runCatching { reactor.disconnect() }
                reactor.close()
            }
        }
    }

    private fun solidBgra(
        width: Int,
        height: Int,
        bgra: ByteArray = BLUE,
    ): ByteBuffer {
        val pixels = ByteBuffer.allocateDirect(width * height * 4)
        while (pixels.hasRemaining()) pixels.put(bgra)
        pixels.flip()
        return pixels
    }

    private companion object {
        /** 4800 x 4800 RGB is 69 MB: past what one chunked data-channel message carries. */
        const val OVERSIZED = 4800

        /** How far per channel a pixel may drift through a lossy codec and back. */
        const val ENCODED_TOLERANCE = 8

        val BLUE = byteArrayOf(255.toByte(), 0, 0, 255.toByte())

        /** RGB (10, 20, 30) as BGRA, and what echo's `invert` makes of it, as RGB. */
        val ECHO_IN = byteArrayOf(30, 20, 10, 255.toByte())
        val ECHO_OUT = byteArrayOf(245.toByte(), 235.toByte(), 225.toByte())
    }
}
