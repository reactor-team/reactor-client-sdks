package inc.reactor.sdk;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One command of a {@link Reactor#step step}, run after the step's state and in list order.
 *
 * <pre>{@code
 * StepCommand.of("advance", JsonValue.object().put("frames", 4).build());
 * StepCommand.of("reset");
 * }</pre>
 *
 * @param name the command, as the model's schema declares it
 * @param data its arguments as a JSON object, or {@code null} for a command that takes none
 */
public record StepCommand(String name, @Nullable JsonValue data) {

    /**
     * @throws IllegalArgumentException when {@code name} is empty, or {@code data} is anything but a
     *     JSON object or {@code null} — the runtime would refuse it, and refusing it here names the
     *     command it belongs to
     */
    public StepCommand {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("a step command needs a non-empty name");
        }
        if (data instanceof JsonValue.JsonNull) {
            data = null;
        }
        if (data != null && !(data instanceof JsonValue.JsonObject)) {
            throw new IllegalArgumentException("the data of step command \"" + name
                    + "\" must be a JSON object or null, got " + data.toJsonString());
        }
    }

    /**
     * @param name the command
     * @return a command that takes no arguments
     */
    public static StepCommand of(String name) {
        return new StepCommand(name, null);
    }

    /**
     * @param name the command
     * @param data its arguments, a JSON object
     * @return the command
     */
    public static StepCommand of(String name, JsonValue data) {
        return new StepCommand(name, data);
    }

    /** @return the {@code {"command", "data"}} object the native library takes for one command */
    JsonValue toWire() {
        return JsonValue.object()
                .put("command", name)
                .put("data", data == null ? JsonValue.ofNull() : data)
                .build();
    }
}
