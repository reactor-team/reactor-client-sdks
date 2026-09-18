package inc.reactor.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The JSON type, and the three places a hand-written parser usually goes wrong.
 *
 * <p>This SDK carries its own parser because its own value type is what appears in its public
 * signatures — and a parser is only worth owning if the cases below are covered rather than
 * assumed.
 */
final class JsonValueTest {

    @Test
    @DisplayName("a surrogate pair survives, rather than becoming two broken characters")
    void surrogatePairsSurvive() {
        // A single emoji arrives as two backslash-u escapes. A parser that treated each as a
        // whole character would produce a String nothing can render. (Written without the escape
        // sequence itself: javac expands those inside comments too, and a stray one is a syntax
        // error rather than a comment.)
        JsonValue parsed = JsonValue.parse("{\"caption\":\"clap \\uD83D\\uDC4F done\"}");

        String caption = assertInstanceOf(JsonValue.JsonObject.class, parsed)
                .getString("caption")
                .orElseThrow();
        assertEquals("clap 👏 done", caption);
        assertEquals(
                1,
                caption.codePointCount(caption.indexOf("clap ") + 5, caption.indexOf(" done")),
                "the pair must read as one code point, not two");
    }

    @Test
    @DisplayName("nesting past the limit is refused, rather than overflowing the stack")
    void deepNestingIsRefused() {
        // Parsing is recursive, so depth is stack depth. A StackOverflowError is an Error and
        // nothing on the way out would catch it; this is a payload the platform could genuinely
        // send, so it has to come back as an error a caller can handle.
        String deep = "[".repeat(5_000) + "]".repeat(5_000);

        ReactorException thrown = assertThrows(ReactorException.class, () -> JsonValue.parse(deep));

        assertEquals("DECODE_FAILED", thrown.code());
        assertTrue(thrown.getMessage().contains("deep"), thrown.getMessage());
    }

    @Test
    @DisplayName("an integer too large for a long is kept as a number rather than rejected")
    void hugeIntegersAreKept() {
        JsonValue parsed = JsonValue.parse("{\"count\":123456789012345678901234567890}");

        double count = assertInstanceOf(JsonValue.JsonObject.class, parsed)
                .getNumber("count")
                .orElseThrow();
        assertTrue(count > 1e29, "the value must survive, even past what a long can hold");
    }

    @Test
    @DisplayName("asLong refuses to round, because an id that rounds is a different id")
    void asLongRefusesToRound() {
        JsonValue.JsonNumber whole = new JsonValue.JsonNumber(42);
        JsonValue.JsonNumber fractional = new JsonValue.JsonNumber(42.5);

        assertEquals(42L, whole.asLong());
        assertThrows(ArithmeticException.class, fractional::asLong);
    }

    @Test
    @DisplayName("text round-trips through escapes, control characters and non-ASCII")
    void textRoundTrips() {
        // Built rather than written out: a raw control byte in a source file is invisible in a
        // diff and survives a copy-paste badly.
        String awkward = "quote \" backslash \\ newline \n tab \t control " + (char) 1 + " café 🎥";
        JsonValue value = JsonValue.object().put("text", awkward).build();

        JsonValue reparsed = JsonValue.parse(value.toJsonString());

        assertEquals(
                awkward,
                assertInstanceOf(JsonValue.JsonObject.class, reparsed)
                        .getString("text")
                        .orElseThrow());
    }

    @Test
    @DisplayName("whole numbers are written without a trailing decimal")
    void wholeNumbersAreWrittenAsIntegers() {
        // A model reading an index or a count expects 3, not 3.0.
        assertEquals("{\"index\":3}", JsonValue.object().put("index", 3).build().toJsonString());
        assertEquals(
                "{\"ratio\":0.5}", JsonValue.object().put("ratio", 0.5).build().toJsonString());
    }

    @Test
    @DisplayName("JSON cannot carry NaN or infinity, and says so instead of writing something unreadable")
    void nonFiniteNumbersAreRefused() {
        JsonValue value = JsonValue.object().put("x", Double.NaN).build();

        ReactorException thrown = assertThrows(ReactorException.class, value::toJsonString);

        assertTrue(thrown.getMessage().contains("NaN"), thrown.getMessage());
    }

    @Test
    @DisplayName("malformed JSON is a typed decode failure, naming where it went wrong")
    void malformedJsonIsTyped() {
        ReactorException thrown = assertThrows(ReactorException.class, () -> JsonValue.parse("{\"a\": }"));

        assertEquals("DECODE_FAILED", thrown.code());
        assertTrue(thrown.getMessage().contains("offset"), thrown.getMessage());
    }

    @Test
    @DisplayName("values switch over without unwrapping")
    void valuesAreSwitchable() {
        JsonValue value = JsonValue.array(List.of(JsonValue.of("a"), JsonValue.of(1), JsonValue.ofNull()));

        String described =
                switch (value) {
                    case JsonValue.JsonArray array ->
                        "array of " + array.items().size();
                    case JsonValue.JsonObject ignored -> "object";
                    default -> "scalar";
                };

        assertEquals("array of 3", described);
    }

    @Test
    @DisplayName("an integer beyond 2^53 survives a round trip")
    void largeIntegersAreNotRounded() {
        // 2^53 + 1. As a double this is 9007199254740992, which for a command id or a counter is a
        // different value handed back without a word.
        long id = 9_007_199_254_740_993L;
        JsonValue parsed = JsonValue.parse("{\"id\":" + id + "}");
        JsonValue.JsonNumber number =
                (JsonValue.JsonNumber) ((JsonValue.JsonObject) parsed).fields().get("id");

        assertEquals(id, number.asLong());
        assertTrue(number.isExactInteger());
        // And it goes back out as it came in, rather than as the double it would have become.
        assertEquals("{\"id\":" + id + "}", parsed.toJsonString());
    }

    @Test
    @DisplayName("a fractional number is still a double, and still refuses asLong")
    void fractionsAreUnchanged() {
        JsonValue.JsonNumber half = new JsonValue.JsonNumber(0.5);
        assertFalse(half.isExactInteger());
        assertThrows(ArithmeticException.class, half::asLong);
    }
}
