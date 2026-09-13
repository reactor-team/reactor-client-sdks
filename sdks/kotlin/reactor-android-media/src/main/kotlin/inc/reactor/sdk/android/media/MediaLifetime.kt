package inc.reactor.sdk.android.media

import inc.reactor.sdk.Reactor
import inc.reactor.sdk.ReactorStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/** close requests shutdown without blocking the UI. awaitClosed waits for resource release. */
interface AndroidMediaResource : AutoCloseable {
    suspend fun awaitClosed()
}

internal class MediaWorker(
    private val onFailure: (Throwable) -> Unit,
) : AndroidMediaResource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var job: Job? = null
    private val stopped = AtomicBoolean(false)
    private val closed = CompletableDeferred<Unit>()

    @Volatile private var active = false
    val running get() = !stopped.get() && active

    suspend fun <T : AutoCloseable> start(
        open: () -> T,
        run: suspend (T) -> Unit,
    ) {
        val opened = CompletableDeferred<Unit>()
        synchronized(lock) {
            check(job == null && !stopped.get()) { "Media helpers are single-use; create a new helper after stop" }
            active = true
            job =
                scope
                    .launch {
                        var resource: T? = null
                        try {
                            resource = open()
                            ensureActive()
                            opened.complete(Unit)
                            run(resource)
                        } catch (failure: Throwable) {
                            val starting = opened.completeExceptionally(failure)
                            if (!starting && failure !is CancellationException) runCatching { onFailure(failure) }
                        } finally {
                            active = false
                            stopped.set(true)
                            runCatching { resource?.close() }.onFailure { runCatching { onFailure(it) } }
                        }
                    }.also { task ->
                        task.invokeOnCompletion { failure ->
                            opened.completeExceptionally(failure ?: CancellationException("Device stopped before opening"))
                            closed.complete(Unit)
                            scope.cancel()
                        }
                    }
        }
        try {
            opened.await()
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    override fun close() {
        synchronized(lock) {
            stopped.set(true)
            job?.cancel()
            if (job == null) {
                closed.complete(Unit)
                scope.cancel()
            }
        }
    }

    override suspend fun awaitClosed() {
        closed.await()
    }
}

/** Register resources immediately as they are created inside ForegroundMedia's factory. */
class MediaResources internal constructor(
    private val onFailure: (Throwable) -> Unit,
) {
    private val cleanup = mutableListOf<suspend () -> Unit>()
    private var released = false

    private fun add(action: suspend () -> Unit) =
        synchronized(cleanup) {
            check(!released) { "Foreground resource scope is already closed" }
            cleanup.add(action)
            Unit
        }

    fun <T : AutoCloseable> own(resource: T): T = resource.also { add { it.close() } }

    fun <T : AndroidMediaResource> ownMedia(resource: T): T =
        resource.also {
            add {
                it.close()
                it.awaitClosed()
            }
        }

    fun ownClient(client: Reactor): Reactor =
        client.also {
            add {
                try {
                    if (client.status != ReactorStatus.DISCONNECTED) withTimeout(5000) { client.disconnect() }
                } finally {
                    client.close()
                }
            }
        }

    internal suspend fun release() =
        withContext(NonCancellable + Dispatchers.IO) {
            val actions =
                synchronized(cleanup) {
                    released = true
                    cleanup.toList().asReversed().also { cleanup.clear() }
                }
            for (action in actions) {
                try {
                    action()
                } catch (failure: Throwable) {
                    runCatching { onFailure(failure) }
                }
            }
        }
}
