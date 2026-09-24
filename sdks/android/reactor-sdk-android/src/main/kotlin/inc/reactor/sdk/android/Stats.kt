package inc.reactor.sdk.android

/**
 * A connection statistics snapshot, as the platform reported it.
 *
 * Deliberately not a typed struct of the fields we happen to know today. The measurement set is
 * the platform's to extend, and a binding that mapped it into fixed fields would silently drop
 * every new one — so this keeps the parsed shape and lets a caller read what is there.
 */
public class Stats internal constructor(
    /** Every measurement, as parsed JSON. */
    public val raw: Map<String, Any?>,
) {
    /**
     * A numeric measurement by key, or null when absent.
     *
     * Returns [Double] rather than a narrower type: JSON numbers arrive as doubles, and counters
     * here are signed — coercing to an unsigned or 32-bit type is how a counter that has wrapped
     * or gone negative becomes a wrong answer rather than a visible one.
     */
    public fun number(key: String): Double? = raw[key] as? Double

    public fun string(key: String): String? = raw[key] as? String

    override fun toString(): String = "Stats(${raw.keys.sorted().joinToString(", ")})"
}
