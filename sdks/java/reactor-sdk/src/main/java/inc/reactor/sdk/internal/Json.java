package inc.reactor.sdk.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A JSON reader, because {@code java.base} has none and this SDK's core has no dependencies.
 *
 * <p>A library's dependency list is paid for by every application that uses it, in version
 * conflicts it did not ask for; an SDK whose only job is to carry a handful of documented payloads
 * across a C boundary does not need to make that anyone's problem. What it does need is to be
 * strict — a parser that guesses turns "the model sent something unexpected" into "a field silently
 * read as null", which is exactly the silent failure this SDK refuses to produce.
 *
 * <p>So: no lenient modes, no trailing commas, no comments, no single quotes. Anything the grammar
 * does not allow throws {@link JsonException}, naming the offset.
 *
 * <p>Values map to {@code Map<String, Object>}, {@code List<Object>}, {@code String}, {@code
 * Double}, {@code Long}, {@code Boolean} and {@code null}. Numbers come back as {@code Long} when
 * they are integral and fit, and as {@code Double} otherwise, because a JSON number is one type and
 * the two things callers do with them are not.
 */
final class Json {

    /**
     * How deeply nested a value may be.
     *
     * <p>Parsing is recursive, so depth is stack depth: {@code [[[[…]]]]} a few tens of thousands
     * deep is a StackOverflowError, which is an Error rather than an exception and will not be
     * caught by anything reasonable on the way out. A payload nested past this is refused instead.
     */
    private static final int MAX_DEPTH = 256;

    private final String source;
    private int at;
    private int depth;

    private Json(String source) {
        this.source = source;
    }

    /**
     * Parses one JSON value.
     *
     * @param source the text
     * @return the value; {@code null} when the text is the literal {@code null}
     * @throws JsonException when the text is not one well-formed JSON value
     */
    static Object parse(String source) {
        if (source == null) {
            throw new JsonException("no JSON to parse", 0);
        }
        Json json = new Json(source);
        json.skipWhitespace();
        Object value = json.readValue();
        json.skipWhitespace();
        if (json.at != source.length()) {
            throw new JsonException("trailing text after the value", json.at);
        }
        return value;
    }

    /**
     * Parses a JSON object.
     *
     * @param source the text
     * @return the object's fields
     * @throws JsonException when the text is not a well-formed JSON object
     */
    static Map<String, Object> parseObject(String source) {
        Object value = parse(source);
        if (!(value instanceof Map<?, ?> map)) {
            throw new JsonException(
                    "expected a JSON object, got "
                            + (value == null ? "null" : value.getClass().getSimpleName()),
                    0);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) map;
        return fields;
    }

    private Object readValue() {
        if (at >= source.length()) {
            throw new JsonException("expected a value", at);
        }
        char c = source.charAt(at);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readKeyword("true", Boolean.TRUE);
            case 'f' -> readKeyword("false", Boolean.FALSE);
            case 'n' -> readKeyword("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        enter();
        Map<String, Object> fields = new LinkedHashMap<>();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            at++;
            leave();
            return fields;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            fields.put(key, readValue());
            skipWhitespace();
            char c = next();
            if (c == '}') {
                leave();
                return fields;
            }
            if (c != ',') {
                throw new JsonException("expected ',' or '}' in an object", at - 1);
            }
        }
    }

    private List<Object> readArray() {
        enter();
        List<Object> items = new ArrayList<>();
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            at++;
            leave();
            return items;
        }
        while (true) {
            skipWhitespace();
            items.add(readValue());
            skipWhitespace();
            char c = next();
            if (c == ']') {
                leave();
                return items;
            }
            if (c != ',') {
                throw new JsonException("expected ',' or ']' in an array", at - 1);
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                if (c < 0x20) {
                    throw new JsonException("a control character must be escaped in a string", at - 1);
                }
                out.append(c);
                continue;
            }
            char escape = next();
            switch (escape) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> out.append(readUnicodeEscape());
                default -> throw new JsonException("unknown escape \\" + escape, at - 1);
            }
        }
    }

    private void enter() {
        if (++depth > MAX_DEPTH) {
            throw new JsonException("nested more than " + MAX_DEPTH + " deep", at);
        }
    }

    private void leave() {
        depth--;
    }

    private char readUnicodeEscape() {
        if (at + 4 > source.length()) {
            throw new JsonException("a \\u escape needs four hex digits", at);
        }
        String hex = source.substring(at, at + 4);
        at += 4;
        try {
            return (char) Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            throw new JsonException("a \\u escape needs four hex digits, got \"" + hex + "\"", at - 4);
        }
    }

    private Object readNumber() {
        int start = at;
        if (peek() == '-') {
            at++;
        }
        while (at < source.length() && isNumberChar(source.charAt(at))) {
            at++;
        }
        String text = source.substring(start, at);
        if (text.isEmpty() || text.equals("-")) {
            throw new JsonException("expected a value", start);
        }
        boolean integral = text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0;
        try {
            return integral ? (Object) Long.valueOf(text) : (Object) Double.valueOf(text);
        } catch (NumberFormatException integerTooBig) {
            try {
                // A count past 2^63 is still a number a caller may want to read.
                return Double.valueOf(text);
            } catch (NumberFormatException notANumber) {
                throw new JsonException("not a number: \"" + text + "\"", start);
            }
        }
    }

    private static boolean isNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
    }

    private Object readKeyword(String keyword, Object value) {
        if (!source.startsWith(keyword, at)) {
            throw new JsonException("expected " + keyword, at);
        }
        at += keyword.length();
        return value;
    }

    private void skipWhitespace() {
        while (at < source.length()) {
            char c = source.charAt(at);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            at++;
        }
    }

    private char peek() {
        if (at >= source.length()) {
            throw new JsonException("unexpected end of input", at);
        }
        return source.charAt(at);
    }

    private char next() {
        char c = peek();
        at++;
        return c;
    }

    private void expect(char expected) {
        char c = next();
        if (c != expected) {
            throw new JsonException("expected '" + expected + "', got '" + c + "'", at - 1);
        }
    }
}
