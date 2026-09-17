package inc.reactor.sdk;

import java.util.Optional;

/**
 * What a model answered a command with: {@code {type, data}}.
 *
 * <p>A command that the handler ran and acknowledged without producing a message — an
 * auto-generated setter, say — answers with nothing at all, which is why {@code sendCommand} gives
 * an {@link Optional} of this rather than one of these with everything empty.
 *
 * @param type the message type the model named, when it named one
 * @param data the payload, as the model sent it
 */
public record CommandReply(Optional<String> type, Optional<JsonValue> data) {

    /**
     * The payload, or JSON {@code null} when there was none.
     *
     * @return something to {@code switch} over without unwrapping first
     */
    public JsonValue dataOrNull() {
        return data.orElseGet(JsonValue::ofNull);
    }
}
