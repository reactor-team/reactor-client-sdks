package inc.reactor.sdk.android

/**
 * How a session generates.
 *
 * Chosen in [ReactorOptions.generation], because it is a property of the session the client asks
 * for rather than of any one call.
 */
public enum class Generation(
    internal val wire: String,
) {
    /**
     * Continuously, over the model's media tracks. The default, and what every session did before
     * the choice existed.
     */
    STREAMED("streamed"),

    /**
     * One [step][Reactor.step] at a time. Each step applies the model's state, a list of commands
     * and every input frame pushed since the previous step, in that order, then generates and
     * answers with what the model produced on each output track — arrays, not a video stream.
     *
     * A runtime without stepped generation ignores the request rather than refusing it, so
     * [Reactor.connect] checks what the runtime reports and fails with
     * [GenerationModeMismatchException] when it did not start the session this way.
     */
    STEPPED("stepped"),
}
