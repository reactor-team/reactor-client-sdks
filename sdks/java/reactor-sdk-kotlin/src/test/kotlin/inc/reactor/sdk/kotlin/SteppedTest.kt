package inc.reactor.sdk.kotlin

import inc.reactor.sdk.EncodedFrame
import inc.reactor.sdk.FakeClients
import inc.reactor.sdk.Generation
import inc.reactor.sdk.InvalidStateException
import inc.reactor.sdk.JsonValue
import inc.reactor.sdk.StepCommand
import inc.reactor.sdk.StepFile
import inc.reactor.sdk.StepInput
import inc.reactor.sdk.StepNotReadyException
import inc.reactor.sdk.StepRefusedException
import inc.reactor.sdk.StepResultTooLargeException
import inc.reactor.sdk.internal.FakeNativeLibrary
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Stepped generation through the facade: the suspend step, its result, its refusals. */
class SteppedTest {

    private lateinit var fake: FakeNativeLibrary
    private lateinit var client: ReactorClient

    @BeforeEach
    fun openSteppedClient() {
        fake = FakeNativeLibrary()
        fake.tracksJson = FacadeFixture.TRACKS
        client =
            ReactorClient(
                FakeClients.openConnected(
                    fake,
                    reactorOptions("https://api.example.test", "owner/model") {
                        dispatcher { it.run() }
                        generation(Generation.STEPPED)
                    },
                )
            )
    }

    @AfterEach
    fun closeClient() {
        client.close()
        fake.close()
    }

    @Test
    fun `step suspends until the result arrives, and returns it copied out`() = runBlocking {
        val stepping =
            async(Dispatchers.Default) {
                client.step(
                    state = JsonValue.`object`().put("prompt", "a red fox").build(),
                    commands = listOf(StepCommand.of("advance")),
                )
            }
        awaitStep()
        fake.settleLastStep(
            2,
            listOf(
                FakeNativeLibrary.StepTrackSpec(
                    "screen_out",
                    "video",
                    listOf(
                        FakeNativeLibrary.StepFrameSpec(
                            "raw",
                            "uint8",
                            intArrayOf(1, 1, 3),
                            byteArrayOf(9, 8, 7),
                            null,
                            null,
                        )
                    ),
                )
            ),
        )
        val result = stepping.await()
        assertEquals(2L, result.step())
        assertArrayEquals(
            byteArrayOf(9, 8, 7),
            result.track("screen_out").frames().single().toByteArray(),
        )
        assertEquals(Generation.STEPPED, client.generation)
    }

    @Test
    fun `a refused step throws the binding's own type`() = runBlocking {
        val stepping = async(Dispatchers.Default) { runCatching { client.step() } }
        awaitStep()
        fake.failLastStep("""{"code":"step_refused","message":"no","recoverable":false}""")
        val failure = stepping.await().exceptionOrNull()
        assertEquals(StepRefusedException::class.java, failure?.javaClass)
    }

    @Test
    fun `a step the session was not ready for throws its own type`() = runBlocking {
        val stepping = async(Dispatchers.Default) { runCatching { client.step() } }
        awaitStep()
        fake.failLastStep("""{"code":"step_not_ready","message":"starting","recoverable":false}""")
        val failure = stepping.await().exceptionOrNull()
        assertEquals(StepNotReadyException::class.java, failure?.javaClass)
    }

    @Test
    fun `a step whose result is too large throws its own type`() = runBlocking {
        val stepping = async(Dispatchers.Default) { runCatching { client.step() } }
        awaitStep()
        fake.failLastStep(
            """{"code":"step_result_too_large","message":"big","recoverable":false}"""
        )
        val failure = stepping.await().exceptionOrNull()
        assertEquals(StepResultTooLargeException::class.java, failure?.javaClass)
    }

    @Test
    fun `step on a streamed client is refused`() {
        FakeNativeLibrary().use { streamedLibrary ->
            ReactorClient(
                    FakeClients.openConnected(
                        streamedLibrary,
                        reactorOptions("https://api.example.test", "owner/model") {
                            dispatcher { it.run() }
                        },
                    )
                )
                .use { streamed ->
                    assertThrows<InvalidStateException> { runBlocking { streamed.step() } }
                    streamed.clearStepInputs() // a no-op, not a refusal
                }
        }
    }

    @Test
    fun `a bare step sends nothing but the step, and a failed one returns its error and files`() =
        runBlocking {
            val stepping = async(Dispatchers.Default) { client.step() }
            awaitStep()
            assertNull(fake.lastStepState)
            assertEquals("[]", fake.lastStepCommands)
            fake.settleLastStep(
                3,
                emptyList(),
                0.0,
                listOf(FakeNativeLibrary.StepFileSpec("out.png", "image/png", 42)),
                "the model gave up",
            )
            val result = stepping.await()
            assertEquals("the model gave up", result.error().orElseThrow())
            assertEquals(listOf(StepFile("out.png", "image/png", 42)), result.files())
        }

    @Test
    fun `an encoded frame is pushed through the Java track, untouched`() {
        val camera = publishedCamera()
        camera.pushEncodedFrame(byteArrayOf(0, 0, 0, 1, 0x65), 4, 2, captureTimeUs = 9L)

        val push = fake.pushOrder.single() as FakeNativeLibrary.EncodedPush
        assertEquals("camera_in", push.track())
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65), push.data())
        assertEquals(4, push.width())
        assertEquals(2, push.height())
        assertNull(push.userData())
        assertTrue(push.hasCaptureTime())
        assertEquals(9L, push.captureTimeUs())
    }

    @Test
    fun `an encoded frame on an audio track is refused by the Java track`() {
        assertThrows<InvalidStateException> {
            client.track("mic_in").pushEncodedFrame(byteArrayOf(1), 2, 2)
        }
    }

    @Test
    fun `step media is pushed, encoded and raw, before the step goes out`() = runBlocking {
        publishedCamera()
        val stepping =
            async(Dispatchers.Default) {
                client.step(
                    media =
                        mapOf(
                            "camera_in" to
                                listOf(
                                    EncodedFrame.h264(byteArrayOf(0, 0, 0, 1, 0x65), 2, 2),
                                    StepInput.video(ByteArray(2 * 2 * 4), 2, 2),
                                )
                        )
                )
            }
        awaitStep()
        assertEquals(2, fake.pushOrder.size)
        assertTrue(fake.pushOrder[0] is FakeNativeLibrary.EncodedPush)
        assertEquals("camera_in", fake.pushOrder[1])
        fake.settleLastStep(1, emptyList())
        assertEquals(1L, stepping.await().step())
    }

    @Test
    fun `step media with a bad frame late in it pushes none of it`() = runBlocking {
        publishedCamera()
        assertThrows<InvalidStateException> {
            client.step(
                media =
                    mapOf(
                        "camera_in" to
                            listOf(
                                EncodedFrame.h264(byteArrayOf(0, 0, 0, 1, 0x65), 2, 2),
                                StepInput.video(ByteArray(3), 2, 2),
                            )
                    )
            )
        }
        assertTrue(fake.pushOrder.isEmpty())
        assertEquals(0, fake.stepCalls)
    }

    private fun publishedCamera(): ReactorTrack {
        val camera = client.track("camera_in")
        camera.java.publish()
        fake.settleLastCall(true, "{}", null)
        return camera
    }

    private fun awaitStep() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!fake.hasPendingStep()) {
            check(System.nanoTime() < deadline) { "the step never reached the library" }
            Thread.sleep(1)
        }
    }
}
