package inc.reactor.sdk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A JSON value, as the model sent it or as you are about to send it.
 *
 * <p>Command arguments and replies have no fixed shape: what they hold is whatever the model
 * declares, so there is nothing for this SDK to generate a type from. This is that — a sealed
 * hierarchy you can {@code switch} over, the same choice the Swift SDK made for the same reason.
 *
 * <pre>{@code
 * CommandReply reply = reactor.sendCommand("describe", JsonValue.object()
 *         .put("detail", "high")
 *         .build()).join().orElseThrow();
 *
 * String text = switch (reply.data().orElse(JsonValue.ofNull())) {
 *     case JsonValue.JsonObject object -> object.getString("caption").orElse("");
 *     case JsonValue.JsonString string -> string.value();
 *     default -> "";
 * };
 * }</pre>
 *
 * <p><b>Why not {@code JsonNode}.</b> Jackson has two major lines in use at once, under different
 * group ids and different package names, so a client library that put either one in its public
 * signatures would force that choice on every application embedding it. If Jackson is what your
 * application uses, add {@code inc.reactor:reactor-sdk-jackson} and convert in one call.
 */
public sealed interface JsonValue {

    /** A JSON string. */
    record JsonString(String value) implements JsonValue {}

    /**
     * A JSON number.
     *
     * <p>JSON has one numeric type and this SDK does not invent two. {@link #asLong()} is there for
     * the common case, and it refuses rather than rounding.
     */
    record JsonNumber(double value) implements JsonValue {

        /**
         * @return this number as a {@code long}
         * @throws ArithmeticException when it is not an exact integer, or does not fit — losing
         *     precision silently is how an id becomes a different id
         */
        public long asLong() {
            if (value != Math.rint(value) || value < Long.MIN_VALUE || value > Long.MAX_VALUE) {
                throw new ArithmeticException(value + " is not an exact long");
            }
            return (long) value;
        }
    }

    /** {@code true} or {@code false}. */
    record JsonBoolean(boolean value) implements JsonValue {}

    /** {@code null}. */
    record JsonNull() implements JsonValue {}

    /** A JSON object. Keys keep the order they arrived in; a repeated key keeps the last value. */
    record JsonObject(Map<String, JsonValue> fields) implements JsonValue {

        public JsonObject {
            // A LinkedHashMap, not Map.copyOf: that one is explicitly unordered, and this type
            // documents keeping the order fields arrived in. Order is also what makes serialising
            // the same value twice produce the same text, which a test can hold to.
            fields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        /**
         * @param key the field name
         * @return the value, or empty when the object has no such field
         */
        public Optional<JsonValue> get(String key) {
            return Optional.ofNullable(fields.get(key));
        }

        /**
         * @param key the field name
         * @return the field's string value, or empty when it is absent or is not a string
         */
        public Optional<String> getString(String key) {
            return get(key).filter(JsonString.class::isInstance).map(value -> ((JsonString) value).value());
        }

        /**
         * @param key the field name
         * @return the field's numeric value, or empty when it is absent or is not a number
         */
        public Optional<Double> getNumber(String key) {
            return get(key).filter(JsonNumber.class::isInstance).map(value -> ((JsonNumber) value).value());
        }

        /**
         * @param key the field name
         * @return the field's boolean value, or empty when it is absent or is not a boolean
         */
        public Optional<Boolean> getBoolean(String key) {
            return get(key).filter(JsonBoolean.class::isInstance).map(value -> ((JsonBoolean) value).value());
        }
    }

    /** A JSON array. */
    record JsonArray(List<JsonValue> items) implements JsonValue {

        public JsonArray {
            items = List.copyOf(items);
        }
    }

    /**
     * @param value the text
     * @return it, as JSON
     */
    static JsonValue of(String value) {
        return new JsonString(value);
    }

    /**
     * @param value the number
     * @return it, as JSON
     */
    static JsonValue of(double value) {
        return new JsonNumber(value);
    }

    /**
     * @param value the flag
     * @return it, as JSON
     */
    static JsonValue of(boolean value) {
        return new JsonBoolean(value);
    }

    /** @return JSON {@code null} */
    static JsonValue ofNull() {
        return new JsonNull();
    }

    /**
     * @param items the values
     * @return them, as a JSON array
     */
    static JsonValue array(List<JsonValue> items) {
        return new JsonArray(items);
    }

    /** @return a builder for a JSON object */
    static ObjectBuilder object() {
        return new ObjectBuilder();
    }

    /**
     * Parses one JSON value.
     *
     * @param json the text
     * @return the value
     * @throws ReactorException with {@link ErrorCode#DECODE_FAILED} when the text is not one
     *     well-formed JSON value
     */
    static JsonValue parse(String json) {
        return inc.reactor.sdk.internal.JsonBridge.parse(json);
    }

    /** @return this value as JSON text */
    default String toJsonString() {
        StringBuilder out = new StringBuilder();
        inc.reactor.sdk.internal.JsonBridge.write(this, out);
        return out.toString();
    }

    /** Builds a JSON object without writing {@code new JsonString(...)} at every field. */
    final class ObjectBuilder {

        private final Map<String, JsonValue> fields = new LinkedHashMap<>();

        private ObjectBuilder() {}

        /**
         * @param key the field name
         * @param value the value
         * @return this builder
         */
        public ObjectBuilder put(String key, JsonValue value) {
            fields.put(key, value);
            return this;
        }

        /**
         * @param key the field name
         * @param value the text
         * @return this builder
         */
        public ObjectBuilder put(String key, String value) {
            return put(key, JsonValue.of(value));
        }

        /**
         * @param key the field name
         * @param value the number
         * @return this builder
         */
        public ObjectBuilder put(String key, double value) {
            return put(key, JsonValue.of(value));
        }

        /**
         * @param key the field name
         * @param value the flag
         * @return this builder
         */
        public ObjectBuilder put(String key, boolean value) {
            return put(key, JsonValue.of(value));
        }

        /**
         * @param key the field name
         * @param values the values
         * @return this builder
         */
        public ObjectBuilder putArray(String key, List<JsonValue> values) {
            return put(key, JsonValue.array(new ArrayList<>(values)));
        }

        /** @return the object */
        public JsonValue build() {
            return new JsonObject(fields);
        }
    }
}
