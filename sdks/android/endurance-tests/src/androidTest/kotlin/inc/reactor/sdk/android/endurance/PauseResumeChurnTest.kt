package inc.reactor.sdk.android.endurance

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pause and resume a recvonly track, and nothing else.
 *
 * Not hypothetical: the first real CI run of this scenario in another binding found a linear,
 * no-plateau RSS climb of 50% over five minutes that publish churn — same run, same process, same
 * fixture — did not show at all. Folded into the broad mix, that signal reads as "RSS grew a bit,
 * inconclusive" instead of naming pause and resume outright.
 */
@RunWith(AndroidJUnit4::class)
internal class PauseResumeChurnTest {
    @Test
    fun pauseResumeChurn() =
        runBlocking {
            val apiKey = Endurance.apiKey()
            Endurance.run(
                "pause-resume-churn",
                "pause then resume a recvonly track, nothing else in the loop.",
                liveClientsBaselineZero = false,
            ) { run ->
                val reactor = Endurance.connected(apiKey)
                try {
                    val output = reactor.track("main_video")

                    while (run.keepGoing()) {
                        output.pause()
                        output.resume()

                        // This scenario's own invariant: what it churns must not accumulate.
                        if (output.paused) {
                            throw AssertionError("a track stayed paused after resume")
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
