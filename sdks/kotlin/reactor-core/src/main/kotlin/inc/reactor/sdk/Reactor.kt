package inc.reactor.sdk

import inc.reactor.sdk.internal.CompletionRegistry
import inc.reactor.sdk.internal.ControlEvents
import inc.reactor.sdk.internal.Declaration
import inc.reactor.sdk.internal.HandleCallbacks
import inc.reactor.sdk.internal.NativeClient
import inc.reactor.sdk.internal.SDK_VERSION
import inc.reactor.sdk.internal.TrackState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/** Fetch short-lived credentials from your application's backend. Called before each connect. */
fun interface TokenProvider {
    suspend fun token(sessionId: String?): String
}

enum class ReactorStatus {
    DISCONNECTED,
    CONNECTING,
    WAITING,
    READY,
    ;

    internal companion object {
        fun fromWire(value: String): ReactorStatus = valueOf(value.uppercase(java.util.Locale.ROOT))
    }
}

sealed interface ControlEvent {
    data class TrackReceived(
        val name: String,
        val mid: String?,
    ) : ControlEvent

    data class StatusChanged(
        val status: ReactorStatus,
    ) : ControlEvent

    data class SessionChanged(
        val sessionId: String?,
    ) : ControlEvent

    data class Error(
        val error: ReactorError,
    ) : ControlEvent

    data class Message(
        val payload: JsonObject,
    ) : ControlEvent

    data class RuntimeMessage(
        val payload: JsonObject,
    ) : ControlEvent

    data class CapabilitiesChanged(
        val payload: JsonObject,
    ) : ControlEvent
}

/** Load the platform JNI library before constructing a client. Always close in finally. */
class Reactor(
    private val model: String,
    private val tokenProvider: TokenProvider? = null,
    private val apiUrl: String = "https://api.reactor.inc",
    private val local: Boolean = false,
    eventDispatcher: CoroutineDispatcher = Dispatchers.Default,
    onHandlerFailure: (Throwable) -> Unit = { System.err.println("Reactor event handler failed: $it") },
) {
    private val events = ControlEvents(eventDispatcher, onHandlerFailure)
    private val trackState = TrackState(onHandlerFailure)
    private var handleCallbacks: HandleCallbacks? = null
    private val lease = Any()
    private val lifecycle = Mutex()
    private var closed = false
    private var handle = 0L
    private var token: String? = null
    private var operations = Operations()
    private val shutdown = CompletableDeferred<Unit>()

    init {
        require(model.isNotBlank() && '\u0000' !in model) { "Model must be nonempty and contain no NUL" }
        require(apiUrl.isNotBlank() && '\u0000' !in apiUrl) { "API URL must be nonempty and contain no NUL" }
    }

    fun onEvent(handler: (ControlEvent) -> Unit): AutoCloseable = events.subscribe(handler)

    val status: ReactorStatus
        get() =
            synchronized(lease) {
                requireOpen()
                if (handle == 0L) ReactorStatus.DISCONNECTED else ReactorStatus.fromWire(NativeClient.status(handle).decodeToString())
            }
    val sessionId: String?
        get() =
            synchronized(lease) {
                requireOpen()
                if (handle == 0L) null else NativeClient.session(handle)?.decodeToString()
            }

    suspend fun connect(
        sessionId: String? = null,
        connectionId: Long? = null,
    ) = lifecycle.withLock {
        require(connectionId == null || connectionId in 0..0xffff_ffffL) { "Connection ID must fit uint32" }
        require(sessionId == null || '\u0000' !in sessionId) { "Session ID cannot contain NUL" }
        synchronized(lease) { requireOpen() }
        val nextToken = tokenProvider?.token(sessionId)
        require(nextToken == null || (nextToken.isNotBlank() && '\u0000' !in nextToken)) { "Token must be nonempty and contain no NUL" }
        require(local || nextToken != null) { "Provide a token provider, or explicitly enable local development" }
        withContext(Dispatchers.IO) {
            synchronized(lease) {
                requireOpen()
                if (handle != 0L && token != nextToken) destroyHandle()
                if (handle == 0L) {
                    handleCallbacks = HandleCallbacks(trackState, events, trackState.beginHandle())
                    handle =
                        NativeClient.create(
                            apiUrl.encodeToByteArray(),
                            model.encodeToByteArray(),
                            nextToken?.encodeToByteArray(),
                            local,
                            SDK_VERSION.encodeToByteArray(),
                            requireNotNull(handleCallbacks),
                            requireNotNull(handleCallbacks),
                        )
                    check(handle != 0L) { "Native client creation failed" }
                    token = nextToken
                }
            }
        }
        runOperation(0, sessionId, connectionId)
    }

    /** Preserve the current session and credentials, unlike disconnect followed by connect. */
    suspend fun reconnect() = lifecycle.withLock { runOperation(1) }

    /** End the session on the server. */
    suspend fun disconnect() = lifecycle.withLock { runOperation(2) }

    /** Reject work immediately; native teardown runs on IO even from Android's main thread. */
    suspend fun close() =
        withContext(NonCancellable + Dispatchers.IO) {
            val first =
                synchronized(lease) {
                    if (closed) {
                        false
                    } else {
                        closed = true
                        operations.close()
                        true
                    }
                }
            if (first) {
                events.close()
                trackState.stop()
                try {
                    withContext(Dispatchers.IO) { synchronized(lease) { destroyHandle() } }
                    shutdown.complete(Unit)
                } catch (failure: Throwable) {
                    shutdown.completeExceptionally(failure)
                }
            }
            shutdown.await()
        }

    private suspend fun runOperation(
        kind: Int,
        session: String? = null,
        connection: Long? = null,
    ) {
        val current =
            synchronized(lease) {
                requireOpen()
                if (handle == 0L) throw InvalidStateError(ErrorDetails("INVALID_STATE", "Call connect before this operation"))
                operations
            }
        current.registry.await({ Unit }) { id ->
            synchronized(lease) {
                requireOpen()
                val receiver = current.receiver(id)
                try {
                    NativeClient.start(handle, kind, session?.encodeToByteArray(), connection ?: -1, receiver)
                } catch (failure: Throwable) {
                    current.receivers.remove(id)
                    throw failure
                }
            }
        }
    }

    val tracks: TrackList
        get() {
            val entries =
                trackState.snapshot {
                    synchronized(lease) {
                        requireOpen()
                        if (handle == 0L) "[]".encodeToByteArray() else NativeClient.tracks(handle)
                    }
                }
            return TrackList(entries.map { Track(this, it.name, it.kind, it.direction) })
        }

    fun track(name: String): Track {
        val values = tracks
        return values.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("Unknown track '$name'. Declared tracks: ${values.joinToString { it.name }}")
    }

    internal fun validateTrack(track: Track): Declaration {
        val current = track(track.name)
        require(current.kind == track.kind && current.direction == track.direction) {
            "Track '${track.name}' changed; refresh it from Reactor.tracks"
        }
        return Declaration(current.name, current.kind, current.direction)
    }

    internal fun trackMid(track: Track): String? {
        validateTrack(track)
        return trackState.mid(track.name)
    }

    internal fun trackPaused(track: Track): Boolean {
        validateTrack(track)
        val value =
            synchronized(lease) {
                requireOpen()
                NativeClient.paused(handle)
            }
        return (Json.parseToJsonElement(value.decodeToString()) as JsonArray).any { (it as JsonPrimitive).content == track.name }
    }

    internal fun receive(
        track: Track,
        handler: (MediaFrame) -> Unit,
    ): AutoCloseable = trackState.subscribe(validateTrack(track), handler)

    internal suspend fun trackOperation(
        track: Track,
        kind: Int,
    ) {
        validateTrack(track)
        runOperation(kind, track.name)
    }

    private fun requireOpen() {
        if (closed) throw InvalidStateError(ErrorDetails("INVALID_STATE", "Client is closed; create a new Reactor"))
    }

    // lease held; callbacks never acquire it. Only called from Dispatchers.IO.
    private fun destroyHandle() {
        operations.close()
        if (handle != 0L) {
            val previous = handle
            handle = 0
            NativeClient.destroy(previous) // JNI retains orphan callback state on -1.
        }
        handleCallbacks = null
        trackState.beginHandle()
        operations = Operations()
    }
}

internal class Operations : AutoCloseable {
    val registry = CompletionRegistry()
    val receivers = ConcurrentHashMap<Long, CompletionReceiver>()

    fun receiver(id: Long): CompletionReceiver = CompletionReceiver(this, id).also { receivers[id] = it }

    override fun close() {
        registry.close()
        receivers.clear()
    }
}

internal class CompletionReceiver(
    private val owner: Operations,
    private val id: Long,
) {
    fun accept(
        ok: Int,
        result: ByteArray?,
        error: ByteArray?,
    ) {
        val failure =
            if (ok == 0 && error == null) {
                """{"code":"DECODE_FAILED","message":"Native failure without an error payload"}""".encodeToByteArray()
            } else {
                error
            }
        // Never resume an Unconfined continuation on the native callback thread.
        // It could call close while destroy is waiting for this callback.
        completionScope.launch {
            owner.registry.complete(id, result, failure)
            owner.receivers.remove(id)
        }
    }
}

private val completionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
