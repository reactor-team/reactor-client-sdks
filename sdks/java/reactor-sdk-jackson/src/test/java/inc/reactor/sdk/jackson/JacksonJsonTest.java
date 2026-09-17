package inc.reactor.sdk.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import inc.reactor.sdk.JsonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The interop this module exists for: an application's own ObjectMapper doing the binding. */
final class JacksonJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** What an application would model a reply as. */
    record Caption(String text, int confidence) {}

    @Test
    @DisplayName("a reply binds to an application's own type in one call")
    void aReplyBindsToAnApplicationType() throws Exception {
        JsonValue reply = JsonValue.parse("{\"text\":\"a cat\",\"confidence\":91}");

        Caption caption = mapper.treeToValue(JacksonJson.toNode(reply), Caption.class);

        assertEquals(new Caption("a cat", 91), caption);
    }

    @Test
    @DisplayName("a value survives the round trip in both directions")
    void valuesRoundTrip() throws Exception {
        String json = "{\"a\":[1,2.5,true,null,\"x\"],\"b\":{\"c\":\"d\"}}";

        JsonValue viaSdk = JsonValue.parse(json);
        JsonNode viaJackson = mapper.readTree(json);

        assertEquals(viaJackson, JacksonJson.toNode(viaSdk));
        assertEquals(viaSdk.toJsonString(), JacksonJson.toValue(viaJackson).toJsonString());
    }

    @Test
    @DisplayName("arguments can be built from an application type without hand-writing JSON")
    void argumentsCanComeFromAnApplicationType() {
        JsonValue args = JacksonJson.toValue(mapper.valueToTree(new Caption("hello", 7)));

        assertTrue(args.toJsonString().contains("\"text\":\"hello\""), args.toJsonString());
        assertTrue(args.toJsonString().contains("\"confidence\":7"), args.toJsonString());
    }
}
