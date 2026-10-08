package inc.reactor.sdk.android

import inc.reactor.sdk.android.internal.Json
import inc.reactor.sdk.android.internal.JsonException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-rolled parser, directly.
 *
 * It had no tests of its own: ErrorsTest and CompletionsTest reached it only through simple
 * payloads, so a mis-parse of a *valid* one failed nothing. That is the dangerous direction —
 * a malformed payload raises and is obvious, while a valid payload read wrongly silently demotes
 * a typed platform error to INTERNAL_ERROR, or loses a field nobody notices is missing.
 *
 * Escapes, unicode escapes and number forms are where a small parser is wrong, so they are what
 * this covers.
 */
class JsonTest {
    private fun obj(text: String) = Json.parseObject(text)

    // ── Strings and escapes ──────────────────────────────────────────────────

    @Test
    fun `the simple escapes all decode`() {
        val fields = obj("""{"a":"q\"q","b":"back\\slash","c":"a\/b","d":"tab\there"}""")
        assertEquals("q\"q", fields["a"])
        assertEquals("back\\slash", fields["b"])
        assertEquals("a/b", fields["c"])
        assertEquals("tab\there", fields["d"])
    }

    @Test
    fun `the control escapes decode to their characters, not their letters`() {
        val fields = obj("""{"n":"a\nb","r":"a\rb","f":"a\fb","bs":"a\bb"}""")
        assertEquals("a\nb", fields["n"])
        assertEquals("a\rb", fields["r"])
        assertEquals("a\u000Cb", fields["f"])
        assertEquals("a\bb", fields["bs"])
    }

    @Test
    fun `a unicode escape decodes`() {
        assertEquals("é", obj("""{"a":"é"}""")["a"])
        assertEquals("日", obj("""{"a":"日"}""")["a"])
        // A surrogate pair is two escapes and has to survive as one character.
        assertEquals("🌲", obj("""{"a":"🌲"}""")["a"])
    }

    @Test
    fun `an escaped quote inside a value does not end the string`() {
        val fields = obj("""{"message":"the model said \"no\"","code":"BAD_REQUEST"}""")
        assertEquals("""the model said "no"""", fields["message"])
        // The real risk: a value whose escape is mishandled swallows the rest of the object.
        assertEquals("BAD_REQUEST", fields["code"])
    }

    // ── Numbers ──────────────────────────────────────────────────────────────

    @Test
    fun `number forms parse`() {
        val fields = obj("""{"a":0,"b":-7,"c":12.5,"d":-0.25,"e":1e3,"f":2.5E-2,"g":1E+2}""")
        assertEquals(0.0, fields["a"] as Double, 0.0)
        assertEquals(-7.0, fields["b"] as Double, 0.0)
        assertEquals(12.5, fields["c"] as Double, 0.0)
        assertEquals(-0.25, fields["d"] as Double, 0.0)
        assertEquals(1000.0, fields["e"] as Double, 0.0)
        assertEquals(0.025, fields["f"] as Double, 1e-12)
        assertEquals(100.0, fields["g"] as Double, 0.0)
    }

    /** `status` and `retry_after_ms` are read through these casts, so the type matters. */
    @Test
    fun `an integral number is still a Double, which is what the payload readers expect`() {
        assertTrue(obj("""{"status":404}""")["status"] is Double)
        assertEquals(404, (obj("""{"status":404}""")["status"] as Double).toInt())
    }

    // ── Literals, nesting, shape ─────────────────────────────────────────────

    @Test
    fun `literals parse`() {
        val fields = obj("""{"t":true,"f":false,"n":null}""")
        assertEquals(true, fields["t"])
        assertEquals(false, fields["f"])
        assertNull(fields["n"])
        assertTrue("an explicit null must still be a present key", fields.containsKey("n"))
    }

    @Test
    fun `nested objects and arrays parse`() {
        val fields = obj("""{"a":{"b":{"c":[1,"two",{"d":true}]}}}""")

        @Suppress("UNCHECKED_CAST")
        val a = fields["a"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val b = a["b"] as Map<String, Any?>
        val c = b["c"] as List<*>
        assertEquals(3, c.size)
        assertEquals("two", c[1])
        @Suppress("UNCHECKED_CAST")
        assertEquals(true, (c[2] as Map<String, Any?>)["d"])
    }

    @Test
    fun `whitespace between every token is tolerated`() {
        val fields = obj("  {  \"a\"  :  1  ,  \"b\"  :  [  1  ,  2  ]  }  ")
        assertEquals(1.0, fields["a"] as Double, 0.0)
        assertEquals(2, (fields["b"] as List<*>).size)
    }

    @Test
    fun `an empty object and an empty array parse`() {
        assertTrue(obj("{}").isEmpty())
        assertEquals(0, (obj("""{"a":[]}""")["a"] as List<*>).size)
    }

    // ── Refusals ─────────────────────────────────────────────────────────────

    @Test
    fun `malformed input raises rather than returning a partial object`() {
        for (bad in listOf("{", """{"a"}""", """{"a":}""", """{"a":1,}""", "{'a':1}", """{"a":1""", "")) {
            assertThrows("'$bad' must not parse", JsonException::class.java) { obj(bad) }
        }
    }

    @Test
    fun `trailing content after a complete value is refused`() {
        // Otherwise two concatenated payloads read as the first one, silently.
        assertThrows(JsonException::class.java) { obj("""{"a":1}{"b":2}""") }
        assertThrows(JsonException::class.java) { obj("""{"a":1} garbage""") }
    }

    @Test
    fun `a non-object top level is refused by parseObject`() {
        assertThrows(JsonException::class.java) { obj("[1,2]") }
        assertThrows(JsonException::class.java) { obj("\"a\"") }
        assertThrows(JsonException::class.java) { obj("7") }
    }

    @Test
    fun `an unterminated string raises rather than running off the end`() {
        assertThrows(JsonException::class.java) { obj("""{"a":"unterminated""") }
        assertThrows(JsonException::class.java) { obj("""{"a":"bad escape \q"}""") }
        assertThrows(JsonException::class.java) { obj("""{"a":"\u00"}""") }
    }
}
