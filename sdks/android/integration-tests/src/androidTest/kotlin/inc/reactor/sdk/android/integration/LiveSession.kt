package inc.reactor.sdk.android.integration

import inc.reactor.sdk.android.ConnectionStatus
import inc.reactor.sdk.android.Reactor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before

/**
 * One connected client for the whole run.
 *
 * Not one per test, which is what this suite did first and what the platform refused: there is a
 * ceiling on how many sessions may exist at once, and a suite that needs a dozen to check a dozen
 * things meets it on a busy afternoon however politely it paces. Everything below the session is
 * the same code either way, so sharing costs no coverage.
 *
 * Shared across *classes* rather than per class, which is one step further than the Java suite
 * goes, because an Android instrumentation run is a single process and the quota does not care
 * how the run is divided into classes.
 *
 * Sharing has its own cost, and it shows up as a cascade: a run takes minutes, a live session can
 * go away inside them for reasons that are nothing to do with this SDK, and every test after that
 * point fails with `INVALID_STATE ... status: disconnected`. One dropped session reads as several
 * broken features. [ensureHealthy] puts it back and counts how often it had to — because a
 * fixture that quietly repairs itself can hide the thing it is meant to catch, and a run that
 * repairs on every test should be read as a failure of something even when every assertion passes.
 */
internal object LiveSession {
    private var shared: Reactor? = null

    /** How many times the session had to be put back. Reported at the end of the run, always. */
    private var reopened = 0

    /**
     * The shared client, connected and READY.
     *
     * Reconnect first when it is not: the session may still exist on the platform, and rejoining
     * it costs nothing against the ceiling that made this fixture shared in the first place. A new
     * client only when that fails.
     */
    suspend fun ensureHealthy(): Reactor {
        val existing = shared
        if (existing != null && existing.status.value == ConnectionStatus.READY) return existing

        reopened++
        val was = existing?.status?.value?.toString() ?: "never opened"
        println("the shared session was $was — putting it back ($reopened so far in this run)")

        if (existing != null) {
            try {
                withTimeout(90_000) { existing.reconnect() }
                if (existing.status.value == ConnectionStatus.READY) return existing
            } catch (reconnectFailed: Throwable) {
                println("  reconnect did not take: ${reconnectFailed.message}")
            }
            existing.close()
            shared = null
        }

        return Live.connected().also {
            Live.awaitReady(it)
            shared = it
        }
    }

    /** Called once by [LiveRunner] when the run ends. */
    fun closeIfOpen() {
        // Always printed, not only when it happened. Zero is the number that says the session
        // held, and a reader who has to notice the absence of a line will not.
        println("sessions re-established during this run: $reopened")
        val open = shared ?: return
        shared = null
        runBlocking {
            // Disconnect before close: a creator that goes away without disconnecting orphans the
            // session, and the next run of this suite is the one that pays for it.
            runCatching { withTimeout(30_000) { open.disconnect() } }
        }
        open.close()
    }
}

/** A test class that wants the shared session, repaired before each test. */
internal abstract class LiveFixture {
    protected lateinit var reactor: Reactor

    @Before
    fun takeTheSharedSession() {
        runBlocking { reactor = LiveSession.ensureHealthy() }
    }
}
