package inc.reactor.sdk.jvm

import inc.reactor.sdk.Reactor
import inc.reactor.sdk.TokenProvider
import inc.reactor.sdk.uploadBytes
import kotlinx.coroutines.runBlocking

/** Small blocking facade for Java desktop examples; never use it on Android UI threads. */
class JavaReactor(model: String, token: String) : AutoCloseable {
    private val delegate = Reactor(model, TokenProvider { token })

    fun connect() = runBlocking { delegate.connect() }

    fun runScenario(number: Int) = runBlocking {
        require(number in 1..7) { "scenario must be 1..7" }
        when (number) {
            1 -> delegate.sendCommand("inspect", kotlinx.serialization.json.buildJsonObject {})
            2 -> delegate.uploadBytes("hello".encodeToByteArray(), "example.txt", "text/plain")
            3 -> delegate.tracks.first().pause().also { delegate.tracks.first().resume() }
            4 -> delegate.tracks.first().publish()
            5 -> delegate.reconnect()
            6 -> delegate.requestClip(2.0)
            7 -> delegate.tracks.first().onFrame { }
        }
    }

    override fun close() = runBlocking { delegate.close() }
}
