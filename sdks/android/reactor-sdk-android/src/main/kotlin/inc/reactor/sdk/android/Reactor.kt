package inc.reactor.sdk.android

import inc.reactor.sdk.android.internal.Authentication
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
     * The token [handle] was created with: the caller's [ReactorOptions.jwt], or the token the
     * last key exchange minted. Written only under [handleLock]; read by [downloadClip] without
     * it, so volatile.
     */
    @Volatile
    private var token: String? = options.jwt

    /** The token each session this client reached was connected with, by session id. */
    private val sessionTokens = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * What [token] was minted to reach, when it came from an exchange rather than from the
     * caller. Under [handleLock] only.
     */
    private var mintedFor: Authentication.TokenScope? = null

    /**
     * The model [handle] was created for. The native client takes its model at creation and a
     * later connect cannot change it, so a connect to another model needs a new handle — whatever
     * the token says. Under [handleLock] only.
     */
    private var handleModel: String? = null

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
        // Remembered with the token that reached it: a later connect can re-mint for another
        // session, and that token cannot download this session's clips.
        val current = token
        if (id != null && current != null) sessionTokens[id] = current
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
        // The key exchange holds the same lock, and for the same reason: two concurrent connects
        // minting at once would race to rebuild the handle under each other, which is the leak
        // above with extra steps. A mint can take a network round trip, so later connects queue
        // here — the price of a serialized answer being the only correct one.
        //
        // And it is held until the connect itself has been answered, not just until the handle
        // exists. Released earlier, a second connect needing another model or scope could retire
        // the handle this one had just taken, which then connected on a closed client or had its
        // completion abandoned under it. Waiting on the lock is cancellable; close() still settles
        // an in-flight connect, which releases it.
        //
        // connect() is a suspend function, so this is a coroutine Mutex rather than a monitor.
        //
        // close() cannot take it — it is not a suspend function — so `closed` is checked again
        // around the creation: a close() landing between the check above and the assignment would
        // otherwise null a handle that is not there yet, and this call would then store a fresh
        // native client on a closed Reactor that nothing ever releases. Checked *after* assigning,
        // so whichever of the two runs second sees the other: either close() reads the stored
        // handle and closes it, or this sees `closed` and closes it itself.
        handleLock.withLock {
            check(!closed.get()) { "This Reactor is closed" }
            val tokenChanged = mintForConnect(modelName, sessionId)
            Authentication.needsRebuild(handleModel, modelName, tokenChanged)?.let {
                // The create() below builds the replacement around the new token and model.
                retireHandle(it.reason)
            }
            val client =
                handle ?: NativeClient
                    .create(
                        apiUrl = options.apiUrl,
                        modelName = modelName,
                        jwt = token,
                        local = options.local,
                        listener = Events(this),
                        sdkVersion = SDK_VERSION,
                    ).also { created ->
                        handle = created
                        handleModel = modelName
                        if (closed.get()) {
                            handle = null
                            created.close()
                            throw IllegalStateException("This Reactor is closed")
                        }
                    }
            client.connect(sessionId)
        }
    }

    /**
     * Mint the token this connect needs, when that is what it takes, and say whether it changed.
     * Runs under [handleLock], which is what serializes one connect's mint-and-rebuild against
     * another connect's create.
     *
     * A mint does not necessarily mean a rebuild: the native client receives its token at
     * creation, so a *changed* token reaches it only through a new handle — but an exchange
     * returning the same value (short-lived keys often yield the same token twice) leaves the
     * handle exactly as useful as it was, and rebuilding would end a live session for nothing.
     * Whether to rebuild is [Authentication.needsRebuild]'s call.
     *
     * The failure a caller gets is the exchange's own — UNAUTHORIZED for a rejected key,
     * NETWORK_ERROR for a coordinator that is not there — propagated rather than rephrased, so a
     * connect that fails names what actually failed.
     */
    private suspend fun mintForConnect(
        modelName: String,
        sessionId: String?,
    ): Boolean {
        val wanted = Authentication.TokenScope(modelName = modelName, sessionId = sessionId)
        val optionsJson =
            Authentication.planMint(
                apiKey = options.apiKey,
                callerJwt = options.jwt,
                local = options.local,
                currentToken = token,
                mintedFor = mintedFor,
                wanted = wanted,
            ) ?: return false
        val minted =
            NativeClient.fetchJwt(
                apiUrl = options.apiUrl,
                // planMint returns a plan only when the key exists; this is the invariant, said once.
                apiKey = checkNotNull(options.apiKey),
                optionsJson = optionsJson,
                local = options.local,
            )
        val changed = minted != token
        token = minted
        mintedFor = wanted
        return changed
    }

    /**
     * Close [handle] so connect() builds a new one, settling the old one's operations.
     *
     * The same order close() keeps: the handle first, so nothing more can launch on it, then its
     * completions — only its own, since the registry is shared with every other client. Under
     * [handleLock] only.
     */
    private fun retireHandle(reason: String) {
        val old = handle ?: return
        handle = null
        handleModel = null
        old.close()
        Completions.abandonAll(old, reason)
    }

    /**
     * Send a command to the model and await its **correlated** reply.
     *
     * The correlation is the FFI's: this completes for this command and no other. A binding that
     * sent and then waited for a message event would be racing a reply that may already have
     * arrived.
     *
     * @param args a JSON object as a string, or null for none. Typed argument builders arrive
     *   with the JSON work in a later slice; this is the primitive underneath them.
     * @return the reply, which may be [CommandReply.isEmpty] when the handler acknowledged the
     *   command without sending anything back — success with nothing to report.
     * @throws DecodeFailedException when a reply arrived but did not parse.
     */
    public suspend fun sendCommand(
        name: String,
        args: String? = null,
        uploads: String? = null,
    ): CommandReply = requireHandle("send_command").sendCommand(name, args, uploads)

    /**
     * The model's declared command and track schema.
     *
     * @throws DecodeFailedException when the request succeeded but carried no schema. An absent
     *   schema is not an empty one, and reporting `{}` would be indistinguishable from a model
     *   that genuinely declares nothing.
     */
    public suspend fun requestSchema(): Map<String, Any?> = requireHandle("request_schema").requestSchema()

    /** A connection statistics snapshot. */
    public suspend fun stats(): Stats = requireHandle("get_stats").stats()

    /**
     * Upload a file from the filesystem.
     *
     * The MIME type is inferred from the name's extension, so a staged copy has to keep one.
     */
    public suspend fun uploadFile(file: java.io.File): FileRef {
        if (!file.isFile) {
            throw ErrorCode.toException(
                wire = "NOT_FOUND",
                message = "No file at ${file.path}",
                operation = "upload_file",
            )
        }
        return requireHandle("upload_file").uploadFile(file.absolutePath)
    }

    /**
     * Upload bytes already in memory.
     *
     * @param data a **direct** [java.nio.ByteBuffer]; the native layer reads it in place rather
     *   than copying, which keeps peak memory at one copy for a large payload.
     */
    public suspend fun uploadBytes(
        data: java.nio.ByteBuffer,
        name: String,
        mimeType: String? = null,
    ): FileRef {
        require(data.isDirect) {
            "uploadBytes needs a direct ByteBuffer — ByteBuffer.allocateDirect(), not allocate()"
        }
        // Sliced, not passed as-is. GetDirectBufferAddress returns the buffer's *base* address,
        // unadjusted for position — so a buffer that was filled and not flipped would upload
        // `remaining()` bytes starting at offset 0, which is neither what the caller wrote nor an
        // error anything would catch. A slice re-bases the address onto the current position,
        // which is the contract ByteBuffer.slice() documents.
        val window = data.slice()
        // Resolved here because the ABI will not take null: reactor_upload_bytes dereferences
        // mime_type unconditionally (CStr::from_ptr), and the header marks only send_command's
        // args and uploads as nullable. Passing the public default through was a null dereference
        // on the simplest possible call.
        val resolved =
            mimeType ?: inc.reactor.sdk.android.internal.Uploads
                .mimeTypeFor(name)
        return requireHandle("upload_bytes").uploadBytes(window, window.remaining(), name, resolved)
    }

    /**
     * Upload from anything that opens a stream, staging it through [cacheDirectory] first.
     *
     * Public because a caller with their own source — an asset, a network response, a cipher
     * stream — should not have to construct a `content://` URI to reach this. [uploadContent] is
     * a thin wrapper over it.
     *
     * Copied in chunks and bounded by [maxBytes]: the source is a file the *user* chose, and an
     * SDK that read it whole would be deciding the memory ceiling of an app it knows nothing
     * about.
     *
     * **Cancellable while staging, not while uploading, and that asymmetry is the ABI's.**
     * `reactor_upload_file` opens the path inside a task of its own, after returning, and nothing
     * in the ABI cancels it. So the two halves get different treatment:
     *
     *  * The copy is cancellable, and is the half worth cancelling — it is the one that takes
     *    time for a large file. It checks for cancellation between chunks, and the staged file is
     *    deleted however staging ends — including a cancel that lands after the copy finished but
     *    before this function got the file back.
     *  * The upload runs under [kotlinx.coroutines.NonCancellable]. A cancelled wait here used to
     *    run the `finally` immediately and delete a file the native task had not opened yet,
     *    which is a failed upload the caller is never told about. Waiting is the only thing that
     *    makes the delete safe, because there is nothing to cancel on the other side.
     */
    public suspend fun uploadStream(
        name: String,
        cacheDirectory: java.io.File,
        maxBytes: Long = DEFAULT_UPLOAD_LIMIT_BYTES,
        open: () -> java.io.InputStream,
    ): FileRef =
        inc.reactor.sdk.android.internal.Uploads
            .withStaged(name, cacheDirectory, maxBytes, open) { staged ->
                requireHandle("upload_file").uploadFile(staged.absolutePath)
            }

    /**
     * Ask for a clip of the last [durationSeconds] of generated media.
     *
     * A "snap" clip's window ends at *now*, so its boundary chunk is always the still-open one —
     * which is why waiting before asking does not help: it moves the target with you.
     */
    public suspend fun requestClip(durationSeconds: Double): Clip = requireHandle("request_clip").requestClip(durationSeconds)

    /** Start recording the whole session. */
    public suspend fun requestRecording(): Clip = requireHandle("request_recording").requestRecording()

    /**
     * Download a clip to [outFile].
     *
     * **This outlives the client.** The download keeps running if the [Reactor] is closed
     * mid-flight, and its completion is not bounded by teardown. [close] settles your call so you
     * are not left waiting for the life of the process — and the error says the file may yet
     * arrive, because it may: telling a caller the download was "aborted" when it is still
     * writing is worse than telling them nothing.
     *
     * @param readyTimeoutSeconds grace past the clip's own prediction. Null or negative waits as
     *   long as the session lives, which is the right answer for a model generating slower than
     *   real time — readiness is in **media** time, so a wall-clock guess is only correct at 1x.
     *   Bound the wait on the session being alive, never on a number: once the session is gone, a
     *   202 is a 202 forever.
     * @param onProgress called after each segment, **on the download's own thread**. Blocking it
     *   delays this download and nothing else.
     */
    public suspend fun downloadClip(
        clip: Clip,
        outFile: java.io.File,
        readyTimeoutSeconds: Double? = null,
        onProgress: ((ClipProgress) -> Unit)? = null,
    ): DownloadedClip {
        val timeout =
            inc.reactor.sdk.android.internal.Recordings.readyTimeoutSeconds(
                readyTimeoutSeconds,
            )
        return requireHandle("download_clip").downloadClip(
            playlistUrl = clip.playlistUrl,
            // The token the clip's session was connected with — which for an apiKey caller is the
            // minted one, not options.jwt (that caller never had one).
            jwt = Authentication.tokenForClip(clip.sessionId, sessionTokens, token),
            outPath = outFile.absolutePath,
            predictedReadyAtMs =
                inc.reactor.sdk.android.internal.Recordings
                    .predictedReadyAtMs(clip),
            readyTimeoutSeconds = timeout,
            local = options.local,
            onProgress =
                onProgress?.let { handler ->
                    { done, total -> handler(ClipProgress(done, total)) }
                },
        )
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

    public companion object {
        /**
         * The default ceiling for a streamed upload, 64 MiB.
         *
         * A number rather than "unbounded": the source is user-chosen, and an unbounded default
         * turns a mis-picked video into an out-of-memory crash in someone else's app.
         */
        public const val DEFAULT_UPLOAD_LIMIT_BYTES: Long = 64L * 1024 * 1024

        /**
         * This SDK's version, which the coordinator records as `client_info.sdk_version`.
         *
         * The AAR's own version rather than reactor-core's, read from gradle.properties at build
         * time — the same line a release bumps, so the two cannot disagree.
         */
        const val SDK_VERSION: String = BuildConfig.SDK_VERSION

        /**
         * Exchange an API key for a short-lived token, without creating a client.
         *
         * What every other binding exposes — Java's `Reactor.fetchJwt`, Swift's
         * `Reactor.fetchJWT`, Python's `reactor_sdk.fetch_jwt` — so an application backend (or a
         * one-off tool) mints tokens its Android clients then connect with. A client that holds
         * only a key does not need this: `connect()` exchanges it itself.
         *
         * **Scope the token.** With [options] null the minted token carries everything the key's
         * roles allow — fine server to server, wrong to hand to a client you do not control. Pass
         * `{"models":["owner/name"]}` for a token that can only create sessions under one model.
         * An **unrecognised option key is an error**, not a silent drop: the FFI rejects the
         * whole request, because a misspelt `models` would otherwise mint exactly the unscoped
         * token the caller was avoiding.
         *
         * @param apiUrl the coordinator's base URL (production is `https://api.reactor.inc`)
         * @param apiKey the key to exchange
         * @param options scoping options as raw JSON — the same style [sendCommand] takes its
         *   arguments — or null for an unscoped token
         * @param local accept a development coordinator's self-signed certificate; the key is still exchanged
         * @return the token
         * @throws ReactorException `UNAUTHORIZED` for a key the coordinator rejects,
         *   `NETWORK_ERROR` for one it cannot reach
         */
        public suspend fun fetchJwt(
            apiUrl: String,
            apiKey: String,
            options: String? = null,
            local: Boolean = false,
        ): String = NativeClient.fetchJwt(apiUrl, apiKey, options, local)
    }
}
