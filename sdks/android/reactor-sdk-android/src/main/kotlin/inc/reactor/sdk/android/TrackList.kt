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
    /**
     * Everything the session declared, carried through every filter.
     *
     * Without it, "the session declared: nothing" is what `withKind(AUDIO).one()` says on a
     * session that declared four video tracks — because by then `tracks` *is* the empty filtered
     * list. The message that is supposed to send a reader to the model manifest instead tells
     * them the manifest is empty. The >1 branch is different: there the filtered names are
     * exactly what the caller needs to narrow further.
     */
    private val declared: List<Track> = tracks,
) : List<Track> by tracks {
    /** Only the tracks of [kind]. */
    public fun withKind(kind: TrackKind): TrackList = TrackList(tracks.filter { it.kind == kind }, declared)

    /** Only the tracks flowing [direction]. */
    public fun withDirection(direction: TrackDirection): TrackList = TrackList(tracks.filter { it.direction == direction }, declared)

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

    /** What the *session* declared — see [declared]. Not the filtered subset. */
    private fun describeAll(): String = if (declared.isEmpty()) "nothing" else declared.joinToString(", ") { it.name }

    override fun toString(): String = "TrackList(${tracks.joinToString(", ") { it.name }})"
}
