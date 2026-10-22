package inc.reactor.sdk.android.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import inc.reactor.sdk.android.Generation
import inc.reactor.sdk.android.GenerationModeMismatchException
import inc.reactor.sdk.android.MessageTooLargeException
import inc.reactor.sdk.android.Reactor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/**
 * Scenario 09: stepped generation against a real runtime, as every other binding's suite runs it.
 *
 * `reactor/echo` streams, so it is the negative case: a runtime that does not start a stepped
 * session must fail `connect()` typed rather than leave a client waiting on steps. The positive
 * cases need a model with stepped generation — set
 * REACTOR_STEPPED_MODEL_NAME (and REACTOR_STEPPED_INPUT_TRACK for the push) or they are skipped.
 *
 * Each test pays for its own session, because each is about how a session starts.
 */
@RunWith(AndroidJUnit4::class)
internal class LiveSteppedTest {
    @get:Rule
    public val timeout: Timeout = Timeout.seconds(240)

    @Test
    fun aStreamingRuntimeFailsASteppedConnectTyped() =
        runBlocking {
            try {
                val reactor = Live.connected(generation = Generation.STEPPED)
                reactor.close()
                fail("a stepped connect to ${Live.model}, which streams, must throw")
            } catch (expected: GenerationModeMismatchException) {
                assertEquals("GENERATION_MODE_MISMATCH", expected.code)
            }
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

            // Proof the push rode the step whatever the model makes of it: a frame over the 64 MiB a
            // chunked data channel carries can only fail a step it went into. A push dropped on
            // the way would let it through.
            source.pushFrame(solidBgra(OVERSIZED, OVERSIZED), OVERSIZED, OVERSIZED)
            try {
                reactor.step()
                fail("a step carrying an oversized frame must throw")
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
    ): ByteBuffer {
        val pixels = ByteBuffer.allocateDirect(width * height * 4)
        while (pixels.hasRemaining()) pixels.put(BLUE)
        pixels.flip()
        return pixels
    }

    private companion object {
        /** 4800 x 4800 RGB is 69 MB: past what one chunked data-channel message carries. */
        const val OVERSIZED = 4800

        val BLUE = byteArrayOf(255.toByte(), 0, 0, 255.toByte())
    }
}
