package inc.reactor.sdk.android

/**
 * How to reach the platform.
 *
 * An Android app should carry a **short-lived token**, not an API key: a key shipped in an APK is
 * a key anyone can extract. Mint the token on your own backend and pass it as [jwt].
 *
 * [apiKey] is for development. There the SDK does the exchange for you, at `connect()`: the key
 * becomes a token scoped to what that connect needs — the model when creating a session, the
 * session when adopting one — and re-mints when the scope changes. The key still sits in the
 * APK for anyone to extract; none of that is a reason to ship one. A [jwt] passed alongside wins,
 * and is never exchanged, and [local] never authenticates.
 */
public data class ReactorOptions(
    /** The coordinator, e.g. `https://api.reactor.inc`. */
    public val apiUrl: String = "https://api.reactor.inc",
    /** A short-lived token minted by your backend. Preferred — when set, [apiKey] is never used. */
    public val jwt: String? = null,
    /** Development only — exchanged for a scoped token at connect; see the note on this class. */
    public val apiKey: String? = null,
    /** Accept a development coordinator's self-signed certificate. Does not authenticate. */
    public val local: Boolean = false,
    /**
     * How the session generates. [Generation.STEPPED] asks for a session that generates one
     * [step][Reactor.step] at a time instead of streaming; [Reactor.connect] then fails with
     * [GenerationModeMismatchException] when the runtime does not start the session that way, as
     * one without stepped generation will not.
     */
    public val generation: Generation = Generation.STREAMED,
    /**
     * How many seconds a [step][Reactor.step] may take, generation included, before it fails with
     * [RequestTimeoutException]. Stepped sessions only; null for the native library's default of
     * 300.
     */
    public val stepTimeoutSeconds: Double? = null,
    /**
     * The largest data-channel message, in bytes, the client declares and accepts, from
     * [MIN_MAX_MESSAGE_SIZE] (256 KiB) to [MAX_MAX_MESSAGE_SIZE] (1 GiB); null for the native
     * library's default of 256 MiB. A connection carries the smaller of this and the runtime's own
     * limit, which is 64 MiB unless the model raises it, so a [step][Reactor.step] whose result
     * is larger than either fails with [StepResultTooLargeException].
     */
    public val maxMessageSize: Long? = null,
) {
    public companion object {
        /** The lowest [maxMessageSize]: 256 KiB, what a data channel carries unchunked. */
        public const val MIN_MAX_MESSAGE_SIZE: Long = 256L * 1024

        /** The highest [maxMessageSize]: 1 GiB. */
        public const val MAX_MAX_MESSAGE_SIZE: Long = 1024L * 1024 * 1024
    }

    init {
        // Both refused here rather than at connect: the first would be ignored in silence, and the
        // second cannot become a duration.
        if (stepTimeoutSeconds != null) {
            require(generation == Generation.STEPPED) {
                "stepTimeoutSeconds only applies to a stepped session: set generation = Generation.STEPPED " +
                    "or drop the timeout"
            }
            require(stepTimeoutSeconds.isFinite() && stepTimeoutSeconds > 0.0) {
                "stepTimeoutSeconds must be a finite, positive number of seconds; got $stepTimeoutSeconds"
            }
        }
        if (maxMessageSize != null) {
            require(maxMessageSize in MIN_MAX_MESSAGE_SIZE..MAX_MAX_MESSAGE_SIZE) {
                "maxMessageSize must be from $MIN_MAX_MESSAGE_SIZE (256 KiB) to $MAX_MAX_MESSAGE_SIZE " +
                    "(1 GiB) bytes; got $maxMessageSize"
            }
        }
    }
}
