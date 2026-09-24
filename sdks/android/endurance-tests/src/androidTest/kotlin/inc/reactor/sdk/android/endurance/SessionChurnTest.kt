package inc.reactor.sdk.android.endurance

import androidx.test.ext.junit.runners.AndroidJUnit4
import inc.reactor.sdk.android.ConnectionStatus
import inc.reactor.sdk.android.VideoFrame
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * One client, connected once, repeating everything a session does without ever disconnecting.
 *
 * For a leak in a single operation, which a connect-and-close cycle dilutes into noise.
 */
@RunWith(AndroidJUnit4::class)
internal class SessionChurnTest {
    @Test
    fun sessionChurn() =
        runBlocking {
            val apiKey = Endurance.apiKey()
            Endurance.run(
                "session-churn",
                "One client, connected once: publish, subscribe, push a frame, send a command, " +
                    "unpublish — repeated without ever disconnecting.",
                liveClientsBaselineZero = false,
            ) { run ->
                val reactor = Endurance.connected(apiKey)
                try {
                    val input = reactor.track("webcam")
                    val output = reactor.track("main_video")
                    val frame = ByteBuffer.allocateDirect(320 * 240 * 4)

                    while (run.keepGoing()) {
                        val received = AtomicInteger()
                        output.onFrame { _: VideoFrame -> received.incrementAndGet() }

                        input.publish()
                        frame.rewind()
                        input.pushFrame(frame, 320, 240)
                        reactor.sendCommand("get_status")
                        input.unpublish()
                        // Destruction order is explicit: the handler goes before anything else in
                        // the cycle is released. A client released while a handler is still
                        // registered skips the deregistration this cycle exists to exercise.
                        output.removeHandlers()

                        // This scenario's own invariant: every operation it starts is answered, so
                        // nothing may be left waiting at the end of a cycle.
                        if (reactor.status.value != ConnectionStatus.READY) {
                            throw AssertionError("the session left READY mid-run: ${reactor.status.value}")
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
