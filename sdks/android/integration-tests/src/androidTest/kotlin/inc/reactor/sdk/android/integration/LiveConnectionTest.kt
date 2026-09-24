package inc.reactor.sdk.android.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import inc.reactor.sdk.android.ConnectionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tests that need their own session, and therefore pay for one.
 *
 * Kept apart from [LiveFixture]'s shared client on purpose: each of these is *about* connecting,
 * so borrowing an already-connected session would test nothing. They are few because each one
 * costs a session against a quota shared with every other binding's suite.
 */
@RunWith(AndroidJUnit4::class)
internal class LiveConnectionTest {
    /** An API key handed straight to the client, which is the development path the SDK documents. */
    @Test
    fun anApiKeyConnects() =
        runBlocking {
            val reactor = Live.connected()
            try {
                Live.awaitReady(reactor)
                assertNotNull(reactor.sessionId.value)
            } finally {
                runCatching { reactor.disconnect() }
                reactor.close()
            }
        }

    /**
     * A second client adopting the first's session by id.
     *
     * The multi-connection path: the session exists once, and the second client joins rather than
     * creating. Two sessions would be a quota problem *and* a wrong answer.
     */
    @Test
    fun aSecondClientAdoptsTheSession() =
        runBlocking {
            val first = Live.connected()
            try {
                Live.awaitReady(first)
                val sessionId = first.sessionId.value
                assertNotNull("the first client must have a session to adopt", sessionId)

                val second = Live.connected(sessionId)
                try {
                    Live.awaitReady(second)
                    assertEquals(
                        "the second client must be in the same session, not a new one",
                        sessionId,
                        second.sessionId.value,
                    )
                } finally {
                    // Close only. Disconnecting here would end the session under the first client,
                    // which is the whole point of adopting rather than creating.
                    second.close()
                }
            } finally {
                runCatching { first.disconnect() }
                first.close()
            }
        }

    /**
     * Closing without disconnecting still releases the handle, and the client refuses afterwards.
     *
     * The orphaned-session cost is real and documented; what is checked here is that the *binding*
     * does not leave the caller holding something that looks usable.
     */
    @Test
    fun aClosedClientRefusesFurtherWork() =
        runBlocking {
            val reactor = Live.connected()
            Live.awaitReady(reactor)
            runCatching { reactor.disconnect() }
            reactor.close()

            assertTrue(
                "a closed client must refuse rather than answer",
                runCatching { reactor.stats() }.isFailure,
            )
            assertEquals(ConnectionStatus.DISCONNECTED, reactor.status.value)
        }
}
