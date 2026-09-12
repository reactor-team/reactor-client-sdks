package inc.reactor.sdk

import inc.reactor.sdk.internal.CompletionRegistry
import inc.reactor.sdk.internal.ControlEvents
import inc.reactor.sdk.internal.Declaration
import inc.reactor.sdk.internal.HandleCallbacks
import inc.reactor.sdk.internal.NativeClient
import inc.reactor.sdk.internal.SDK_VERSION
import inc.reactor.sdk.internal.TrackState
import inc.reactor.sdk.internal.decodeError
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
import kotlinx.serialization.json.JsonElement
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
    private var bitrateBounds: Triple<Int, Int, Int>? = null
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
        bitrateBounds?.let { setNativeBitrate(null, it.first, it.second, it.third) }
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
        awaitOperation { native, receiver -> NativeClient.start(native, kind, session?.encodeToByteArray(), connection ?: -1, receiver) }
    }

    private suspend fun awaitOperation(
        onCompletion: ((Boolean) -> Unit)? = null,
        start: (Long, Any) -> Unit,
    ) = awaitResult({
        require(it is JsonObject) { "Expected a completion object" }
        Unit
    }, onCompletion, start)

    internal suspend fun <T> awaitResult(
        decode: (JsonElement?) -> T,
        onCompletion: ((Boolean) -> Unit)? = null,
        start: (Long, Any) -> Unit,
    ): T {
        val current =
            synchronized(lease) {
                requireOpen()
                if (handle == 0L) throw InvalidStateError(ErrorDetails("INVALID_STATE", "Call connect before this operation"))
                operations
            }
        return current.registry.await(decode) { id ->
            synchronized(lease) {
                try {
                    requireOpen()
                    check(operations === current) { "Native handle changed before starting operation" }
                    start(handle, current.receiver(id, onCompletion))
                } catch (failure: Throwable) {
                    current.receivers.remove(id)
                    onCompletion?.invoke(false)
                    throw failure
                }
            }
        }
    }

    /** The native completion is the correlated reply; no event listener is needed. */
    suspend fun sendCommand(
        name: String,
        arguments: JsonObject = JsonObject(emptyMap()),
    ): CommandReply? {
        require(name.isNotBlank() && '\u0000' !in name) { "Command name must be nonempty and contain no NUL" }
        val args = arguments.toString().encodeToByteArray()
        return awaitResult(::commandReply) { native, receiver ->
            NativeClient.query(native, 0, name.encodeToByteArray(), args, receiver)
        }
    }

    suspend fun requestSchema(): JsonObject =
        awaitResult({ it as? JsonObject ?: error("Expected a schema document") }) { native, receiver ->
            NativeClient.query(native, 1, null, null, receiver)
        }

    suspend fun getStats(): ConnectionStats =
        awaitResult(::connectionStats) { native, receiver ->
            NativeClient.query(native, 2, null, null, receiver)
        }

    /** Unsolicited application messages, delivered on the configured control dispatcher. */
    fun onMessage(handler: (JsonObject) -> Unit): AutoCloseable = onEvent { if (it is ControlEvent.Message) handler(it.payload) }

    fun onRuntimeMessage(handler: (JsonObject) -> Unit): AutoCloseable =
        onEvent { if (it is ControlEvent.RuntimeMessage) handler(it.payload) }

    /** Connection-wide bounds. Before connect, remember them for the first peer connection. */
    suspend fun setBitrate(
        minBps: Int = -1,
        startBps: Int = -1,
        maxBps: Int = -1,
    ) = lifecycle.withLock {
        validateBitrate(minBps, startBps, maxBps)
        val exists =
            synchronized(lease) {
                requireOpen()
                handle != 0L
            }
        if (exists) setNativeBitrate(null, minBps, startBps, maxBps)
        bitrateBounds = Triple(minBps, startBps, maxBps)
    }

    internal suspend fun trackBitrate(
        track: Track,
        min: Int,
        max: Int,
    ) {
        validateBitrate(min, max)
        validateTrack(track)
        setNativeBitrate(track.name, min, -1, max)
    }

    private suspend fun setNativeBitrate(
        name: String?,
        min: Int,
        start: Int,
        max: Int,
    ) = awaitOperation { native, receiver -> NativeClient.bitrate(native, name?.encodeToByteArray(), min, start, max, receiver) }

    private fun validateBitrate(vararg bounds: Int) {
        require(bounds.all { it >= -1 }) { "Bitrate bounds must be nonnegative or -1 for the WebRTC default" }
    }

    internal fun publicationState(track: Track): PublicationState {
        synchronized(lease) { requireOpen() }
        return trackState.publicationState(Declaration(track.name, track.kind, track.direction))
    }

    internal suspend fun publish(track: Track) {
        val state = trackState // Callback must not retain Reactor or Track.
        val attempt = state.beginPublish(validateTrack(track)) ?: return
        try {
            awaitOperation({ success -> state.finishPublish(attempt, success) }) { native, receiver ->
                NativeClient.start(native, 5, track.name.encodeToByteArray(), -1, receiver)
            }
        } catch (failure: kotlinx.coroutines.CancellationException) {
            // Cancellation removes the awaiter, not the in-flight native publish.
            throw failure
        } catch (failure: Throwable) {
            state.finishPublish(attempt, false)
            throw failure
        }
        if (state.publication(attempt.declaration) !== attempt) {
            throw InvalidStateError(ErrorDetails("INVALID_STATE", "Connection changed while publishing; publish again after reconnect"))
        }
    }

    internal fun unpublish(track: Track) {
        val declaration = validateTrack(track)
        synchronized(lease) {
            requireOpen()
            val attempt = trackState.publication(declaration) ?: return
            trackState.requirePublished(declaration)
            val error = NativeClient.unpublish(handle, track.name.encodeToByteArray())
            if (error != null) {
                val decoded =
                    try {
                        Json.parseToJsonElement(error.decodeToString(throwOnInvalidSequence = true))
                    } catch (failure: Exception) {
                        throw DecodeFailedError(ErrorDetails("DECODE_FAILED", "Invalid unpublish error: ${failure.message}"))
                    }
                throw decodeError(decoded)
            }
            trackState.finishPublish(attempt, false)
        }
    }

    internal fun push(
        track: Track,
        frame: MediaFrame,
        captureTime: Long? = null,
    ) {
        val declaration = validateTrack(track)
        synchronized(lease) {
            requireOpen()
            trackState.requirePublished(declaration)
            when (frame) {
                is VideoFrame ->
                    NativeClient.pushVideo(
                        handle,
                        track.name.encodeToByteArray(),
                        frame.pixels,
                        frame.width,
                        frame.height,
                        frame.userData,
                        captureTime ?: -1,
                    )
                is AudioFrame ->
                    NativeClient.pushAudio(
                        handle,
                        track.name.encodeToByteArray(),
                        frame.samples,
                        frame.sampleRate,
                        frame.channels,
                    )
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

    fun receiver(
        id: Long,
        onCompletion: ((Boolean) -> Unit)? = null,
    ): CompletionReceiver =
        CompletionReceiver(this, id, onCompletion).also {
            receivers[id] =
                it
        }

    override fun close() {
        registry.close()
        receivers.clear()
    }
}

internal class CompletionReceiver(
    private val owner: Operations,
    private val id: Long,
    private val onCompletion: ((Boolean) -> Unit)? = null,
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
            if (!owner.receivers.remove(id, this@CompletionReceiver)) return@launch
            val validSuccess =
                failure == null &&
                    runCatching {
                        Json.parseToJsonElement(requireNotNull(result).decodeToString(throwOnInvalidSequence = true)) is JsonObject
                    }.getOrDefault(false)
            onCompletion?.invoke(validSuccess)
            owner.registry.complete(id, result, failure)
        }
    }
}

private val completionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
