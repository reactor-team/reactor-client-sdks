package inc.reactor.sdk.android.endurance

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The same shape as video publish steady, on the audio path.
 *
 * Kept separate rather than parameterised: video and audio share nothing below `pushFrame` — a
 * different native entry point, a different encoder, a different buffer discipline — so a leak in
 * one would be invisible in the other's run.
 */
@RunWith(AndroidJUnit4::class)
internal class AudioPublishSteadyTest {
    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 1

        /** 10ms per push, the frame size WebRTC's pipeline is built around. */
        const val SAMPLES_PER_CHANNEL = 480
        const val FRAME_INTERVAL_MS = 10L
    }

    @Test
    fun audioPublishSteady() =
        runBlocking {
            val apiKey = Endurance.apiKey()
            Endurance.run(
                "audio-publish-steady",
                "publish once and hold it, pushing 10ms PCM buffers for the whole run — no pause, " +
                    "no unpublish, no reconnect.",
                liveClientsBaselineZero = false,
            ) { run ->
                val reactor = Endurance.connected(apiKey)
                try {
                    val input = reactor.track("mic")
                    input.publish()
                    val pcm =
                        ByteBuffer
                            .allocateDirect(SAMPLES_PER_CHANNEL * CHANNELS * 2)
                            .order(ByteOrder.nativeOrder())

                    while (run.keepGoing()) {
                        pcm.rewind()
                        input.pushFrame(pcm, SAMPLES_PER_CHANNEL, SAMPLE_RATE, CHANNELS)
                        delay(FRAME_INTERVAL_MS)

                        if (!input.published) {
                            throw AssertionError("the held track stopped being published mid-run")
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
