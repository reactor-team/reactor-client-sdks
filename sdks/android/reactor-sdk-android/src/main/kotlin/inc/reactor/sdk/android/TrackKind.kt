package inc.reactor.sdk.android

/** What a track carries. */
public enum class TrackKind(
    internal val wire: String,
) {
    VIDEO("video"),
    AUDIO("audio"),
    ;

    internal companion object {
        fun of(wire: String?): TrackKind? = entries.firstOrNull { it.wire == wire }
    }
}
