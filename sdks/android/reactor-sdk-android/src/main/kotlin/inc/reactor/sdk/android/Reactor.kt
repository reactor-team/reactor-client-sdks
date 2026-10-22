package inc.reactor.sdk.android

import inc.reactor.sdk.android.internal.Completions
import inc.reactor.sdk.android.internal.ErrorPayloads
import inc.reactor.sdk.android.internal.NativeClient
import inc.reactor.sdk.android.internal.NativeEvents
import inc.reactor.sdk.android.internal.TrackParsing
import inc.reactor.sdk.android.internal.recordPublish
import inc.reactor.sdk.android.internal.recordUnpublish
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
import kotlinx.coroutines.sync.withLock
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

    /** Serialises creating the native handle. See [connect]. */
    private val handleLock = kotlinx.coroutines.sync.Mutex()

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
            val frame =
                VideoFrame(
                    trackName = name,
                    pixels =
                        inc.reactor.sdk.android.internal.ReceivedBuffers
                            .pixels(pixels ?: return),
                    width = width,
                    height = height,
                    frameId = frameId,
                    timestampUs = timestampUs,
                    userData = userData,
                )
            // Here, on the callback's own thread and right before the app's handler, is where the
            // frame's delivery stage ends — and only for a frame a handler actually receives.
            NativeClient.frameDelivered()
            handler(frame)
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
                    samples =
                        inc.reactor.sdk.android.internal.ReceivedBuffers
                            .samples(pcm ?: return),
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

    /** Whether the last status delivered was `ready`. Under the [publishStates] monitor. */
    private var ready = false

    /** Bumped every time the session leaves `ready`. Under the [publishStates] monitor. */
    private var readyGeneration = 0L

    /**
     * One lock per track, held across a publish or an unpublish so none of them can race another
     * over the same slot.
     */
    private val publishLocks =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    private fun publishLock(track: String) = publishLocks.getOrPut(track) { kotlinx.coroutines.sync.Mutex() }

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
    public val droppedFrames: Long
        get() = dropped.get()

    // Atomic, not @Volatile: video and audio are delivered on separate native threads, and two
    // drops at once would each read, add and write the same value, losing one.
    private val dropped =
        java.util.concurrent.atomic
            .AtomicLong()

    private fun dropFrame(
        @Suppress("UNUSED_PARAMETER") track: String,
    ) {
        dropped.incrementAndGet()
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

    /** Internal rather than private only so the handler bookkeeping is testable without a device. */
    internal val trackOwner =
        object : TrackOwner {
            override fun setVideoHandler(
                track: String,
                handler: (VideoFrame) -> Unit,
            ) {
                register(videoHandlers, track, handler)
            }

            override fun setAudioHandler(
                track: String,
                handler: (AudioFrame) -> Unit,
            ) {
                register(audioHandlers, track, handler)
            }

            override fun clearHandlers(track: String) {
                videoHandlers.remove(track)
                audioHandlers.remove(track)
            }

            override fun isPaused(track: String): Boolean = track in TrackParsing.parsePaused(handle?.pausedTracks)

            override fun publishState(track: String): PublishState = publishStates[track] ?: PublishState.UNPUBLISHED

            override suspend fun publish(track: String) {
                val client = requireHandle("publish")
                recordPublish(
                    publishStates,
                    track,
                    publishLock(track),
                    readyCheck = ::readyCheck,
                ) { client.publishTrack(track) }
            }

            override suspend fun unpublish(track: String) {
                val client = requireHandle("unpublish")
                recordUnpublish(publishStates, track, publishLock(track)) {
                    client.unpublishTrack(track)?.let { error ->
                        throw inc.reactor.sdk.android.internal.ErrorPayloads
                            .toException(error, "unpublish")
                    }
                }
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
        }

    /**
     * Store a frame handler, unless this Reactor is closed.
     *
     * A closed Reactor has no native handle to deliver through, so a handler registered on it
     * would never run — and, often capturing an Activity, would be kept alive for as long as the
     * Reactor is. Refused instead, and checked again after storing, so one racing [close]'s clear
     * cannot land on the far side of it.
     */
    private fun <F> register(
        handlers: MutableMap<String, F>,
        track: String,
        handler: F,
    ) {
        requireOpen("onFrame")
        handlers[track] = handler
        if (closed.get()) {
            handlers.remove(track)
            requireOpen("onFrame")
        }
    }

    private fun requireOpen(operation: String) {
        if (closed.get()) {
            throw ErrorCode.toException(
                wire = "INVALID_STATE",
                message = "This Reactor is closed — create a new one to receive frames",
                operation = operation,
            )
        }
    }

    private fun deliverStatus(status: ConnectionStatus) {
        // Cleared here rather than on the dispatcher: the drop has to be visible to a caller
        // pushing from any thread the instant the session stops being ready, not one dispatch
        // later. A frame pushed in that window goes into a slot with no sender behind it.
        //
        // Readiness is recorded here too, under the same monitor, rather than read back from
        // `_status`: that is only updated once the launch below runs, and a publish answered in
        // between would see the old READY and restore PUBLISHED after the sender was lost.
        synchronized(publishStates) {
            ready = status == ConnectionStatus.READY
            if (!ready) {
                publishStates.clear()
                readyGeneration += 1
            }
        }
        scope.launch { _status.value = status }
    }

    /**
     * Whether the session is still in the same `ready` it was in when this was called.
     *
     * Captured at the start of a publish and asked once it is answered, under the publish-state
     * monitor: a session that left `ready` and came back in between is a different connection,
     * and the sender the publish attached went with the old one.
     */
    private fun readyCheck(): () -> Boolean {
        val generation = synchronized(publishStates) { readyGeneration }
        return { synchronized(publishStates) { ready && readyGeneration == generation } }
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
        // Creation under a mutex, because the read-check-assign below is not atomic and the
        // consequence is not a lost update. Two concurrent connect() calls both saw a null handle
        // and each created a native client; the last write won, and the other client's Context,
        // its listener's global references and its *session* were never closed — leaked for the
        // life of the process, with a session holding quota nobody could release. @Volatile makes
        // the read fresh, which is not the same as making the sequence exclusive.
        //
        // connect() is a suspend function, so this is a coroutine Mutex rather than a monitor.
        //
        // close() cannot take it — it is not a suspend function — so `closed` is checked again
        // around the creation: a close() landing between the check above and the assignment would
        // otherwise null a handle that is not there yet, and this call would then store a fresh
        // native client on a closed Reactor that nothing ever releases. Checked *after* assigning,
        // so whichever of the two runs second sees the other: either close() reads the stored
        // handle and closes it, or this sees `closed` and closes it itself.
        val client =
            handleLock.withLock {
                check(!closed.get()) { "This Reactor is closed" }
                handle ?: NativeClient
                    .create(
                        apiUrl = options.apiUrl,
                        modelName = modelName,
                        jwt = options.jwt,
                        local = options.local,
                        listener = Events(this),
                        sdkVersion = SDK_VERSION,
                    ).also { created ->
                        handle = created
                        if (closed.get()) {
                            handle = null
                            created.close()
                            throw IllegalStateException("This Reactor is closed")
                        }
                    }
            }
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
        val closing = handle
        handle = null
        // The handle first, and only then its completions. Once it is closed nothing can launch
        // on it, so nothing can register a completion that this would miss — the other order let
        // a disconnect() racing close() register after the sweep and launch before the destroy,
        // and reactor_destroy then ended its native task with nothing left to settle it.
        if (closing != null) {
            closing.close()
            Completions.abandonAll(
                closing,
                "The Reactor was closed. Native work already started may still be running.",
            )
        }
        // Nothing can deliver to these any more; dropped so they stop holding what they capture.
        videoHandlers.clear()
        audioHandlers.clear()
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
