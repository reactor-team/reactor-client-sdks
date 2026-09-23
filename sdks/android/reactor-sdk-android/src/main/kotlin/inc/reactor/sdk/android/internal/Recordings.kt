package inc.reactor.sdk.android.internal

import inc.reactor.sdk.android.Clip
import inc.reactor.sdk.android.DownloadedClip
import inc.reactor.sdk.android.ErrorCode

/** Decoding and argument checks for recordings. */
internal object Recordings {
    /** `{ playlist_url, session_id, kind, predicted_ready_at_ms, … }`. */
    fun decodeClip(resultJson: String?): Clip {
        if (resultJson.isNullOrBlank()) {
            throw JsonException("The clip request completed but carried no clip")
        }
        val fields = Json.parseObject(resultJson)
        val url =
            fields["playlist_url"] as? String
                ?: throw JsonException("Clip result carried no playlist_url: $resultJson")
        return Clip(
            playlistUrl = url,
            sessionId = fields["session_id"] as? String,
            kind = fields["kind"] as? String,
            predictedReadyAtMs = fields["predicted_ready_at_ms"] as? Double,
            raw = fields,
        )
    }

    /** `{ path, bytes, segments }`. */
    fun decodeDownload(resultJson: String?): DownloadedClip {
        if (resultJson.isNullOrBlank()) {
            throw JsonException("The download completed but reported no file")
        }
        val fields = Json.parseObject(resultJson)
        return DownloadedClip(
            path =
                fields["path"] as? String
                    ?: throw JsonException("Download result carried no path: $resultJson"),
            bytes = (fields["bytes"] as? Double)?.toLong() ?: 0L,
            segments = (fields["segments"] as? Double)?.toLong() ?: 0L,
        )
    }

    /**
     * The readiness grace, in the units the ABI wants.
     *
     * Negative and infinite both mean "wait as long as the session lives", and that is the only
     * sane answer for a model generating slower than real time: clip readiness is in media time,
     * so a model at a tenth of real time reaches the boundary ten times later than any wall-clock
     * guess. A NaN is a caller bug — the ABI would report it back through the completion, but
     * refusing it here puts the error on the calling thread where the mistake was made.
     */
    fun readyTimeoutSeconds(seconds: Double?): Double {
        if (seconds == null) return -1.0
        if (seconds.isNaN()) {
            throw ErrorCode.toException(
                wire = "BAD_REQUEST",
                message =
                    "A readiness timeout of NaN is not a duration. Use a negative value " +
                        "or null to wait as long as the session lives.",
                operation = "download_clip",
            )
        }
        return seconds
    }

    /**
     * A clip's own prediction, in the units the ABI wants.
     *
     * 0 means "the runtime offered none", which runs the grace from now instead.
     */
    fun predictedReadyAtMs(clip: Clip): Double = clip.predictedReadyAtMs ?: 0.0
}
