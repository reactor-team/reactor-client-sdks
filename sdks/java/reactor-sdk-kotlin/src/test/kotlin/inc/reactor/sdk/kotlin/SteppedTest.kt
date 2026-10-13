package inc.reactor.sdk.kotlin

import inc.reactor.sdk.FakeClients
import inc.reactor.sdk.Generation
import inc.reactor.sdk.InvalidStateException
import inc.reactor.sdk.JsonValue
import inc.reactor.sdk.StepCommand
import inc.reactor.sdk.StepRefusedException
import inc.reactor.sdk.internal.FakeNativeLibrary
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Stepped generation through the facade: the suspend step, its refusals, the accepted flow. */
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
        assertEquals(2L, result.index())
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
    fun `the step accepted flow carries each index`() = runBlocking {
        val collecting = async(Dispatchers.Default) { client.stepAcceptedFlow().take(2).toList() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (FakeClients.controlHandlerCount(client.java) == 0 && System.nanoTime() < deadline) {
            Thread.sleep(1)
        }
        fake.fireStepAccepted(1)
        fake.fireStepAccepted(2)
        assertEquals(listOf(1L, 2L), collecting.await())
    }

    private fun awaitStep() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!fake.hasPendingStep()) {
            check(System.nanoTime() < deadline) { "the step never reached the library" }
            Thread.sleep(1)
        }
    }
}
