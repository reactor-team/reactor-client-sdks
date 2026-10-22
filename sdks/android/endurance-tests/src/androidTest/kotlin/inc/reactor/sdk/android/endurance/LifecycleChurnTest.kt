package inc.reactor.sdk.android.endurance

import androidx.test.ext.junit.runners.AndroidJUnit4
import inc.reactor.sdk.android.internal.Diagnostics
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A fresh client per full cycle — the only scenario that exercises the native handle's whole
 * lifetime, so a leak tied to *tearing a client down* shows up here and nowhere else.
 */
@RunWith(AndroidJUnit4::class)
internal class LifecycleChurnTest {
    @Test
    fun lifecycleChurn() =
        runBlocking {
            val apiKey = Endurance.apiKey()
            Endurance.run(
                "lifecycle-churn",
                "A new client every cycle: create, connect, send one command, disconnect, close. " +
                    "The only scenario that creates and destroys native handles.",
                liveClientsBaselineZero = true,
            ) { run ->
                while (run.keepGoing()) {
                    // Closed explicitly rather than by scope exit, so the moment it happens is this
                    // line and not wherever the last reference happened to be dropped.
                    val reactor = Endurance.connected(apiKey)
                    try {
                        reactor.sendCommand("get_status")
                        reactor.disconnect()
                    } finally {
                        reactor.close()
                    }
                    // Its own invariant, which belongs here rather than in the shared metrics: this
                    // scenario is the one that must bring the count back to zero every cycle.
                    if (Diagnostics.liveClients != 0) {
                        throw AssertionError("a client outlived its cycle: ${Diagnostics.liveClients}")
                    }
                    run.endOfCycle()
                }
            }
        }
}
