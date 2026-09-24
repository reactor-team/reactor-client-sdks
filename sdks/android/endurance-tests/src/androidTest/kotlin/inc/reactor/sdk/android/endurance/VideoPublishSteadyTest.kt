package inc.reactor.sdk.android.endurance

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/**
 * Publish once and hold it, streaming for the whole run.
 *
 * The opposite shape from every churn scenario. A leak tied to *elapsed streaming time or frame
 * count* rather than to churn count would not necessarily show up in a churn scenario even run
 * forever, which makes this a distinct category rather than a variant.
 */
@RunWith(AndroidJUnit4::class)
internal class VideoPublishSteadyTest {
    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 240

        /** Roughly 30fps. Fast enough to be streaming, slow enough not to be a benchmark. */
        const val FRAME_INTERVAL_MS = 33L
    }

    @Test
    fun videoPublishSteady() =
        runBlocking {
            val apiKey = Endurance.apiKey()
            Endurance.run(
                "video-publish-steady",
                "publish once and hold it, pushing video for the whole run — no pause, no " +
                    "unpublish, no reconnect.",
                liveClientsBaselineZero = false,
            ) { run ->
                val reactor = Endurance.connected(apiKey)
                try {
                    val input = reactor.track("webcam")
                    input.publish()
                    // One buffer, reused. Allocating per frame would make the allocator the thing
                    // under test rather than the binding.
                    val frame = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4)

                    while (run.keepGoing()) {
                        frame.rewind()
                        input.pushFrame(frame, WIDTH, HEIGHT)
                        delay(FRAME_INTERVAL_MS)

                        // This scenario's own invariant: it publishes once, so a slot that stopped
                        // being published means something tore it down behind the caller's back.
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
