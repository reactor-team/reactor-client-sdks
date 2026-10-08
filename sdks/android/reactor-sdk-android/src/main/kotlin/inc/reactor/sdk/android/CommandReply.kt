package inc.reactor.sdk.android

/**
 * What a model answered with.
 *
 * Both fields are optional because the ABI says so: a handler may acknowledge a command and
 * return no message at all, which is success with nothing to report — distinct from a reply that
 * failed to parse, which is [DecodeFailedException].
 */
public data class CommandReply(
    /** The reply's type tag, when it carried one. */
    public val type: String? = null,
    /** The reply's payload, as parsed JSON — a Map, List, String, Double, Boolean or null. */
    public val data: Any? = null,
) {
    /** True when the handler acknowledged the command without sending anything back. */
    public val isEmpty: Boolean
        get() = type == null && data == null
}
