package inc.reactor.sdk.internal

import inc.reactor.sdk.ControlEvent
import inc.reactor.sdk.DecodeFailedError
import inc.reactor.sdk.ErrorDetails
import inc.reactor.sdk.ReactorStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** The event worker owns this state, never the public Reactor wrapper. */
internal class ControlEvents(
    dispatcher: CoroutineDispatcher,
    private val report: (Throwable) -> Unit,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<ControlEvent>(Channel.UNLIMITED)
    private val listeners = CopyOnWriteArrayList<(ControlEvent) -> Unit>()
    private val closed = AtomicBoolean(false)
    private val reported = mutableSetOf<String>()

    init {
        val weak = WeakReference(this)
        val incoming = queue
        scope.launch {
            // Even Unconfined/custom inline dispatchers must start off the native thread.
            for (event in incoming) withContext(dispatcher) { weak.get()?.dispatch(event) }
        }
    }

    private fun dispatch(event: ControlEvent) {
        for (listener in listeners) {
            if (closed.get()) break
            try {
                listener(event)
            } catch (failure: Throwable) {
                val key = "${failure.javaClass.name}: ${failure.message}"
                if (reported.add(key)) runCatching { report(failure) }
            }
        }
    }

    fun subscribe(listener: (ControlEvent) -> Unit): AutoCloseable {
        check(!closed.get()) { "Client is closed" }
        listeners.add(listener)
        if (closed.get()) listeners.remove(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    @Suppress("UNUSED_PARAMETER")
    fun accept(
        kind: Int,
        text: ByteArray?,
        data: ByteArray?,
    ) {
        if (closed.get()) return
        val event =
            try {
                val value = text?.decodeToString(throwOnInvalidSequence = true)

                fun objectValue(): JsonObject = Json.parseToJsonElement(requireNotNull(value)) as JsonObject
                when (kind) {
                    0 -> ControlEvent.StatusChanged(ReactorStatus.fromWire(requireNotNull(value)))
                    1 -> ControlEvent.Error(decodeError(objectValue()))
                    2 -> ControlEvent.Message(objectValue())
                    3 -> ControlEvent.RuntimeMessage(objectValue())
                    4 -> ControlEvent.CapabilitiesChanged(objectValue())
                    5 -> ControlEvent.SessionChanged(value)
                    6 -> ControlEvent.TrackReceived(requireNotNull(value), data?.decodeToString(throwOnInvalidSequence = true))
                    else -> error("Unknown control event $kind")
                }
            } catch (failure: Exception) {
                ControlEvent.Error(DecodeFailedError(ErrorDetails("DECODE_FAILED", "Invalid control event: ${failure.message}")))
            }
        emit(event)
    }

    fun emit(event: ControlEvent) {
        queue.trySend(event)
    }

    override fun close() {
        closed.set(true)
        listeners.clear()
        queue.cancel()
        scope.cancel() // Never joins a handler that requested shutdown itself.
    }
}
