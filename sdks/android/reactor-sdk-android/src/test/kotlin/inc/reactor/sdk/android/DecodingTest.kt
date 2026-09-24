package inc.reactor.sdk.android

import inc.reactor.sdk.android.internal.JsonException
import inc.reactor.sdk.android.internal.decodeCommandReply
import inc.reactor.sdk.android.internal.decodeSchema
import inc.reactor.sdk.android.internal.decodeStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How successful completions become results.
 *
 * Each decoder runs before the promise is claimed, so what it throws becomes DECODE_FAILED for
 * the caller. The distinctions below are the ones that make a malformed answer visible instead of
 * masquerading as an empty one.
 */
class DecodingTest {
    @Test
    fun `a full reply keeps its type and data`() {
        val reply = decodeCommandReply("""{"type":"status","data":{"fps":30}}""")
        assertEquals("status", reply.type)
        @Suppress("UNCHECKED_CAST")
        assertEquals(30.0, (reply.data as Map<String, Any?>)["fps"])
        assertTrue(!reply.isEmpty)
    }

    /** The ABI documents an absent payload for a handler that acknowledged and said nothing. */
    @Test
    fun `an absent reply is success with nothing to report`() {
        assertTrue(decodeCommandReply(null).isEmpty)
        assertTrue(decodeCommandReply("").isEmpty)
    }

    @Test
    fun `a malformed reply throws rather than reading as empty`() {
        assertThrows(JsonException::class.java) { decodeCommandReply("{not json") }
    }

    @Test
    fun `a reply with neither field is empty but valid`() {
        val reply = decodeCommandReply("{}")
        assertTrue(reply.isEmpty)
        assertNull(reply.type)
    }

    /**
     * The distinction the skill singles out: substituting an empty schema makes a model that
     * declares nothing indistinguishable from a request that failed to carry one.
     */
    @Test
    fun `an absent schema throws rather than becoming an empty schema`() {
        val error = assertThrows(JsonException::class.java) { decodeSchema(null) }
        assertTrue(error.message!!.contains("different answers"))
    }

    @Test
    fun `a schema that is genuinely empty is a valid answer`() {
        assertEquals(emptyMap<String, Any?>(), decodeSchema("{}"))
    }

    @Test
    fun `a malformed schema throws`() {
        assertThrows(JsonException::class.java) { decodeSchema("[1,2,3]") }
    }

    @Test
    fun `stats keep every measurement, including ones this SDK does not know`() {
        val stats = decodeStats("""{"rtt_ms":12.5,"bytes_sent":99,"some_new_metric":1}""")
        assertEquals(12.5, stats.number("rtt_ms"))
        assertEquals(99.0, stats.number("bytes_sent"))
        assertTrue(
            "a measurement set is the platform's to extend; dropping unknowns loses new ones",
            stats.raw.containsKey("some_new_metric"),
        )
    }

    /** Counters here are signed, and coercing them is how a negative one becomes a wrong answer. */
    @Test
    fun `a negative counter survives as a negative number`() {
        assertEquals(-1.0, decodeStats("""{"drift":-1}""").number("drift"))
    }

    @Test
    fun `a missing measurement is null rather than zero`() {
        assertNull(decodeStats("{}").number("rtt_ms"))
    }
}
