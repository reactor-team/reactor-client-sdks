package inc.reactor.sdk;

/**
 * How one {@link Reactor#step(JsonValue, java.util.List, java.util.Map, StepOptions) step} runs,
 * beyond what it carries.
 *
 * <pre>{@code
 * reactor.step(null, List.of(), null, StepOptions.defaults().withRequestKeyframe(true));
 * }</pre>
 *
 * @param requestKeyframe ask the runtime to start every encoded output track of this step's result
 *     at a keyframe — for a caller that drops what it decoded so far. {@code false}, the default,
 *     stops no keyframe the runtime sends anyway, and the client already asks on its own when one of
 *     its decoders loses its state. Raw tracks are unaffected.
 */
public record StepOptions(boolean requestKeyframe) {

    private static final StepOptions DEFAULTS = new StepOptions(false);

    /** @return the options a step without any runs with */
    public static StepOptions defaults() {
        return DEFAULTS;
    }

    /**
     * @param requestKeyframe see {@link #requestKeyframe()}
     * @return these options, asking for keyframes or not
     */
    public StepOptions withRequestKeyframe(boolean requestKeyframe) {
        return new StepOptions(requestKeyframe);
    }

    /** @return the options object the native library takes, or {@code null} for the defaults */
    @org.jspecify.annotations.Nullable
    String toWire() {
        return requestKeyframe
                ? JsonValue.object().put("request_keyframe", true).build().toJsonString()
                : null;
    }
}
