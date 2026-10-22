package inc.reactor.sdk.android.internal

/**
 * A minimal JSON reader.
 *
 * Hand-rolled rather than `org.json` or a serialization library, for two reasons. `org.json` is
 * an Android platform class that is *stubbed* in JVM unit tests — every call throws "not mocked"
 * — so anything built on it can only be tested on a device, which is the wrong place to test a
 * parser. And a binding should not put a serialization dependency on every consumer's classpath
 * to read its own error payloads. The Java SDK made the same call for the same reasons.
 *
 * Deliberately small: objects, arrays, strings, numbers, booleans and null, which is the whole of
 * what this ABI sends. It will grow for command payloads in A08.
 */
internal object Json {
    /** Parse a JSON document. Throws [JsonException] on anything malformed. */
    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd()) reader.fail("trailing content")
        return value
    }

    /** Parse a document expected to be an object, or throw. */
    fun parseObject(text: String): Map<String, Any?> {
        val value = parse(text)
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?>
            ?: throw JsonException("expected a JSON object, got ${value?.let { it::class.simpleName } ?: "null"}")
    }

    /**
     * One JSON string literal.
     *
     * The writing half is this one function: the strings this binding writes are names and ids —
     * a model, a session, a step command — and none justifies a serialization dependency on every
     * consumer's classpath. A name with a quote in it is not something the platform accepts, but a
     * malformed request here is a coordinator's 400 — escaping it is a client one.
     */
    fun quote(value: String): String =
        buildString(value.length + 2) {
            append('"')
            for (c in value) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }

    private class Reader(
        private val text: String,
    ) {
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun fail(what: String): Nothing = throw JsonException("$what at offset $index")

        /** JSON's four whitespace characters only: `isWhitespace` also takes form feed and Unicode separators. */
        fun skipWhitespace() {
            while (index < text.length && text[index] in " \t\r\n") index++
        }

        fun readValue(): Any? {
            if (atEnd()) fail("unexpected end of input")
            return when (text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            index++ // {
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!atEnd() && text[index] == '}') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || text[index] != '"') fail("expected a key")
                val key = readString()
                skipWhitespace()
                if (atEnd() || text[index] != ':') fail("expected ':'")
                index++
                skipWhitespace()
                result[key] = readValue()
                skipWhitespace()
                if (atEnd()) fail("unterminated object")
                when (text[index]) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return result
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            index++ // [
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (!atEnd() && text[index] == ']') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                result += readValue()
                skipWhitespace()
                if (atEnd()) fail("unterminated array")
                when (text[index]) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return result
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            index++ // opening quote
            val builder = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                when (val c = text[index++]) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        if (atEnd()) fail("unterminated escape")
                        when (val escape = text[index++]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) fail("truncated \\u escape")
                                val hex = text.substring(index, index + 4)
                                index += 4
                                builder.append(
                                    hex.toIntOrNull(16)?.toChar() ?: fail("invalid \\u escape"),
                                )
                            }
                            else -> fail("unknown escape '\\$escape'")
                        }
                    }
                    // JSON requires these escaped; a raw newline, tab or NUL is a malformed payload.
                    in '\u0000'..'\u001F' -> {
                        index--
                        fail("unescaped control character U+%04X".format(c.code))
                    }
                    else -> builder.append(c)
                }
            }
        }

        private fun <T> readLiteral(
            literal: String,
            value: T,
        ): T {
            if (!text.startsWith(literal, index)) fail("expected '$literal'")
            index += literal.length
            return value
        }

        /**
         * A number, by JSON's own grammar.
         *
         * Matched before conversion rather than handed to `toDoubleOrNull`, which accepts forms
         * JSON forbids — `+1`, `01`, `1.`, `.5` — and would let a malformed payload read as a
         * valid one instead of taking the malformed-payload path.
         */
        private fun readNumber(): Double {
            val match =
                NUMBER.matchAt(text, index)
                    ?: throw JsonException("invalid number at offset $index")
            index = match.range.last + 1
            // A number running straight into more number-like characters is malformed, not two
            // tokens: `01` matches `0` and would otherwise leave `1` to fail somewhere less clear.
            if (!atEnd() && (text[index] in "0123456789+-.eE")) {
                throw JsonException("invalid number '${text.substring(match.range.first, index + 1)}'")
            }
            return match.value.toDouble()
        }
    }
}

/** JSON's number grammar, ASCII digits only: `Char.isDigit` also accepts other scripts' digits. */
private val NUMBER = Regex("""-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?""")

/** A payload that did not parse. Distinct from a payload that was absent. */
internal class JsonException(
    message: String,
) : Exception(message)
