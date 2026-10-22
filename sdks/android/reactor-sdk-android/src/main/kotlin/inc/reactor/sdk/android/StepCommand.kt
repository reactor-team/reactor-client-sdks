package inc.reactor.sdk.android

import inc.reactor.sdk.android.internal.Json
import inc.reactor.sdk.android.internal.JsonException

/**
 * One command of a [step][Reactor.step], run after the step's state and in list order.
 *
 * ```kotlin
 * StepCommand("advance", """{"frames": 4}""")
 * StepCommand("reset")
 * ```
 *
 * @property name the command, as the model's schema declares it
 * @property data its arguments as a JSON object, in the same raw form [Reactor.sendCommand] takes
 *   them, or null for a command that takes none
 * @throws IllegalArgumentException when [name] is empty, or [data] is not a JSON object — the
 *   runtime would refuse either, and refusing it here names the command it belongs to
 */
public data class StepCommand(
    public val name: String,
    public val data: String? = null,
) {
    init {
        require(name.isNotEmpty()) { "A step command needs a non-empty name" }
        if (data != null) {
            val parsed =
                try {
                    Json.parse(data)
                } catch (e: JsonException) {
                    throw IllegalArgumentException("The data of step command \"$name\" is not JSON: ${e.message}", e)
                }
            require(parsed == null || parsed is Map<*, *>) {
                "The data of step command \"$name\" must be a JSON object or null, got $data"
            }
        }
    }

    /** The `{"command", "data"}` object the native library takes for one command. */
    internal fun toWire(): String = "{\"command\":${Json.quote(name)},\"data\":${data ?: "null"}}"
}
