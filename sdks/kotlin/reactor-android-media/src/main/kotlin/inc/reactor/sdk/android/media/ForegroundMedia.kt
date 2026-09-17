package inc.reactor.sdk.android.media

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Explicitly enabled foreground sessions. Construction never registers an observer or starts hardware.
 * Each start/re-entry uses a fresh resource scope; teardown finishes before the next factory runs.
 */
class ForegroundMedia(
    private val lifecycle: Lifecycle,
    private val onFailure: (Throwable) -> Unit = { System.err.println("Reactor Android media: $it") },
    private val create: suspend MediaResources.() -> Unit,
) : AndroidMediaResource {
    private data class Activation(
        val enabled: Boolean,
        val generation: Long,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val state = MutableStateFlow(Activation(false, 0))
    private var generation = 0L
    private var enabled = false
    private var observing = false
    private val closing = AtomicBoolean(false)
    private val closed = CompletableDeferred<Unit>()
    private val observer =
        LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_DESTROY -> close()
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_STOP -> {
                    generation++
                    update()
                }
                else -> Unit
            }
        }
    private val worker =
        scope.launch(start = CoroutineStart.LAZY) {
            state.collectLatest { activation ->
                if (activation.enabled) {
                    val resources = MediaResources(onFailure)
                    try {
                        withContext(Dispatchers.IO) { resources.create() }
                        awaitCancellation()
                    } catch (failure: Throwable) {
                        if (failure !is CancellationException) runCatching { onFailure(failure) }
                    } finally {
                        resources.release()
                    }
                }
            }
        }

    fun start() {
        check(!closing.get()) { "Foreground adapter is closed" }
        scope.launch {
            if (closing.get()) return@launch
            if (lifecycle.currentState == Lifecycle.State.DESTROYED) {
                close()
                return@launch
            }
            worker.start()
            enabled = true
            generation++
            if (!observing) {
                observing = true
                lifecycle.addObserver(observer)
            }
            update()
        }
    }

    fun stop() {
        scope.launch {
            enabled = false
            update()
        }
    }

    private fun update() {
        state.value = Activation(enabled && !closing.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED), generation)
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        scope.launch {
            if (observing) lifecycle.removeObserver(observer)
            worker.cancelAndJoin()
            closed.complete(Unit)
            scope.cancel()
        }
    }

    override suspend fun awaitClosed() {
        closed.await()
    }
}
