package inc.reactor.sdk.android.endurance

import androidx.test.ext.junit.runners.AndroidJUnit4
import inc.reactor.sdk.android.PublishState
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Publish and unpublish, and nothing else in the loop.
 *
 * Session churn mixes publish, frames, commands and unpublish every cycle, so a leak specific to
 * just one of them shows up as a small contribution to a trend several operations are feeding. On
 * its own it is either flat or it is not — and the report names the culprit rather than the mix.
 */
@RunWith(AndroidJUnit4::class)
internal class PublishChurnTest {
    @Test
    fun publishChurn() =
        runBlocking {
            val apiKey = Endurance.apiKey()
            Endurance.run(
                "publish-churn",
                "publish then unpublish, nothing else: no frames, no commands, no reconnects.",
                liveClientsBaselineZero = false,
            ) { run ->
                val reactor = Endurance.connected(apiKey)
                try {
                    val input = reactor.track("webcam")

                    while (run.keepGoing()) {
                        input.publish()
                        input.unpublish()

                        // This scenario's own invariant: the state it churns has to come back each
                        // time.
                        if (input.publishState != PublishState.UNPUBLISHED) {
                            throw AssertionError(
                                "a track stayed published after unpublish: ${input.publishState}",
                            )
                        }
                        run.endOfCycle()
                    }
                } finally {
                    runCatching { reactor.disconnect() }
                    reactor.close()
                }
            }
        }
}
