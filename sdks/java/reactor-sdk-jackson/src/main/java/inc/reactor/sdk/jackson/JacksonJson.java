package inc.reactor.sdk.jackson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import inc.reactor.sdk.JsonValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts between {@link JsonValue} and Jackson's {@link JsonNode}.
 *
 * <p>The SDK's own type is in its public signatures so that a client library does not force a
 * Jackson line on every application embedding it — there are two in use at once, under different
 * group ids and different package names. This module is for applications that have already made
 * that choice and want their own {@code ObjectMapper} to do the binding:
 *
 * <pre>{@code
 * CommandReply reply = reactor.sendCommand("describe", args).join().orElseThrow();
 * Caption caption = mapper.treeToValue(JacksonJson.toNode(reply.dataOrNull()), Caption.class);
 * }</pre>
 */
public final class JacksonJson {

    private JacksonJson() {}

    /**
     * @param value the SDK's value
     * @return the same value as a Jackson node
     */
    public static JsonNode toNode(JsonValue value) {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        return switch (value) {
            case JsonValue.JsonNull ignored -> nodes.nullNode();
            case JsonValue.JsonBoolean flag -> nodes.booleanNode(flag.value());
            case JsonValue.JsonString text -> nodes.textNode(text.value());
            case JsonValue.JsonNumber number -> {
                // JSON has one numeric type and this SDK keeps it that way, but Jackson has
                // several and an application binding to an int field wants an integral node.
                // Emitting a double for everything makes 1 arrive as 1.0.
                double numeric = number.value();
                if (numeric != Math.rint(numeric) || Math.abs(numeric) > Long.MAX_VALUE) {
                    yield nodes.numberNode(numeric);
                }
                long whole = (long) numeric;
                // int rather than long where it fits, because that is what Jackson's own parser
                // produces and node equality is type-sensitive: an IntNode and a LongNode holding
                // 1 print the same and do not compare equal.
                yield whole >= Integer.MIN_VALUE && whole <= Integer.MAX_VALUE
                        ? nodes.numberNode((int) whole)
                        : nodes.numberNode(whole);
            }
            case JsonValue.JsonArray array -> {
                ArrayNode out = nodes.arrayNode(array.items().size());
                array.items().forEach(item -> out.add(toNode(item)));
                yield out;
            }
            case JsonValue.JsonObject object -> {
                ObjectNode out = nodes.objectNode();
                object.fields().forEach((key, field) -> out.set(key, toNode(field)));
                yield out;
            }
        };
    }

    /**
     * @param node a Jackson node
     * @return the same value as the SDK's type
     */
    public static JsonValue toValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return JsonValue.ofNull();
        }
        if (node.isTextual()) {
            return JsonValue.of(node.textValue());
        }
        if (node.isBoolean()) {
            return JsonValue.of(node.booleanValue());
        }
        if (node.isNumber()) {
            return JsonValue.of(node.doubleValue());
        }
        if (node.isArray()) {
            List<JsonValue> items = new ArrayList<>(node.size());
            node.forEach(item -> items.add(toValue(item)));
            return JsonValue.array(items);
        }
        if (node.isObject()) {
            Map<String, JsonValue> fields = new LinkedHashMap<>();
            node.properties().forEach(field -> fields.put(field.getKey(), toValue(field.getValue())));
            return new JsonValue.JsonObject(fields);
        }
        throw new IllegalArgumentException("this Jackson node is not JSON this SDK can carry: " + node.getNodeType());
    }
}
