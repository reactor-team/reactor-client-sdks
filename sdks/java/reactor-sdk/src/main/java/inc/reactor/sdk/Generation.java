package inc.reactor.sdk;

/**
 * How a session generates.
 *
 * <p>Chosen when the client is created, with {@link ReactorOptions.Builder#generation}, because it
 * is a property of the session the client asks for rather than of any one call.
 */
public enum Generation {
    /**
     * Continuously, over the model's media tracks. The default, and what every session did before
     * the choice existed.
     */
    STREAMED("streamed"),

    /**
     * One {@link Reactor#step step} at a time. Each step applies the model's state, a list of
     * commands and every input frame pushed since the previous step, in that order, then generates
     * and answers with what the model produced on each output track — arrays, not a video stream.
     *
     * <p>A runtime without stepped generation ignores the request rather than refusing it, so
     * {@link Reactor#connect()} checks what the runtime reports and fails with {@link
     * GenerationModeMismatchException} when it did not start the session this way.
     */
    STEPPED("stepped");

    private final String wireValue;

    Generation(String wireValue) {
        this.wireValue = wireValue;
    }

    /** @return the mode as the native library's options name it: {@code "streamed"} or {@code "stepped"} */
    public String wireValue() {
        return wireValue;
    }
}
