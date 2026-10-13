package inc.reactor.sdk.kotlin

import inc.reactor.sdk.ConnectionStatus
import inc.reactor.sdk.FakeClients
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The control-event flows, and the thing that would leak if one of them stopped unsubscribing. */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowsTest : FacadeFixture() {

    private fun fireStatus(status: String) {
        fake.fireCallback(
            "on_status",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            fake.cString(status),
            MemorySegment.NULL,
        )
    }

    @Test
    fun `a status flow carries every change, in order`() = runBlocking {
        val collecting = async(Dispatchers.Default) { client.statusFlow().take(3).toList() }
        awaitSubscribed()
        fireStatus("connecting")
        fireStatus("ready")
        fireStatus("disconnected")
        assertEquals(
            listOf(
                ConnectionStatus.CONNECTING,
                ConnectionStatus.READY,
                ConnectionStatus.DISCONNECTED,
            ),
            collecting.await(),
        )
    }

    @Test
    fun `a collector that goes away unregisters its handler`() = runBlocking {
        // take(1) completes the flow after the first event, which is what runs awaitClose.
        val first = async(Dispatchers.Default) { client.statusFlow().first() }
        awaitSubscribed()
        fireStatus("ready")
        assertEquals(ConnectionStatus.READY, first.await())

        // Nothing is collecting any more. If the subscription were still registered, this would
        // reach a channel nobody owns — and the leak is not visible from the flow's side, so it is
        // asserted from the SDK's: the client has no status handler left.
        assertEquals(0, handlerCount())
    }

    @Test
    fun `every flow unregisters, not only the one that was tested`() = runBlocking {
        val flows =
            listOf(
                client.statusFlow(),
                client.errorFlow(),
                client.messageFlow(),
                client.runtimeMessageFlow(),
                client.capabilitiesFlow(),
                client.sessionIdFlow(),
            )
        for (flow in flows) {
            val collecting = async(Dispatchers.Default) { flow.take(1).toList() }
            awaitSubscribed()
            fireStatus("ready")
            // Only the status flow answers a status event; the rest are cancelled instead, which
            // runs the same awaitClose the completion path does.
            collecting.cancel()
            withContext(Dispatchers.Default) { collecting.join() }
        }
        assertEquals(0, handlerCount())
    }

    /**
     * How many handlers the client is holding, across every control event.
     *
     * Asked of the SDK rather than of the flow: whether the *SDK* still holds a reference is the
     * thing that leaks, and the flow's own side of it looks identical either way.
     */
    private fun handlerCount(): Int = FakeClients.controlHandlerCount(client.java)

    /** callbackFlow registers on the collector's thread; firing before it has is a lost event. */
    private fun awaitSubscribed() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (handlerCount() == 0) {
            check(System.nanoTime() < deadline) { "no handler registered within 5s" }
            Thread.sleep(1)
        }
    }
}
