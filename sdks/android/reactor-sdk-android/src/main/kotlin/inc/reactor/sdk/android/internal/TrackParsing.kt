package inc.reactor.sdk.android.internal

import inc.reactor.sdk.android.ErrorCode
import inc.reactor.sdk.android.Track
import inc.reactor.sdk.android.TrackDirection
import inc.reactor.sdk.android.TrackKind
import inc.reactor.sdk.android.TrackList
import inc.reactor.sdk.android.TrackOwner

/** Reads `reactor_tracks`' JSON array into the declared order. */
internal object TrackParsing {
    /**
     * Parse `[{"name":…,"kind":"video"|"audio","direction":"sendonly"|"recvonly"}]`.
     *
     * Order is preserved because it is the contract — see [TrackList]. An entry whose kind or
     * direction this SDK does not recognise is **skipped rather than guessed at**: inventing a
     * direction would turn a refusal this SDK owes the caller into a silent no-op at the FFI.
     */
    fun parse(
        json: String?,
        owner: TrackOwner,
    ): TrackList {
        if (json.isNullOrBlank()) return TrackList(emptyList())
        val entries =
            try {
                Json.parse(json) as? List<*> ?: return TrackList(emptyList())
            } catch (e: JsonException) {
                throw ErrorCode.toException(
                    wire = "DECODE_FAILED",
                    message = "The session's track list did not parse: $json",
                    operation = "tracks",
                    cause = e,
                )
            }

        val tracks =
            entries.mapNotNull { entry ->
                @Suppress("UNCHECKED_CAST")
                val fields = entry as? Map<String, Any?> ?: return@mapNotNull null
                val name = fields["name"] as? String ?: return@mapNotNull null
                val kind = TrackKind.of(fields["kind"] as? String) ?: return@mapNotNull null
                val direction =
                    TrackDirection.of(fields["direction"] as? String) ?: return@mapNotNull null
                Track(
                    name = name,
                    kind = kind,
                    direction = direction,
                    mid = fields["mid"] as? String,
                    owner = owner,
                )
            }
        return TrackList(tracks)
    }
}
