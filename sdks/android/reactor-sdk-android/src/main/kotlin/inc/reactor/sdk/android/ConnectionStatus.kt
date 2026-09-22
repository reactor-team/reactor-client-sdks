package inc.reactor.sdk.android

/**
 * Where the connection is.
 *
 * The wire values are the static strings `reactor_status` returns and `on_status` delivers. An
 * unrecognised one maps to [DISCONNECTED] rather than throwing: a status arriving on an event
 * thread has nowhere to report a parse failure, and treating an unknown state as "not usable" is
 * the safe reading.
 */
public enum class ConnectionStatus(
    internal val wire: String,
) {
    /** No connection. Publish state does not survive this — see `Track.published`. */
    DISCONNECTED("disconnected"),

    /** Negotiating. */
    CONNECTING("connecting"),

    /** Connected, waiting for the model to become available. */
    WAITING("waiting"),

    /** Connected and running. The only state in which media flows. */
    READY("ready"),
    ;

    internal companion object {
        private val byWire = entries.associateBy { it.wire }

        fun of(wire: String?): ConnectionStatus = byWire[wire] ?: DISCONNECTED
    }
}
