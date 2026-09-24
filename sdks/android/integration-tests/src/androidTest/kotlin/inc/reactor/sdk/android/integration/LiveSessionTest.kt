package inc.reactor.sdk.android.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import inc.reactor.sdk.android.ConnectionStatus
import inc.reactor.sdk.android.ReactorException
import inc.reactor.sdk.android.TrackDirection
import inc.reactor.sdk.android.TrackKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Connecting, what the session declares, commands, reconnection and statistics. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
internal class LiveSessionTest : LiveFixture() {
    @Test
    fun connectingReachesReady() {
        assertEquals(ConnectionStatus.READY, reactor.status.value)
        assertNotNull("a live session has an id", reactor.sessionId.value)
    }

    /**
     * Echo is the one published model that declares all four combinations, which is why this
     * suite uses it. If this fails, the model's contract changed and every send-path test below
     * is testing something else.
     */
    @Test
    fun theSessionDeclaresEveryCombination() {
        val tracks = reactor.tracks
        assertEquals(TrackKind.VIDEO, reactor.track(Live.VIDEO_IN).kind)
        assertEquals(TrackDirection.SENDONLY, reactor.track(Live.VIDEO_IN).direction)
        assertEquals(TrackKind.AUDIO, reactor.track(Live.AUDIO_IN).kind)
        assertEquals(TrackDirection.SENDONLY, reactor.track(Live.AUDIO_IN).direction)
        assertEquals(TrackKind.VIDEO, reactor.track(Live.VIDEO_OUT).kind)
        assertEquals(TrackDirection.RECVONLY, reactor.track(Live.VIDEO_OUT).direction)
        assertEquals(TrackKind.AUDIO, reactor.track(Live.AUDIO_OUT).kind)
        assertEquals(TrackDirection.RECVONLY, reactor.track(Live.AUDIO_OUT).direction)

        // Order is part of the contract: tracks() is indexed by position and every SDK promises
        // that position is the order the session declared them in.
        assertEquals(4, tracks.size)
    }

    /**
     * The refusal, against a real session rather than a fixture.
     *
     * The unit suite checks this against declarations it wrote itself. Here the declared set came
     * from the platform, so this is the one that would notice the binding reading it wrongly.
     */
    @Test
    fun anUndeclaredNameIsRefused() {
        val refused =
            runCatching { reactor.track("no_such_track") }.exceptionOrNull()
        assertNotNull("an undeclared name must be refused, not returned", refused)
        assertTrue(
            "the refusal must list the declared names, or it is not actionable: $refused",
            refused!!.message.orEmpty().contains(Live.VIDEO_OUT),
        )
    }

    @Test
    fun aCommandAnswersThroughItsOwnCall() =
        runBlocking {
            val reply = reactor.sendCommand("ping")
            assertNotNull("a command must answer through its own call", reply)
        }

    /**
     * A rejected command carries a typed code rather than failing as a generic error, and
     * `recoverable` is derived from that code.
     */
    @Test
    fun aRejectedCommandCarriesItsOwnCode() =
        runBlocking {
            val failure =
                runCatching { reactor.sendCommand("no_such_command") }.exceptionOrNull()
            assertTrue(
                "a rejected command must raise a ReactorException, got $failure",
                failure is ReactorException,
            )
            val error = failure as ReactorException
            assertTrue("the code must be set", error.code.isNotBlank())
            assertEquals("the operation must name the call", "send_command", error.operation)
        }

    /** Reconnect cycles the transport and keeps the session; disconnect is what ends it. */
    @Test
    fun reconnectKeepsTheSession() =
        runBlocking {
            val before = reactor.sessionId.value
            assertNotNull(before)
            reactor.reconnect()
            Live.awaitReady(reactor)
            assertEquals("reconnect must keep the session", before, reactor.sessionId.value)
        }

    @Test
    fun statisticsComeBack() =
        runBlocking {
            val stats = reactor.stats()
            assertNotNull("a live session reports statistics", stats)
        }

    /**
     * The model's own schema, from the platform.
     *
     * A successful completion whose payload will not parse is a decode failure, not an empty
     * object — substituting `{}` here would make a model that declares nothing indistinguishable
     * from a binding that cannot read what it declared.
     */
    @Test
    fun theModelDeclaresItsSchema() =
        runBlocking {
            val schema = reactor.requestSchema()
            assertTrue("a published model declares a non-empty schema", schema.isNotEmpty())
        }
}
