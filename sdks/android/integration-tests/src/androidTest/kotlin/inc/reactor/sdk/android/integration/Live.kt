package inc.reactor.sdk.android.integration

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import inc.reactor.sdk.android.ConnectionStatus
import inc.reactor.sdk.android.Reactor
import inc.reactor.sdk.android.ReactorOptions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assume

/*
 * A real client, against a real model, over a real WebRTC connection, on a real device.
 *
 * Nothing here is faked. The unit suite proves the binding behaves against a library it can make
 * misbehave on purpose; this proves it behaves against the platform, which is the only place the
 * whole path exists — the coordinator, the codecs the fleet negotiates, the model contracts as
 * deployed rather than as declared in a manifest.
 */

/**
 * Configuration, quota pacing and the one decision a missing key forces.
 *
 * `reactor/echo` because it declares all four combinations of kind and direction: `webcam` and
 * `mic` sendonly, `main_video` and `main_audio` recvonly. No other published model exercises the
 * send path at all.
 */
internal object Live {
    /** The tracks echo declares, named here so a test reads as what it is checking. */
    const val VIDEO_IN: String = "webcam"
    const val AUDIO_IN: String = "mic"
    const val VIDEO_OUT: String = "main_video"
    const val AUDIO_OUT: String = "main_audio"

    private val args: Bundle
        get() = InstrumentationRegistry.getArguments()

    /**
     * An instrumentation argument, or null when it was not passed or was passed empty.
     *
     * Empty and absent are the same thing on purpose: the Gradle side defaults a missing property
     * to `""` rather than dropping the key, because `am instrument` has no way to express "this
     * argument exists but has no value" that survives the round trip intact.
     */
    private fun arg(name: String): String? = args.getString(name)?.takeIf { it.isNotBlank() }

    val apiUrl: String get() = arg("reactorApiUrl") ?: "https://api.reactor.inc"

    val model: String get() = arg("reactorModel") ?: "reactor/echo"

    private val inCi: Boolean get() = arg("ci") == "true"

    /**
     * The key, a skip, or a failure — and which of the last two is the point of this function.
     *
     * Locally, no key means these are skipped: a contributor should not need production
     * credentials to run the unit suite. In CI it throws instead, because this suite gates CI and
     * the release and cannot pass without reaching the platform. **A required check that goes
     * green because a secret was missing is worse than one that fails**, and on Android there is
     * a second way to be silently green that the other SDKs do not have — no device attached — so
     * the mise task checks for one before Gradle is ever invoked.
     */
    fun apiKey(): String =
        arg("reactorApiKey") ?: if (inCi) {
            error(
                "INTEGRATION_TESTS_REACTOR_API_KEY reached the device empty. This suite gates CI " +
                    "and the release and cannot pass without reaching the platform, so a missing " +
                    "secret fails here rather than passing quietly. If the secret is set, check " +
                    "that the mise task is forwarding it as an instrumentation argument — the " +
                    "device does not inherit the runner's environment.",
            )
        } else {
            Assume.assumeTrue(
                "INTEGRATION_TESTS_REACTOR_API_KEY is not set — skipping the live suite",
                false,
            )
            error("unreachable")
        }

    /**
     * Sessions this suite may be creating at once.
     *
     * The model's session quota is shared with every other binding's suite and with anyone running
     * an example. Creating them one at a time costs a little wall clock and keeps this suite from
     * being the reason another one sees a 429.
     */
    private val connecting = Mutex()

    /**
     * The shortest gap between two session creations, anywhere in this process.
     *
     * Two quotas sit behind this and they are not the same thing: a rate limit on creating
     * sessions, and a ceiling on how many may exist at once. Pacing answers the first. The second
     * is why this suite shares one session rather than opening one per test — a suite that needs a
     * dozen sessions to check a dozen things will meet that ceiling on a busy afternoon however
     * politely it asks for them.
     */
    private const val SESSION_INTERVAL_MS = 700L

    private var lastCreatedAt = 0L

    /**
     * Opens a client and connects it, one session at a time.
     *
     * The dispatcher is [kotlinx.coroutines.Dispatchers.Unconfined] rather than the production
     * default: `Dispatchers.Main.immediate` needs a looper the instrumentation thread does not
     * have, and a test that deadlocks on its own event delivery proves nothing about the platform.
     */
    suspend fun connected(sessionId: String? = null): Reactor =
        connecting.withLock {
            pace()
            val reactor =
                Reactor(
                    ReactorOptions(apiUrl = apiUrl, apiKey = apiKey()),
                    dispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
                )
            try {
                withTimeout(90_000) { reactor.connect(model, sessionId) }
                reactor
            } catch (failed: Throwable) {
                reactor.close()
                throw failed
            }
        }

    /** Waits for this process's turn to create a session. */
    private suspend fun pace() {
        val earliest = lastCreatedAt + SESSION_INTERVAL_MS
        val wait = earliest - System.currentTimeMillis()
        if (wait > 0) kotlinx.coroutines.delay(wait)
        lastCreatedAt = System.currentTimeMillis()
    }

    /** Waits for a client to reach READY, or fails saying where it stopped instead. */
    suspend fun awaitReady(reactor: Reactor) {
        try {
            withTimeout(90_000) { reactor.status.first { it == ConnectionStatus.READY } }
        } catch (timedOut: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError(
                "waited 90s for READY and the session stopped at ${reactor.status.value}",
                timedOut,
            )
        }
    }

    /**
     * Waits for something a live session produces when it is ready to.
     *
     * Polling rather than a flow, because most of what these tests wait for — a frame counter, a
     * handler having fired — is not something the SDK exposes as one.
     */
    suspend fun await(
        what: String,
        timeoutMs: Long = 30_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            kotlinx.coroutines.delay(100)
        }
        throw AssertionError("waited ${timeoutMs / 1000}s for $what and it never happened")
    }
}
