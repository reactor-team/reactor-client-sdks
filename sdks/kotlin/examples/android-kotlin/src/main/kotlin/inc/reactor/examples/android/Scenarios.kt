package inc.reactor.examples.android

import android.content.Context
import android.net.Uri
import inc.reactor.sdk.*
import inc.reactor.sdk.android.uploadContent
import inc.reactor.sdk.android.createRecordingOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Seven scenarios launched from an Activity or lifecycle ViewModel. */
class Scenarios(private val context: Context, private val scope: CoroutineScope, private val preview: FrameView? = null) {
    fun runScenario(number: Int, model: String, token: String) {
        require(number in 1..7) { "scenario must be 1..7" }
        scope.launch {
            val client = Reactor(model, TokenProvider { token })
            try {
                client.connect()
                when (number) {
                    1 -> client.sendCommand("inspect", kotlinx.serialization.json.buildJsonObject {})
                    2 -> client.uploadContent(context, Uri.parse("content://example/input"), "example.txt")
                    3 -> client.tracks.withDirection(TrackDirection.RECVONLY).one().pause().also { client.track("input").resume() }
                    4 -> client.tracks.withDirection(TrackDirection.SENDONLY).one().publish()
                    5 -> println("session=${client.sessionId}; launch a second Activity-scoped client to adopt it")
                    6 -> client.requestClip(2.0).let { result -> result.copyTo { createRecordingOutput(context).outputStream() } }
                    7 -> client.tracks.withKind(TrackKind.VIDEO).one().onFrame { println("id=${it.frameId} ts=${it.timestampMicros}"); preview?.show(it) }
                }
            } finally {
                client.close()
            }
        }
    }
}
