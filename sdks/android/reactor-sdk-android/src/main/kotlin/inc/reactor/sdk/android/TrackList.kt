package inc.reactor.sdk.android

/**
 * The tracks a session declared, **in the order it declared them**.
 *
 * Indexing is part of the contract: `tracks[0]` is the first track the model declared, and every
 * SDK promises the same. Collecting these into a name-keyed map — the obvious implementation —
 * sorts them alphabetically and silently renumbers what `tracks[0]` means for every caller, so
 * lookup here scans the sequence instead. A session declares a handful of tracks, not a
 * dictionary's worth.
 *
 * Filters chain in either order and return a [TrackList], so `withKind(VIDEO).withDirection(
 * RECVONLY).one()` and the reverse are the same query.
 */
public class TrackList internal constructor(
    private val tracks: List<Track>,
) : List<Track> by tracks {
    /** Only the tracks of [kind]. */
    public fun withKind(kind: TrackKind): TrackList = TrackList(tracks.filter { it.kind == kind })

    /** Only the tracks flowing [direction]. */
    public fun withDirection(direction: TrackDirection): TrackList = TrackList(tracks.filter { it.direction == direction })

    /**
     * The single track this filter selected.
     *
     * @throws InvalidStateException when there is not exactly one — naming how many there were
     *   and what they are called, because "expected 1, got 3" sends a reader back to the model
     *   manifest and a list of names does not.
     */
    public fun one(): Track =
        when (tracks.size) {
            1 -> tracks[0]
            0 -> throw ErrorCode.toException(
                wire = "INVALID_STATE",
                message = "No track matched. The session declared: ${describeAll()}",
                operation = "one",
            )
            else -> throw ErrorCode.toException(
                wire = "INVALID_STATE",
                message =
                    "${tracks.size} tracks matched, not one: " +
                        tracks.joinToString(", ") { it.name },
                operation = "one",
            )
        }

    private fun describeAll(): String = if (tracks.isEmpty()) "nothing" else tracks.joinToString(", ") { it.name }

    override fun toString(): String = "TrackList(${tracks.joinToString(", ") { it.name }})"
}
