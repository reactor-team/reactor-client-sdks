package inc.reactor.sdk.android

import inc.reactor.sdk.android.internal.Completions
import inc.reactor.sdk.android.internal.ErrorPayloads
import inc.reactor.sdk.android.internal.NativeClient
import inc.reactor.sdk.android.internal.NativeEvents
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A connection to a Reactor model.
 *
 * ```kotlin
 * val reactor = Reactor(ReactorOptions(jwt = tokenFromYourBackend))
 * reactor.use {
 *     it.connect("reactor/echo")
 *     it.status.collect { status -> … }
 * }
 * ```
 *
 * **Close it.** A creator that goes away without disconnecting orphans the session server-side,
 * and the next run cannot start until that clears. [close] is what releases the native handle.
 */
public class Reactor(
    private val options: ReactorOptions,
    /**
     * Where control events are delivered.
     *
     * Events arrive on threads the FFI owns, and are handed to this dispatcher before any of your
     * code sees them — `Dispatchers.Main.immediate` by default, because an Android consumer's
     * handler usually touches UI. Media is different and deliberately so: frames stay on the FFI
     * thread, because blocking there is the backpressure.
     */
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val closed = AtomicBoolean(false)

    private val _status = MutableStateFlow(ConnectionStatus.DISCONNECTED)

    /** Where the connection is, now and as it changes. */
    public val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _errors =
        MutableSharedFlow<ReactorException>(
            replay = 0,
            extraBufferCapacity = 16,
            // Errors are diagnostics, not a queue to preserve: dropping the oldest under a burst is
            // better than suspending the FFI's event thread, which is what a full rendezvous would do.
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /**
     * Errors the session reports, as they happen.
     *
     * The same [ReactorException] a failed call throws — one type to catch and one to match on.
     * A failed operation throws *and* may appear here; the throw is the answer to your call, this
     * is the session telling you something changed.
     */
    public val errors: SharedFlow<ReactorException> = _errors.asSharedFlow()

    private val _sessionId = MutableStateFlow<String?>(null)

    /** The session's id once there is one, null when it is cleared. */
    public val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    @Volatile
    private var handle: NativeClient.Handle? = null

    /**
     * The listener the bridge holds.
     *
     * It reaches the client through a [WeakReference] on purpose: the native side keeps this
     * object alive for the life of the handle, and a strong reference here would make the client
     * — and therefore the session — unreachable for garbage collection until teardown, which is
     * the opposite of what an Android app needs when an Activity goes away.
     */
    private class Events(
        owner: Reactor,
    ) : NativeEvents {
        private val owner = WeakReference(owner)

        override fun onStatus(status: String?) {
            owner.get()?.deliverStatus(ConnectionStatus.of(status))
        }

        override fun onError(errorJson: String?) {
            owner.get()?.deliverError(ErrorPayloads.toException(errorJson))
        }

        override fun onSessionId(sessionId: String?) {
            owner.get()?.deliverSessionId(sessionId)
        }

        // Frames are *not* handed to the dispatcher. They run here, on the FFI's thread, because
        // blocking here is the backpressure — see Track.onFrame.
        override fun onVideoFrame(
            trackName: String?,
            pixels: java.nio.ByteBuffer?,
            width: Int,
            height: Int,
            frameId: Long,
            timestampUs: Long,
            userData: ByteArray?,
        ) {
            val reactor = owner.get() ?: return
            val name = trackName.orEmpty()
            val handler =
                reactor.videoHandlers[name] ?: run {
                    reactor.dropFrame(name)
                    return
                }
            handler(
                VideoFrame(
                    trackName = name,
                    pixels = pixels ?: return,
                    width = width,
                    height = height,
                    frameId = frameId,
                    timestampUs = timestampUs,
                    userData = userData,
                ),
            )
        }

        override fun onAudioFrame(
            trackName: String?,
            pcm: java.nio.ByteBuffer?,
            sampleCount: Int,
            sampleRate: Int,
            channels: Int,
        ) {
            val reactor = owner.get() ?: return
            val name = trackName.orEmpty()
            val handler =
                reactor.audioHandlers[name] ?: run {
                    reactor.dropFrame(name)
                    return
                }
            handler(
                AudioFrame(
                    trackName = name,
                    samples = (pcm ?: return).order(java.nio.ByteOrder.nativeOrder()).asShortBuffer(),
                    sampleCount = sampleCount,
                    sampleRate = sampleRate,
                    channels = channels,
                ),
            )
        }
    }

    /**
     * Publish state, which the session does not record for us.
     *
     * `reactor_publish_track` is a request and `reactor_unpublish_track` a notification; neither
     * leaves anything to query. So the binding keeps it — and **clears it whenever the status
     * leaves `ready`**, because a reconnect resumes recvonly tracks and nothing else. A slot
     * published before one is not published after it, and remembering otherwise reintroduces
     * exactly the silent drop that publishing exists to prevent.
     */
    private val publishStates =
        java.util.concurrent.ConcurrentHashMap<String, PublishState>()

    private fun requireHandle(operation: String) =
        handle ?: throw ErrorCode.toException(
            wire = "INVALID_STATE",
            message = "Not connected — call connect() before $operation",
            operation = operation,
        )

    private val videoHandlers = java.util.concurrent.ConcurrentHashMap<String, (VideoFrame) -> Unit>()
    private val audioHandlers = java.util.concurrent.ConcurrentHashMap<String, (AudioFrame) -> Unit>()

    /**
     * Frames for a track nobody is listening to.
     *
     * Dropped and counted rather than raised: a frame arriving with no matching handler is
     * ordinary — the model generates whether or not anyone collects — and there is nowhere to
     * raise to on an FFI thread. The count is what makes "my handler never fires" diagnosable.
     */
    @Volatile
    public var droppedFrames: Long = 0L
        private set

    private fun dropFrame(
        @Suppress("UNUSED_PARAMETER") track: String,
    ) {
        droppedFrames += 1
    }

    /**
     * The tracks this session declared, in declaration order.
     *
     * Empty until [connect] has negotiated capabilities.
     */
    public val tracks: TrackList
        get() =
            inc.reactor.sdk.android.internal.TrackParsing
                .parse(handle?.tracks, trackOwner)

    /**
     * One track by name — what an app that knows its model does.
     *
     * @throws InvalidStateException when no such track was declared, listing the names that were.
     */
    public fun track(name: String): Track {
        val all = tracks
        return all.firstOrNull { it.name == name } ?: throw ErrorCode.toException(
            wire = "INVALID_STATE",
            message =
                "No track named '$name'. This session declared: " +
                    (if (all.isEmpty()) "nothing yet — connect() first" else all.joinToString(", ") { it.name }),
            operation = "track",
        )
    }

    private val trackOwner =
        object : TrackOwner {
            override fun setVideoHandler(
                track: String,
                handler: (VideoFrame) -> Unit,
            ) {
                videoHandlers[track] = handler
            }

            override fun setAudioHandler(
                track: String,
                handler: (AudioFrame) -> Unit,
            ) {
                audioHandlers[track] = handler
            }

            override fun clearHandlers(track: String) {
                videoHandlers.remove(track)
                audioHandlers.remove(track)
            }

            override fun publishState(track: String): PublishState = publishStates[track] ?: PublishState.UNPUBLISHED

            override suspend fun publish(track: String) {
                val client = requireHandle("publish")
                publishStates[track] = PublishState.PUBLISHING
                try {
                    client.publishTrack(track)
                } catch (t: Throwable) {
                    // A failed publish leaves no sender behind the slot, so it must not read as
                    // published — and must stay retryable.
                    publishStates.remove(track)
                    throw t
                }
                // Only if the session is still ready. A status change during the publish means the
                // completion is answering about a connection that has since gone, and recording
                // "published" here would outlive the sender it describes.
                if (_status.value == ConnectionStatus.READY) {
                    publishStates[track] = PublishState.PUBLISHED
                } else {
                    publishStates.remove(track)
                }
            }

            override suspend fun unpublish(track: String) {
                val client = requireHandle("unpublish")
                val error = client.unpublishTrack(track)
                if (error != null) {
                    // Deliberately *not* clearing the state: an unpublish that failed leaves the
                    // sender attached, and forgetting that would make the retry a no-op.
                    throw inc.reactor.sdk.android.internal.ErrorPayloads
                        .toException(error, "unpublish")
                }
                publishStates.remove(track)
            }

            override suspend fun pause(track: String) {
                requireHandle("pause").pauseTrack(track)
            }

            override suspend fun resume(track: String) {
                requireHandle("resume").resumeTrack(track)
            }

            override fun pushVideoFrame(
                track: String,
                pixels: java.nio.ByteBuffer,
                width: Int,
                height: Int,
                userData: ByteArray?,
            ) {
                requireHandle("pushFrame").pushVideoFrame(track, pixels, width, height, userData)
            }

            override fun pushAudioFrame(
                track: String,
                pcm: java.nio.ByteBuffer,
                samplesPerChannel: Int,
                sampleRate: Int,
                channels: Int,
            ) {
                requireHandle("pushFrame").pushAudioFrame(track, pcm, samplesPerChannel, sampleRate, channels)
            }

            override fun isPaused(track: String): Boolean = handle?.pausedTracks?.let { it.contains("\"" + track + "\"") } ?: false
        }

    private fun deliverStatus(status: ConnectionStatus) {
        // Cleared here rather than on the dispatcher: the drop has to be visible to a caller
        // pushing from any thread the instant the session stops being ready, not one dispatch
        // later. A frame pushed in that window goes into a slot with no sender behind it.
        if (status != ConnectionStatus.READY) publishStates.clear()
        scope.launch { _status.value = status }
    }

    private fun deliverError(error: ReactorException) {
        scope.launch { _errors.emit(error) }
    }

    private fun deliverSessionId(id: String?) {
        scope.launch { _sessionId.value = id }
    }

    /**
     * Connect to [modelName], creating a session or adopting [sessionId].
     *
     * Model names are `owner/name`. A bare name resolves under `reactor/`, so it works by luck of
     * ownership and answers 403 for anyone else's model.
     */
    public suspend fun connect(
        modelName: String,
        sessionId: String? = null,
    ) {
        check(!closed.get()) { "This Reactor is closed" }
        require(modelName.contains('/')) {
            "Model names are owner/name — '$modelName' would resolve under reactor/ and answer " +
                "403 for a model owned by anyone else"
        }
        val existing = handle
        val client =
            existing ?: NativeClient
                .create(
                    apiUrl = options.apiUrl,
                    modelName = modelName,
                    jwt = options.jwt,
                    local = options.local,
                    listener = Events(this),
                    sdkVersion = SDK_VERSION,
                ).also { handle = it }
        client.connect(sessionId)
    }

    /** End the session server-side. Use [reconnect] to keep it. */
    public suspend fun disconnect() {
        handle?.disconnect()
    }

    /** Cycle the connection without ending the session. */
    public suspend fun reconnect() {
        val client =
            handle ?: throw ErrorCode.toException(
                wire = "INVALID_STATE",
                message = "There is no session to reconnect to — call connect() first",
                operation = "reconnect",
            )
        client.reconnect()
    }

    /**
     * Release the native handle. Idempotent.
     *
     * Outstanding operations are settled rather than dropped: a caller left holding an unresolved
     * promise waits for the life of the process. Native work already started may continue — a
     * download whose client went away is still downloading — and the error says so.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        Completions.abandonAll(
            "The Reactor was closed. Native work already started may still be running.",
        )
        handle?.close()
        handle = null
        scope.cancel()
    }

    internal companion object {
        /**
         * What the coordinator records as `client_info.sdk_version`.
         *
         * A literal until the release wiring replaces it — the AAR's own version is what belongs
         * here, not reactor-core's, and A15 is where that is read from the manifest.
         */
        const val SDK_VERSION: String = "0.1.0-SNAPSHOT"
    }
}
