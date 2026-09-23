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

    private class Reader(
        private val text: String,
    ) {
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun fail(what: String): Nothing = throw JsonException("$what at offset $index")

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
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

        private fun readNumber(): Double {
            val start = index
            if (!atEnd() && (text[index] == '-' || text[index] == '+')) index++
            while (!atEnd() && (text[index].isDigit() || text[index] in ".eE+-")) index++
            val slice = text.substring(start, index)
            return slice.toDoubleOrNull() ?: throw JsonException("invalid number '$slice'")
        }
    }
}

/** A payload that did not parse. Distinct from a payload that was absent. */
internal class JsonException(
    message: String,
) : Exception(message)
