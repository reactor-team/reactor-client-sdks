package inc.reactor.sdk.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the bridge's message callbacks reach [Reactor.messages] and [Reactor.runtimeMessages].
 *
 * Driven through the same listener the native bridge calls, on an unconfined dispatcher, so each
 * delivery and each collector step runs inline and the order of events is the order of calls.
 */
class MessagesTest {
    private val reactor = Reactor(ReactorOptions(jwt = "t"), Dispatchers.Unconfined)
    private val events = Reactor.Events(reactor)

    /** Collect [flow] into a list for the duration of [block]. */
    private fun collecting(
        flow: SharedFlow<Map<String, Any?>>,
        block: () -> Unit,
    ): List<Map<String, Any?>> =
        runBlocking {
            val seen = mutableListOf<Map<String, Any?>>()
            val job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { flow.collect { seen += it } }
            block()
            job.cancel()
            seen
        }

    @Test
    fun `a model message reaches messages intact and not runtimeMessages`() {
        var runtime: List<Map<String, Any?>> = emptyList()
        val model =
            collecting(reactor.messages) {
                runtime =
                    collecting(reactor.runtimeMessages) {
                        events.onMessage("""{"type":"state","data":{"frame":7,"label":"x"}}""")
                    }
            }
        assertEquals(listOf(mapOf("type" to "state", "data" to mapOf("frame" to 7.0, "label" to "x"))), model)
        assertTrue(runtime.isEmpty())
    }

    @Test
    fun `a runtime message reaches runtimeMessages and not messages`() {
        var runtime: List<Map<String, Any?>> = emptyList()
        val model =
            collecting(reactor.messages) {
                runtime =
                    collecting(reactor.runtimeMessages) {
                        events.onRuntimeMessage("""{"type":"recording_ready","data":null}""")
                    }
            }
        assertEquals(listOf(mapOf("type" to "recording_ready", "data" to null)), runtime)
        assertTrue(model.isEmpty())
    }

    @Test
    fun `null, malformed and non-object JSON are dropped without stopping later messages`() {
        val seen =
            collecting(reactor.messages) {
                events.onMessage(null)
                events.onMessage("{not json")
                events.onMessage("""[1,2]""")
                events.onMessage("\"text\"")
                events.onMessage("""{"type":"after"}""")
            }
        assertEquals(listOf(mapOf("type" to "after")), seen)
    }

    /**
     * Emitted on the calling thread, not launched onto the client's dispatcher: a launch per
     * message reorders them on a multi-threaded dispatcher and queues without bound behind a busy
     * one. A dispatcher that never runs makes the difference visible.
     */
    @Test
    fun `a message reaches collectors without going through the client's dispatcher`() {
        val stalled = Reactor(ReactorOptions(jwt = "t"), StandardTestDispatcher())
        val listener = Reactor.Events(stalled)
        val seen = collecting(stalled.messages) { listener.onMessage("""{"type":"state"}""") }
        assertEquals(listOf(mapOf("type" to "state")), seen)
    }

    @Test
    fun `messages arrive in the order they were sent`() {
        val seen =
            collecting(reactor.messages) {
                for (n in 0 until 20) events.onMessage("""{"type":"tick","data":$n}""")
            }
        assertEquals((0 until 20).map { it.toDouble() }, seen.map { it["data"] })
    }

    @Test
    fun `a collector that falls behind loses the oldest messages, never the newest`() {
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val seen = mutableListOf<Double>()
            val job =
                launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    reactor.messages.collect {
                        seen += it["data"] as Double
                        gate.await() // Stuck on the first message while the rest arrive.
                    }
                }
            // Never suspends the caller, which is the FFI's event thread.
            for (n in 0 until 100) events.onMessage("""{"type":"tick","data":$n}""")
            gate.complete(Unit)
            job.cancel()

            // The one being handled, then the newest 64 the buffer kept.
            assertEquals(listOf(0.0) + (36 until 100).map { it.toDouble() }, seen)
        }
    }
}
