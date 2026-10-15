package inc.reactor.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import inc.reactor.sdk.JsonValue;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How extra headers join the options of a token exchange. */
final class AuthenticationTest {

    private static final JsonValue SCOPED = JsonValue.object()
            .putArray("models", List.of(JsonValue.of("owner/model")))
            .build();

    @Test
    @DisplayName("no headers leave the options exactly as given, null included")
    void noHeadersLeaveOptionsUntouched() {
        assertSame(SCOPED, Authentication.withExtraHeaders(SCOPED, Map.of()));
        assertNull(Authentication.withExtraHeaders(null, Map.of()));
    }

    @Test
    @DisplayName("headers become an extra_headers object of strings beside the scope")
    void headersJoinTheScope() {
        assertEquals(
                "{\"models\":[\"owner/model\"],\"extra_headers\":{\"X-Tenant\":\"a\\\"b\"}}",
                Authentication.withExtraHeaders(SCOPED, Map.of("X-Tenant", "a\"b"))
                        .toJsonString());
    }

    @Test
    @DisplayName("headers with no options make an options object of their own")
    void headersWithoutOptions() {
        assertEquals(
                "{\"extra_headers\":{\"X-Tenant\":\"a\"}}",
                Authentication.withExtraHeaders(null, Map.of("X-Tenant", "a")).toJsonString());
    }

    @Test
    @DisplayName("the headers argument replaces an extra_headers entry already in the options")
    void argumentReplacesEntry() {
        JsonValue existing = JsonValue.parse("{\"extra_headers\":{\"X-Old\":\"1\"}}");
        assertEquals(
                "{\"extra_headers\":{\"X-New\":\"2\"}}",
                Authentication.withExtraHeaders(existing, Map.of("X-New", "2")).toJsonString());
    }

    @Test
    @DisplayName("headers cannot ride on options that are not an object")
    void nonObjectOptionsAreRefused() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Authentication.withExtraHeaders(JsonValue.of("models"), Map.of("X-Tenant", "a")));
    }
}
