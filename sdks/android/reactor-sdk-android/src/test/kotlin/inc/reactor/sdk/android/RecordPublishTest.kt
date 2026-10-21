package inc.reactor.sdk.android

import inc.reactor.sdk.android.internal.recordPublish
import inc.reactor.sdk.android.internal.recordUnpublish
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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
    private val lock = Mutex()

    @Test
    fun `a cancelled caller still records a publish that then succeeds`() =
        runTest {
            val answer = CompletableDeferred<Unit>()
            var outcome: Throwable? = null
            val caller =
                launch {
                    outcome = runCatching { recordPublish(states, "webcam", lock, { { true } }) { answer.await() } }.exceptionOrNull()
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
                    runCatching { recordPublish(states, "webcam", lock, { { true } }) { answer.await() } }
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
            val thrown = runCatching { recordPublish(states, "webcam", lock, { { false } }) { } }.exceptionOrNull()
            assertTrue("got $thrown", thrown is DisconnectedException)
            assertNull(states["webcam"])
        }

    @Test
    fun `a failed publish is cleared and rethrown`() =
        runTest {
            val thrown = runCatching { recordPublish(states, "webcam", lock, { { true } }) { error("refused") } }.exceptionOrNull()
            assertEquals("refused", thrown?.message)
            assertNull(states["webcam"])
        }

    @Test
    fun `a failing publish cannot erase one that succeeded alongside it`() =
        runTest {
            val first = CompletableDeferred<Unit>()
            var calls = 0
            var secondOutcome: Throwable? = null
            val a =
                launch {
                    recordPublish(states, "webcam", lock, { { true } }) {
                        calls++
                        first.await()
                    }
                }
            val b =
                launch {
                    secondOutcome =
                        runCatching {
                            recordPublish(states, "webcam", lock, { { true } }) {
                                calls++
                                error("already published")
                            }
                        }.exceptionOrNull()
                }
            runCurrent()
            first.complete(Unit)
            a.join()
            b.join()
            assertEquals(PublishState.PUBLISHED, states["webcam"])
            assertEquals("the second publish finds the track published and does not ask again", 1, calls)
            assertNull(secondOutcome)
        }

    @Test
    fun `an unpublish during a publish is not undone by the publish's answer`() =
        runTest {
            val answer = CompletableDeferred<Unit>()
            val publishing = launch { recordPublish(states, "webcam", lock, { { true } }) { answer.await() } }
            runCurrent()
            var detached = false
            val unpublishing = launch { recordUnpublish(states, "webcam", lock) { detached = true } }
            runCurrent()
            val waited = !detached
            // Answered before asserting: the publish runs non-cancellably, so a failed assertion
            // with it still waiting would leave runTest waiting on it forever.
            answer.complete(Unit)
            assertTrue("the unpublish waits for the publish's answer", waited)

            publishing.join()
            unpublishing.join()
            assertTrue(detached)
            assertNull(states["webcam"])
        }

    @Test
    fun `a failed unpublish keeps the state, so the retry still detaches`() =
        runTest {
            states["webcam"] = PublishState.PUBLISHED
            val thrown = runCatching { recordUnpublish(states, "webcam", lock) { error("refused") } }.exceptionOrNull()
            assertEquals("refused", thrown?.message)
            assertEquals(PublishState.PUBLISHED, states["webcam"])
        }

    /**
     * A status change clears the states under their own monitor, so the readiness check and the
     * PUBLISHED write must be under it too — otherwise the clear can land between them.
     */
    @Test
    fun `readiness is checked under the states' monitor, together with the write`() =
        runTest {
            var heldDuringCheck = false
            recordPublish(states, "webcam", lock, {
                {
                    heldDuringCheck = Thread.holdsLock(states)
                    true
                }
            }) { }
            assertTrue(heldDuringCheck)
            assertEquals(PublishState.PUBLISHED, states["webcam"])
        }

    /** Started too early, a queued publish would be judged by a session it never published on. */
    @Test
    fun `the readiness check starts only once the lock is held`() =
        runTest {
            val first = CompletableDeferred<Unit>()
            var generation = 0
            var firstOutcome: Throwable? = null
            val a =
                launch {
                    firstOutcome =
                        runCatching {
                            recordPublish(states, "webcam", lock, {
                                val g = generation;
                                { generation == g }
                            }) { first.await() }
                        }.exceptionOrNull()
                }
            runCurrent()
            var secondOutcome: Throwable? = null
            val b =
                launch {
                    secondOutcome =
                        runCatching {
                            recordPublish(states, "webcam", lock, {
                                val g = generation;
                                { generation == g }
                            }) { }
                        }.exceptionOrNull()
                }
            runCurrent()
            // The session drops and comes back while the second publish waits for the lock.
            generation += 1
            states.clear()
            first.complete(Unit)
            a.join()
            b.join()
            assertTrue("the first was answered for the session that left", firstOutcome is DisconnectedException)
            assertNull("the second publish ran on the new session and must succeed", secondOutcome)
            assertEquals(PublishState.PUBLISHED, states["webcam"])
        }
}
