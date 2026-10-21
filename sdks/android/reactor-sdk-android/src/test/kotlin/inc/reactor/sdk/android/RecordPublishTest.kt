package inc.reactor.sdk.android

import inc.reactor.sdk.android.internal.recordPublish
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The publish bookkeeping: the recorded state follows what the native publish did, including when
 * the caller stops waiting for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordPublishTest {
    private val states = mutableMapOf<String, PublishState>()

    @Test
    fun `a cancelled caller still records a publish that then succeeds`() =
        runTest {
            val answer = CompletableDeferred<Unit>()
            var outcome: Throwable? = null
            val caller =
                launch {
                    outcome = runCatching { recordPublish(states, "webcam", { true }) { answer.await() } }.exceptionOrNull()
                }
            runCurrent()
            assertEquals(PublishState.PUBLISHING, states["webcam"])

            caller.cancel()
            runCurrent()
            // Still waiting on the native answer, so still publishing rather than forgotten.
            assertEquals(PublishState.PUBLISHING, states["webcam"])

            answer.complete(Unit)
            caller.join()
            assertEquals(PublishState.PUBLISHED, states["webcam"])
            assertTrue("the caller sees its cancellation", outcome is CancellationException)
        }

    @Test
    fun `a cancelled caller still clears a publish that then fails`() =
        runTest {
            val answer = CompletableDeferred<Unit>()
            val caller =
                launch {
                    runCatching { recordPublish(states, "webcam", { true }) { answer.await() } }
                }
            runCurrent()
            caller.cancel()
            runCurrent()

            answer.completeExceptionally(IllegalStateException("refused"))
            caller.join()
            assertNull(states["webcam"])
        }

    @Test
    fun `a publish answered after the session left ready fails as disconnected`() =
        runTest {
            val thrown = runCatching { recordPublish(states, "webcam", { false }) { } }.exceptionOrNull()
            assertTrue("got $thrown", thrown is DisconnectedException)
            assertNull(states["webcam"])
        }

    @Test
    fun `a failed publish is cleared and rethrown`() =
        runTest {
            val thrown = runCatching { recordPublish(states, "webcam", { true }) { error("refused") } }.exceptionOrNull()
            assertEquals("refused", thrown?.message)
            assertNull(states["webcam"])
        }
}
