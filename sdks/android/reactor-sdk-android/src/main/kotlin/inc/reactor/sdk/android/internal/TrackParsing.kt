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
     * The names in `reactor_paused_tracks`, as a set.
     *
     * Parsed rather than substring-matched. The obvious shortcut — does the raw JSON contain
     * `"name"` — is wrong for any name the encoder escapes: a track called `a"b` is written
     * `"a\"b"` and never matches, and a track called `cam` matches a *different* track called
     * `webcam` only if quoting happens to save it. The sibling list is parsed properly three
     * lines away, and the Java SDK parses this one into a Set for exactly this reason.
     *
     * An unparseable list is treated as none paused rather than raised: this feeds a property
     * read on every frame, and the paused state is advisory where the track list is structural.
     */
    fun parsePaused(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        val entries =
            try {
                Json.parse(json) as? List<*> ?: return emptySet()
            } catch (e: JsonException) {
                return emptySet()
            }
        return entries.filterIsInstance<String>().toSet()
    }

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
        val parsed =
            try {
                Json.parse(json)
            } catch (e: JsonException) {
                throw ErrorCode.toException(
                    wire = "DECODE_FAILED",
                    message = "The session's track list did not parse: $json",
                    operation = "tracks",
                    cause = e,
                )
            }
        // A well-formed payload of the wrong shape — `{}`, or a bare string — used to become
        // "no tracks declared", silently, while a malformed one was a DECODE_FAILED. Both are
        // the same class of server bug, and the quiet one is worse: a caller sees a session that
        // declared nothing and goes looking at the model manifest.
        val entries =
            parsed as? List<*>
                ?: throw ErrorCode.toException(
                    wire = "DECODE_FAILED",
                    message =
                        "The session's track list is not a list: $json. A model declares its " +
                            "tracks as a JSON array; this is a platform response this SDK cannot " +
                            "read, not a session without tracks.",
                    operation = "tracks",
                )

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
