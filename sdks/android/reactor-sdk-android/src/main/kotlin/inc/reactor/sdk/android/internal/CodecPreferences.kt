package inc.reactor.sdk.android.internal

import inc.reactor.sdk.android.ErrorCode
import inc.reactor.sdk.android.StepCodec

/**
 * What `setCodecPreference` was told, kept so every new native handle hears it too.
 *
 * The native client reads its codec preference from the handle when a connection is negotiated,
 * and a handle is created at connect — after the caller may have chosen — and replaced whenever a
 * connect needs another model or token. So the choices live here, by track (null: the whole
 * session), and are replayed onto each handle right after it is created. A choice made while a
 * handle exists is also sent to it at once, for its next reconnect.
 *
 * One monitor around sending and storing, and around the replay: a choice made while connect() is
 * creating a handle is either stored before the replay reads it, or sent to the handle the replay
 * ran on. Sent first and stored only once the native side took it, so a choice it refused is not
 * replayed onto the next handle.
 */
internal class CodecPreferences {
    /** Insertion-ordered, so a replay repeats the calls in the order they were first made. */
    private val byTrack = LinkedHashMap<String?, List<StepCodec>?>()

    /**
     * Validate, send through [send] (which answers the native error payload, or null), then store.
     *
     * A codec named more than once counts where it first appears, as the native side and every
     * other SDK read it.
     *
     * @throws IllegalArgumentException for a blank track name
     * @throws inc.reactor.sdk.android.ReactorException the native side's refusal, typed
     */
    @Synchronized
    fun set(
        track: String?,
        codecs: List<StepCodec>?,
        send: (track: String?, codecsJson: String?) -> String?,
    ) {
        require(track == null || track.isNotBlank()) {
            "A codec preference's track is a track name, or null for every track — not a blank string"
        }
        val json = toJson(codecs)
        send(track, json)?.let { throw ErrorPayloads.toException(it, OPERATION) }
        byTrack[track] = codecs?.toList()
    }

    /** Send every stored choice to a new handle, in the order they were first made. */
    @Synchronized
    fun replay(send: (track: String?, codecsJson: String?) -> String?) {
        for ((track, codecs) in byTrack) {
            send(track, toJson(codecs))?.let { throw ErrorPayloads.toException(it, OPERATION) }
        }
    }

    /** What has been chosen, by track (null: the whole session). For tests. */
    @Synchronized
    fun snapshot(): Map<String?, List<StepCodec>?> = LinkedHashMap(byTrack)

    companion object {
        const val OPERATION: String = "set_codec_preference"

        /** The JSON array `reactor_set_codec_preference` takes, or null for the default. */
        fun toJson(codecs: List<StepCodec>?): String? =
            codecs?.joinToString(separator = ",", prefix = "[", postfix = "]") { Json.quote(it.wire) }

        /**
         * Read `reactor_step_frame_encodings`'s answer: track name to `raw`, `h264`, `vp8`, `vp9`
         * or `av1`. Null (no handle) is no tracks; anything that is not an object of strings is a
         * decode failure rather than a guess.
         */
        fun parseEncodings(json: String?): Map<String, String> {
            if (json == null) return emptyMap()
            val fields =
                try {
                    Json.parseObject(json)
                } catch (e: JsonException) {
                    throw ErrorCode.toException(
                        wire = "DECODE_FAILED",
                        message = "The step frame encodings did not parse: $json",
                        operation = "step_frame_encodings",
                        cause = e,
                    )
                }
            return fields.mapValues { (track, encoding) ->
                encoding as? String ?: throw ErrorCode.toException(
                    wire = "DECODE_FAILED",
                    message = "Track '$track' has an encoding that is not a string: $json",
                    operation = "step_frame_encodings",
                )
            }
        }
    }
}
