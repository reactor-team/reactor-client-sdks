package inc.reactor.sdk.android

/**
 * Which way a track flows, from the application's point of view.
 *
 * [SENDONLY] is a slot the app pushes into; [RECVONLY] is one the model generates into. The model
 * declares both, and the direction is what makes `pushFrame` on a recvonly track a mistake this
 * SDK refuses rather than a push the native layer silently drops.
 */
public enum class TrackDirection(
    internal val wire: String,
) {
    /** The application sends; the model receives. */
    SENDONLY("sendonly"),

    /** The model sends; the application receives. */
    RECVONLY("recvonly"),
    ;

    internal companion object {
        fun of(wire: String?): TrackDirection? = entries.firstOrNull { it.wire == wire }
    }
}
