package inc.reactor.sdk.android.examples

import inc.reactor.sdk.android.ConnectionStatus
import inc.reactor.sdk.android.Reactor
import inc.reactor.sdk.android.TrackDirection
import inc.reactor.sdk.android.TrackKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.ByteBuffer

/**
 * The seven scenarios, numbered as `sdks/python/examples/`.
 *
 * These are **parity requirements, not documentation**: an example missing from a binding is a
 * code path that binding has never run, and every bug this set has caught was caught by an
 * example and by no unit test — because a unit test agrees with the fixture you wrote for it.
 *
 * Each teaches exactly one thing, and each spells out the minimum its model needs at the top.
 * "The minimum" is per model and not optional: Helios stays silent until `set_prompt` and then
 * `start`; X2 needs a prompt but no start. That is the first place to look when nothing arrives.
 *
 * Model names are `owner/name` throughout. A bare name resolves under `reactor/`, so it works by
 * luck of ownership and answers 403 for anyone else's model.
 */
public object Scenarios {
    public const val HELIOS: String = "reactor/helios"
    public const val X2: String = "xmax/x2"

    private const val PROMPT = "a forest at dawn, sunbeams through the canopy"
    private const val OUTPUT_TRACK = "main_video"

    public data class Scenario(
        val number: String,
        val title: String,
        val model: String,
        val run: suspend (apiKey: String, log: Log, sink: FrameSink?) -> Unit,
    )

    public val all: List<Scenario> =
        listOf(
            Scenario("01", "Connect and receive", HELIOS, ::connectAndReceive),
            Scenario("02", "Upload an image", HELIOS, ::uploadImage),
            Scenario("03", "Pause and resume", HELIOS, ::pauseAndResume),
            Scenario("04", "Publish a track", X2, ::publishTrack),
            Scenario("05", "Two clients, one session", HELIOS, ::multiConnection),
            Scenario("06", "Record and download a clip", HELIOS, ::recordClip),
            Scenario("07", "Read the frame trailer", HELIOS, ::frameMetadata),
        )

    /**
     * 01 · Connect, send the model's first command, read the reply, count frames.
     *
     * The baseline the rest build on. What it teaches beyond "it connects" is that **nothing
     * arrives until the model's own minimum is met, and that minimum is per model** — Helios
     * emits no frames at all until it has a prompt and a `start`.
     */
    private suspend fun connectAndReceive(
        apiKey: String,
        log: Log,
        sink: FrameSink?,
    ) {
        withReactor(HELIOS, apiKey, log) { reactor ->
            reactor.status.first { it == ConnectionStatus.READY }
            reactor.track(OUTPUT_TRACK).onFrame(countFrames(log, sink))

            // The reply is read, not fire-and-forgotten: a command that failed says so here.
            val prompt = reactor.sendCommand("set_prompt", """{"prompt":"$PROMPT"}""")
            log("set_prompt → ${prompt.type ?: "acknowledged"}")
            reactor.sendCommand("start")
            log("started — frames should begin now")

            delay(15_000)
        }
    }

    /**
     * 02 · Upload a file and pass the `FileRef` into a command.
     *
     * The upload and the command are separate steps on purpose: the platform accepts the bytes
     * once, and the reference is what the model receives.
     */
    private suspend fun uploadImage(
        apiKey: String,
        log: Log,
        sink: FrameSink?,
    ) {
        withReactor(HELIOS, apiKey, log) { reactor ->
            reactor.status.first { it == ConnectionStatus.READY }
            reactor.track(OUTPUT_TRACK).onFrame(countFrames(log, sink))

            // A real app would take this from a picker — see uploadContent for a content:// URI.
            val bytes = ByteBuffer.allocateDirect(64 * 64 * 3)
            val ref = reactor.uploadBytes(bytes, name = "reference.rgb")
            log("uploaded ${ref.name} as ${ref.uploadId} (${ref.size} bytes)")

            reactor.sendCommand(
                "set_prompt",
                args = """{"prompt":"$PROMPT"}""",
                uploads = """{"reference": {"upload_id":"${ref.uploadId}","name":"${ref.name}",
                    "mime_type":"${ref.mimeType}","size":${ref.size}}}""",
            )
            reactor.sendCommand("start")
            delay(10_000)
        }
    }

    /**
     * 03 · Pause and resume a track.
     *
     * Nothing is generated while paused, and the only way to see that is a frame count that stops
     * moving — a paused video track shows a frozen frame, not a black one, so the screen looks
     * much the same either way.
     */
    private suspend fun pauseAndResume(
        apiKey: String,
        log: Log,
        sink: FrameSink?,
    ) {
        withReactor(HELIOS, apiKey, log) { reactor ->
            reactor.status.first { it == ConnectionStatus.READY }
            var frames = 0
            val track = reactor.track(OUTPUT_TRACK)
            track.onFrame { frame ->
                frames += 1
                sink?.render(frame)
            }

            reactor.sendCommand("set_prompt", """{"prompt":"$PROMPT"}""")
            reactor.sendCommand("start")
            delay(5_000)

            val beforePause = frames
            track.pause()
            log("paused at $beforePause frames")
            delay(3_000)
            log("after 3s paused: $frames frames (expect no change)")

            track.resume()
            log("resumed")
            delay(5_000)
            log("after resuming: $frames frames")
        }
    }

    /**
     * 04 · Publish a track and push tagged frames into it.
     *
     * Publishing is what puts a sender behind the slot — pushing before it completes is dropped
     * by the FFI, which is why this SDK refuses it. X2 edits the live track as soon as it has a
     * prompt, with no `start`.
     */
    private suspend fun publishTrack(
        apiKey: String,
        log: Log,
        sink: FrameSink?,
    ) {
        withReactor(X2, apiKey, log) { reactor ->
            reactor.status.first { it == ConnectionStatus.READY }
            reactor.tracks
                .withKind(TrackKind.VIDEO)
                .withDirection(TrackDirection.RECVONLY)
                .one()
                .onFrame(countFrames(log, sink))

            val input =
                reactor.tracks
                    .withKind(TrackKind.VIDEO)
                    .withDirection(TrackDirection.SENDONLY)
                    .one()
            input.publish()
            log("published '${input.name}'")

            reactor.sendCommand("set_prompt", """{"prompt":"$PROMPT"}""")

            val width = 640
            val height = 480
            val frame = ByteBuffer.allocateDirect(width * height * 4)
            repeat(150) { i ->
                // A tag rides with the frame and comes back in the trailer on the far side — see
                // 07. It is dropped unless the peer declared that it reads tags, so tagging is
                // safe whatever the model supports.
                input.pushFrame(frame, width, height, userData = "frame-$i".toByteArray())
                delay(33)
            }
            log("pushed 150 tagged frames")
        }
    }

    /**
     * 05 · Two clients on one session, the second adopting it by id.
     *
     * The observer never creates a session; it joins the creator's. Note that only the creator
     * disconnects — an observer calling disconnect() would end the session for both.
     */
    private suspend fun multiConnection(
        apiKey: String,
        log: Log,
        sink: FrameSink?,
    ) {
        withReactor(HELIOS, apiKey, log) { creator ->
            creator.status.first { it == ConnectionStatus.READY }
            creator.sendCommand("set_prompt", """{"prompt":"$PROMPT"}""")
            creator.sendCommand("start")

            val sessionId = creator.sessionId.first { it != null }!!
            log("creator's session: $sessionId")

            val observer =
                Reactor(
                    inc.reactor.sdk.android
                        .ReactorOptions(apiKey = apiKey),
                )
            try {
                observer.connect(HELIOS, sessionId = sessionId)
                observer.status.first { it == ConnectionStatus.READY }
                log("observer joined the same session")
                observer.track(OUTPUT_TRACK).onFrame(countFrames(log, sink))
                delay(10_000)
            } finally {
                // close() and *not* disconnect(): disconnecting ends the session server-side, and
                // the observer does not own it.
                observer.close()
                log("observer left; the session is still the creator's")
            }
        }
    }

    /**
     * 06 · Request a clip and download it.
     *
     * Readiness is in **media time**: the clip becomes ready because the model keeps generating,
     * so the wait is bounded on the session being alive rather than on a number. A snap clip's
     * window ends at *now*, so its boundary chunk is always the still-open one — waiting before
     * asking moves the target with you.
     */
    private suspend fun recordClip(
        apiKey: String,
        log: Log,
        sink: FrameSink?,
    ) {
        withReactor(HELIOS, apiKey, log) { reactor ->
            reactor.status.first { it == ConnectionStatus.READY }
            reactor.track(OUTPUT_TRACK).onFrame(countFrames(log, sink))
            reactor.sendCommand("set_prompt", """{"prompt":"$PROMPT"}""")
            reactor.sendCommand("start")

            delay(10_000)
            val clip = reactor.requestClip(durationSeconds = 5.0)
            log("clip requested: ${clip.playlistUrl}")

            val out = File.createTempFile("reactor-clip-", ".mp4")
            val downloaded =
                reactor.downloadClip(clip, out) { progress ->
                    log("segments ${progress.done}/${progress.total}")
                }
            log("downloaded ${downloaded.bytes} bytes in ${downloaded.segments} segments")
            log("wrote ${downloaded.path}")
        }
    }

    /**
     * 07 · Read the per-frame trailer: frame id, sender timestamp, `user_data`.
     *
     * **These read zero against a model that attaches no metadata**, which is most of them, and
     * that is the lesson rather than a failure: a tag is dropped unless the far end declared that
     * it reads tags. Run it against a model that tags, or push tagged frames yourself as 04 does.
     *
     * The timestamp is on the *sender's* clock. Differences between stamps from one sender are
     * what it supports; subtracting it from a local clock is meaningless.
     */
    private suspend fun frameMetadata(
        apiKey: String,
        log: Log,
        sink: FrameSink?,
    ) {
        withReactor(HELIOS, apiKey, log) { reactor ->
            reactor.status.first { it == ConnectionStatus.READY }
            var reported = 0
            reactor.track(OUTPUT_TRACK).onFrame { frame ->
                if (reported < 5) {
                    reported += 1
                    log(
                        "frame id=${frame.frameId} senderTs=${frame.timestampUs}µs " +
                            "tag=${frame.userData?.size ?: 0} bytes",
                    )
                }
                sink?.render(frame)
            }
            reactor.sendCommand("set_prompt", """{"prompt":"$PROMPT"}""")
            reactor.sendCommand("start")
            delay(12_000)
            if (reported == 0) log("no frames arrived — check the model's minimum")
        }
    }
}
