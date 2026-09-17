package inc.reactor.sdk;

import java.util.Optional;

/**
 * A reading of the connection.
 *
 * <p>Held as the JSON the platform sent rather than as a fixed set of fields. What a runtime
 * reports grows over time, and a record that named today's fields would drop tomorrow's silently —
 * so the whole payload is here, with accessors for the ones that are stable.
 *
 * @param raw everything the platform reported
 */
public record Stats(JsonValue raw) {

    /**
     * @param name the measurement
     * @return its value, or empty when this reading does not carry it
     */
    public Optional<Double> number(String name) {
        return raw instanceof JsonValue.JsonObject object ? object.getNumber(name) : Optional.empty();
    }

    /**
     * @param name the measurement
     * @return its value, or empty when this reading does not carry it
     */
    public Optional<String> text(String name) {
        return raw instanceof JsonValue.JsonObject object ? object.getString(name) : Optional.empty();
    }
}
