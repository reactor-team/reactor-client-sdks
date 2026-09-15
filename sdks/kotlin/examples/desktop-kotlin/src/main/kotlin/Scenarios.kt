package inc.reactor.examples.desktop

import inc.reactor.sdk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Seven parity scenarios for a desktop Kotlin application. */
fun runScenario(number: Int, model: String, token: String) = runBlocking {
    require(number in 1..7) { "scenario must be 1..7" }
    val client = Reactor(model, TokenProvider { token })
    val display = Display("$model · scenario $number")
    try {
        client.onEvent { println("event=$it") }
        client.connect()
        when (number) {
            1 -> {
                println(client.sendCommand("inspect", buildJsonObject {}))
                client.tracks.forEach { track -> track.onFrame { println("${track.name}: $it"); if (it is VideoFrame) display.show(it); display.pump() } }
            }
            2 -> {
                val file = client.uploadBytes("hello".encodeToByteArray(), "example.txt", "text/plain")
                println(client.sendCommand("upload", buildJsonObject { put("file", file.toJson()) }))
            }
            3 -> client.tracks.withDirection(TrackDirection.RECVONLY).one().also { it.pause(); it.resume() }
            4 -> client.tracks.withDirection(TrackDirection.SENDONLY).one().publish().pushFrame(
                VideoFrame(ByteArray(4 * 4 * 4), 4, 4, timestampMicros = timeMicros().toULong()),
            )
            5 -> println("session=${client.sessionId}; reconnect from a second client in the paired runner")
            6 -> client.requestClip(2.0).let { client.download(it, File("clip.mp4")) }
            7 -> client.tracks.withKind(TrackKind.VIDEO).one().onFrame { frame -> println("id=${frame.frameId} ts=${frame.timestampMicros} user=${frame.userData?.size}"); display.show(frame); display.pump() }
        }
    } finally {
        client.close()
    }
}
