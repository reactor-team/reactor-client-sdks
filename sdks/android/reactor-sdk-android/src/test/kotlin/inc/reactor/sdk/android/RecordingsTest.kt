package inc.reactor.sdk.android

import inc.reactor.sdk.android.internal.Completions
import inc.reactor.sdk.android.internal.JsonException
import inc.reactor.sdk.android.internal.Recordings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Clip decoding, the readiness rules, and what a download does when its client goes away. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingsTest {
    @Test
    fun `a clip keeps every field the platform sent`() {
        val clip =
            Recordings.decodeClip(
                """{"playlist_url":"https://c/p.m3u8","session_id":"s-1","kind":"snap",
                "predicted_ready_at_ms":1700000000000,"something_new":true}""",
            )
        assertEquals("https://c/p.m3u8", clip.playlistUrl)
        assertEquals("s-1", clip.sessionId)
        assertEquals(1.7e12, clip.predictedReadyAtMs)
        assertTrue("unknown fields survive in raw", clip.raw.containsKey("something_new"))
    }

    @Test
    fun `a clip with no playlist url is a decode failure`() {
        assertThrows(JsonException::class.java) { Recordings.decodeClip("""{"kind":"snap"}""") }
        assertThrows(JsonException::class.java) { Recordings.decodeClip(null) }
    }

    @Test
    fun `a clip without a prediction is null rather than zero`() {
        val clip = Recordings.decodeClip("""{"playlist_url":"u"}""")
        assertNull(clip.predictedReadyAtMs)
        // 0 is what the ABI wants for "no prediction" — run the grace from now.
        assertEquals(0.0, Recordings.predictedReadyAtMs(clip), 0.0)
    }

    @Test
    fun `a download result needs a path`() {
        assertEquals(
            DownloadedClip("/tmp/c.mp4", 1024, 8),
            Recordings.decodeDownload("""{"path":"/tmp/c.mp4","bytes":1024,"segments":8}"""),
        )
        assertThrows(JsonException::class.java) { Recordings.decodeDownload("""{"bytes":1}""") }
    }

    // ── The readiness rules ──────────────────────────────────────────────────

    /**
     * Readiness is in media time. A model at a tenth of real time reaches the boundary ten times
     * later than any wall-clock guess, so "wait as long as the session lives" is the default.
     */
    @Test
    fun `null and negative both mean wait as long as the session lives`() {
        assertTrue(Recordings.readyTimeoutSeconds(null) < 0)
        assertEquals(-5.0, Recordings.readyTimeoutSeconds(-5.0), 0.0)
    }

    @Test
    fun `an infinite timeout is passed through, not clamped`() {
        assertTrue(Recordings.readyTimeoutSeconds(Double.POSITIVE_INFINITY).isInfinite())
    }

    @Test
    fun `NaN is refused on the calling thread rather than through the completion`() {
        val error =
            assertThrows(BadRequestException::class.java) {
                Recordings.readyTimeoutSeconds(Double.NaN)
            }
        assertTrue(error.message!!.contains("session lives"))
    }

    @Test
    fun `a real grace is passed through unchanged`() {
        assertEquals(30.0, Recordings.readyTimeoutSeconds(30.0), 0.0)
    }

    // ── Outliving the client ─────────────────────────────────────────────────

    /**
     * The finding the C++ SDK paid for, from the other side: teardown must settle the caller
     * rather than drop them, and must say the download may still be running.
     */
    @Test
    fun `closing while a download is in flight settles the caller, saying it may continue`() =
        runTest {
            val ticket = CompletableDeferred<Long>()
            val result =
                async {
                    runCatching {
                        Completions.await("download_clip", decode = { it }) { ticket.complete(it) }
                    }
                }
            ticket.await()
            Completions.abandonAll("The Reactor was closed. Native work already started may still be running.")
            val error = awaitFailure<AbortedException>(result)
            assertTrue(
                "a download whose client went away is still downloading; saying 'aborted' is worse than saying nothing",
                error.message!!.contains("may still be running"),
            )
        }

    /**
     * And from the native side: a completion for a ticket nobody holds must be a no-op. This is
     * what makes a late callback safe by construction rather than by timing — there is no object
     * whose lifetime could have ended, only a number that is no longer in a map.
     */
    @Test
    fun `a completion arriving after teardown touches nothing`() {
        Completions.settle(ticket = 987_654L, ok = true, resultJson = """{"path":"/x"}""", errorJson = null)
        Completions.progressFromNative(ticket = 987_654L, done = 3, total = 8)
    }

    @Test
    fun `progress reaches a registered handler and stops after it settles`() =
        runTest {
            val seen = mutableListOf<ClipProgress>()
            val ticket = CompletableDeferred<Long>()
            val result =
                async {
                    Completions.await("download_clip", decode = { it ?: "done" }) { t ->
                        Completions.watchProgress(t) { done, total -> seen += ClipProgress(done, total) }
                        ticket.complete(t)
                    }
                }
            val id = ticket.await()
            Completions.progressFromNative(id, 1, 4)
            Completions.progressFromNative(id, 2, 4)
            Completions.settle(id, ok = true, resultJson = null, errorJson = null)
            result.await()

            // Late progress, after the completion: the handler is gone and this must be harmless.
            Completions.progressFromNative(id, 3, 4)

            assertEquals(listOf(ClipProgress(1, 4), ClipProgress(2, 4)), seen)
            assertEquals(0.5, seen.last().fraction)
        }

    private suspend inline fun <reified E : Throwable> awaitFailure(deferred: Deferred<Result<*>>): E {
        val thrown = deferred.await().exceptionOrNull()
        assertTrue("expected ${E::class.simpleName}, got $thrown", thrown is E)
        return thrown as E
    }
}
