package inc.reactor.examples

import inc.reactor.sdk.JsonValue
import inc.reactor.sdk.VideoFrame
import inc.reactor.sdk.kotlin.ReactorClient
import inc.reactor.sdk.kotlin.reactorOptions
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

/**
 * Example 01, in Kotlin — the same scenario, through the facade.
 *
 * ```
 * export REACTOR_API_KEY=rk_...
 * mise run example:kotlin
 *
 * REACTOR_SHOW=1   show the video in a window
 * ```
 *
 * The one Kotlin example, deliberately. The other seven share every code path with their Java twins
 * — the facade forwards, it does not re-implement — so twins of them would be seven more files to
 * keep in step and no line of the SDK they would reach that this does not.
 *
 * What is worth seeing here is the difference: `connect()` suspends rather than returning a future
 * to join, the options come from a builder block, and the frame handler is still a callback. That
 * last one is not an omission. Frames arrive on the FFI's delivery thread and blocking there is the
 * backpressure; a flow would put a buffer in between, which is the trade the FFI's own header warns
 * against. `videoFrames()` exists for when a caller wants it anyway, and says as much.
 */
object KotlinConnectAndReceive {

    private val MODEL = Examples.model("reactor/helios")
    private const val OUTPUT_TRACK = "main_video"
    private const val PROMPT = "a forest at dawn, sunbeams through the canopy"

    @JvmStatic
    fun main(args: Array<String>): Unit = runBlocking {
        val jwt = ReactorClient.fetchJwt(Examples.apiUrl(), Examples.apiKey())

        // use, so the session is left even if the body throws. A creator that goes away without
        // disconnecting orphans the session, and the next run cannot start until that clears.
        ReactorClient.open(reactorOptions(Examples.apiUrl(), MODEL) { jwt(jwt) }).use { client ->
            Display.window("$MODEL · $OUTPUT_TRACK", Examples.show()).use { window ->
                client.java.onStatus { println("status: $it") }
                client.java.onError { println("error: $it") }
                client.java.onMessage { println("message: ${it.toJsonString()}") }

                client.connect()
                println("session: ${client.sessionId}")

                println(
                    "set_prompt -> ${client.sendCommand("set_prompt", JsonValue.`object`().put("prompt", PROMPT).build())}"
                )
                println("start -> ${client.sendCommand("start", JsonValue.`object`().build())}")

                val output = client.track(OUTPUT_TRACK)
                val frames = AtomicInteger()

                output.onVideoFrame { frame: VideoFrame ->
                    if (frames.incrementAndGet() == 1) {
                        println("first frame: ${frame.width()}x${frame.height()}")
                    }
                    // Copied here: the frame's pixels belong to the FFI and are gone when this
                    // returns.
                    window.submit(frame.toByteArray(), frame.width(), frame.height())
                }

                window.hold(Duration.ofSeconds(Examples.seconds(15)))
                println("frames: ${frames.get()}")

                client.disconnect()
            }
        }
    }
}
