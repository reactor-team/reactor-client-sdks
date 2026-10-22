package inc.reactor.sdk.android

import inc.reactor.sdk.android.internal.Completions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The completion registry: settle once, decode before claiming, and never leave a caller waiting.
 *
 * The failing paths capture their exception with `runCatching` *inside* the coroutine rather than
 * letting `await()` rethrow it. Under structured concurrency a failing `async` cancels its parent
 * — here `runTest`'s own scope — so catching at the await site is too late and the whole test
 * fails with the right exception for the wrong reason. This suite got that wrong first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompletionsTest {
    @Test
    fun `a successful completion resolves with the decoded value`() =
        runTest {
            val ticket = CompletableDeferred<Long>()
            val result =
                async {
                    Completions.await("connect", decode = { it ?: "none" }) { ticket.complete(it) }
                }
            Completions.settle(ticket.await(), ok = true, resultJson = "{}", errorJson = null)
            assertEquals("{}", result.await())
        }

    @Test
    fun `a failed completion throws the typed error`() =
        runTest {
            val ticket = CompletableDeferred<Long>()
            val result =
                async {
                    runCatching { Completions.await("send_command", decode = { it }) { ticket.complete(it) } }
                }
            Completions.settle(
                ticket.await(),
                ok = false,
                resultJson = null,
                errorJson = """{"code":"RATE_LIMITED","message":"slow down","retry_after_ms":250}""",
            )
            val error = awaitFailure<RateLimitedException>(result)
            assertEquals(250L, error.retryAfterMs)
            assertTrue(error.recoverable)
        }

    /**
     * The failure the ordering exists to prevent: if the promise were claimed first and the
     * payload converted after, this throw would land where nothing can be settled any more and
     * the caller would hang instead of seeing DECODE_FAILED.
     */
    @Test
    fun `a success whose payload will not decode fails with DECODE_FAILED`() =
        runTest {
            val ticket = CompletableDeferred<Long>()
            val result =
                async {
                    runCatching {
                        Completions.await<String>("request_schema", decode = { error("bad shape") }) {
                            ticket.complete(it)
                        }
                    }
                }
            Completions.settle(ticket.await(), ok = true, resultJson = """{"unexpected":1}""", errorJson = null)
            val error = awaitFailure<DecodeFailedException>(result)
            assertTrue(
                "the raw payload belongs in the message — it is what a decode bug is diagnosed from",
                error.message!!.contains("unexpected"),
            )
        }

    /** An absent payload is an answer: a void operation reports nothing and that is success. */
    @Test
    fun `an absent payload is distinct from a malformed one`() =
        runTest {
            val ticket = CompletableDeferred<Long>()
            val result =
                async {
                    Completions.await("disconnect", decode = { it == null }) { ticket.complete(it) }
                }
            Completions.settle(ticket.await(), ok = true, resultJson = null, errorJson = null)
            assertEquals(true, result.await())
        }

    @Test
    fun `settling twice is ignored`() =
        runTest {
            val ticket = CompletableDeferred<Long>()
            val result =
                async {
                    Completions.await("connect", decode = { it ?: "first" }) { ticket.complete(it) }
                }
            val id = ticket.await()
            Completions.settle(id, ok = true, resultJson = "first", errorJson = null)
            // A second completion for the same ticket must not throw, and must not change the answer.
            Completions.settle(id, ok = false, resultJson = null, errorJson = """{"code":"CONFLICT"}""")
            assertEquals("first", result.await())
        }

    @Test
    fun `an unknown ticket is dropped rather than throwing`() {
        Completions.settle(ticket = 999_999L, ok = true, resultJson = "{}", errorJson = null)
    }

    /** Teardown must settle waiters: an unresolved promise waits for the life of the process. */
    @Test
    fun `abandonAll settles outstanding callers`() =
        runTest {
            val ticket = CompletableDeferred<Long>()
            val result =
                async {
                    runCatching { Completions.await("download_clip", decode = { it }) { ticket.complete(it) } }
                }
            ticket.await()
            Completions.abandonAll("the client was closed; the download may still be writing")
            val error = awaitFailure<AbortedException>(result)
            assertTrue(
                "the message should say the native work may continue, not claim it stopped",
                error.message!!.contains("may still be writing"),
            )
        }

    @Test
    fun `a failure to start removes the ticket`() =
        runTest {
            val before = Completions.pendingCount
            val result =
                async {
                    runCatching {
                        Completions.await<String>("connect", decode = { it!! }) {
                            error("the native call refused")
                        }
                    }
                }
            awaitFailure<IllegalStateException>(result)
            assertEquals(
                "a completion that never started must not stay pending forever",
                before,
                Completions.pendingCount,
            )
        }

    // ── timeoutMillis ────────────────────────────────────────────────────────

    @Test
    fun `NaN is a caller bug rather than a duration`() {
        val error =
            assertThrows(BadRequestException::class.java) {
                Completions.timeoutMillis(Double.NaN)
            }
        assertEquals("BAD_REQUEST", error.code)
    }

    @Test
    fun `infinite and negative both mean no bound`() {
        assertEquals(0L, Completions.timeoutMillis(Double.POSITIVE_INFINITY))
        assertEquals(0L, Completions.timeoutMillis(Double.NEGATIVE_INFINITY))
        assertEquals(0L, Completions.timeoutMillis(-1.0))
        assertEquals(0L, Completions.timeoutMillis(null))
    }

    @Test
    fun `an enormous timeout saturates rather than wrapping`() {
        assertEquals(Long.MAX_VALUE, Completions.timeoutMillis(Double.MAX_VALUE))
        assertEquals(1500L, Completions.timeoutMillis(1.5))
    }

    /**
     * Await a [Deferred] expecting it to fail with [E].
     *
     * Written out rather than wrapping `assertThrows` around a `runBlocking`: nesting a blocking
     * builder inside `runTest`'s scheduler deadlocks, which is how the first version of this
     * suite failed. Awaiting in the test's own coroutine is both correct and the thing being
     * tested — the caller really does suspend here.
     */
    private suspend inline fun <reified E : Throwable> awaitFailure(deferred: Deferred<Result<*>>): E {
        val thrown = deferred.await().exceptionOrNull()
        assertTrue(
            "expected ${E::class.simpleName}, got ${thrown?.let { it::class.simpleName } ?: "success"}",
            thrown is E,
        )
        return thrown as E
    }
}
