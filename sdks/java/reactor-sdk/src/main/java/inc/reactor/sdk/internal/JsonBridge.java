package inc.reactor.sdk.internal;

import inc.reactor.sdk.ErrorCode;
import inc.reactor.sdk.JsonValue;
import inc.reactor.sdk.ReactorException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Between the parser's plain values and the SDK's public {@link JsonValue}.
 *
 * <p>The parser answers in {@code Map}, {@code List}, {@code String}, {@code Double}, {@code Long},
 * {@code Boolean} and {@code null} because that is what a parser is for; the public type is sealed
 * so callers can {@code switch} over it. This is the one place that translates, in both directions.
 */
public final class JsonBridge {

    private JsonBridge() {}

    /**
     * @param json the text
     * @return the value
     * @throws ReactorException with {@link ErrorCode#DECODE_FAILED} when it is not well-formed
     */
    public static JsonValue parse(String json) {
        try {
            return toValue(Json.parse(json));
        } catch (JsonException malformed) {
            throw ReactorException.of(
                    ErrorCode.DECODE_FAILED.code(),
                    "this is not well-formed JSON: " + malformed.getMessage(),
                    null,
                    "parse",
                    null);
        }
    }

    /**
     * @param parsed what {@link Json} answered
     * @return the same value, sealed
     */
    public static JsonValue toValue(Object parsed) {
        return switch (parsed) {
            case null -> JsonValue.ofNull();
            case String text -> new JsonValue.JsonString(text);
            case Boolean flag -> new JsonValue.JsonBoolean(flag);
            // The long overload, not the double one: this is where the precision was lost.
            case Long number -> new JsonValue.JsonNumber(number.longValue());
            case Double number -> new JsonValue.JsonNumber(number);
            case Map<?, ?> object -> {
                Map<String, JsonValue> fields = new LinkedHashMap<>();
                object.forEach((key, value) -> fields.put(String.valueOf(key), toValue(value)));
                yield new JsonValue.JsonObject(fields);
            }
            case List<?> array -> {
                List<JsonValue> items = new ArrayList<>(array.size());
                array.forEach(item -> items.add(toValue(item)));
                yield new JsonValue.JsonArray(items);
            }
            default ->
                throw new IllegalStateException(
                        "the parser produced something this bridge does not know: " + parsed.getClass());
        };
    }

    /**
     * Writes a value as JSON text.
     *
     * @param value what to write
     * @param out where to write it
     */
    public static void write(JsonValue value, StringBuilder out) {
        switch (value) {
            case JsonValue.JsonNull ignored -> out.append("null");
            case JsonValue.JsonBoolean flag -> out.append(flag.value());
            case JsonValue.JsonNumber number -> {
                if (number.isExactInteger()) {
                    out.append(Long.toString(number.asLong()));
                } else {
                    writeNumber(number.value(), out);
                }
            }
            case JsonValue.JsonString text -> writeString(text.value(), out);
            case JsonValue.JsonArray array -> {
                out.append('[');
                for (int index = 0; index < array.items().size(); index++) {
                    if (index > 0) {
                        out.append(',');
                    }
                    write(array.items().get(index), out);
                }
                out.append(']');
            }
            case JsonValue.JsonObject object -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<String, JsonValue> field : object.fields().entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    writeString(field.getKey(), out);
                    out.append(':');
                    write(field.getValue(), out);
                }
                out.append('}');
            }
        }
    }

    private static void writeNumber(double value, StringBuilder out) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            // JSON has no way to write these, and emitting the Java spelling would produce text
            // nothing can read back.
            throw ReactorException.of(
                    ErrorCode.BAD_REQUEST.code(),
                    "JSON cannot carry " + value + " — it has no NaN and no infinities.",
                    null,
                    "toJsonString",
                    null);
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            // Whole numbers go out without a trailing ".0", which is what a model reading an index
            // or a count expects to see.
            out.append((long) value);
            return;
        }
        out.append(value);
    }

    private static void writeString(String text, StringBuilder out) {
        out.append('"');
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
