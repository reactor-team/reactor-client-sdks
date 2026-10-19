package inc.reactor.sdk.android.internal

import inc.reactor.sdk.android.Clip
import inc.reactor.sdk.android.CommandReply
import inc.reactor.sdk.android.DownloadedClip
import inc.reactor.sdk.android.FileRef
import inc.reactor.sdk.android.Stats
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One native handle, and the rule that keeps its callbacks from outliving their references.
 *
 * The object model is built on this in A05 onward; what lives here is the crossing itself.
 */
internal object NativeClient {
    private external fun nativeCreate(
        apiUrl: String,
        modelName: String,
        jwt: String?,
        local: Boolean,
        autoResumeTracks: Boolean,
        listener: NativeEvents,
        sdkVersion: String?,
        sdkType: String?,
    ): Long

    private external fun nativeDestroy(context: Long): Int

    private external fun nativeStatus(context: Long): String?

    private external fun nativeSessionId(context: Long): String?

    private external fun nativeTracks(context: Long): String?

    private external fun nativePausedTracks(context: Long): String?

    private external fun nativeInitCompletions(completions: Class<*>)

    private external fun nativeConnect(
        context: Long,
        sessionId: String?,
        ticket: Long,
    )

    private external fun nativeDisconnect(
        context: Long,
        ticket: Long,
    )

    private external fun nativeReconnect(
        context: Long,
        ticket: Long,
    )

    private external fun nativePublishTrack(
        context: Long,
        name: String,
        ticket: Long,
    )

    /** Synchronous: the error payload on failure, null on success. */
    private external fun nativeUnpublishTrack(
        context: Long,
        name: String,
    ): String?

    private external fun nativePauseTrack(
        context: Long,
        name: String,
        ticket: Long,
    )

    private external fun nativeResumeTrack(
        context: Long,
        name: String,
        ticket: Long,
    )

    private external fun nativePushVideoFrame(
        context: Long,
        name: String,
        pixels: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        userData: ByteArray?,
    )

    private external fun nativePushAudioFrame(
        context: Long,
        name: String,
        pcm: java.nio.ByteBuffer,
        samplesPerChannel: Int,
        sampleRate: Int,
        channels: Int,
    )

    private external fun nativeSendCommand(
        context: Long,
        name: String,
        argsJson: String?,
        uploadsJson: String?,
        ticket: Long,
    )

    private external fun nativeRequestSchema(
        context: Long,
        ticket: Long,
    )

    private external fun nativeGetStats(
        context: Long,
        ticket: Long,
    )

    private external fun nativeUploadFile(
        context: Long,
        path: String,
        ticket: Long,
    )

    private external fun nativeUploadBytes(
        context: Long,
        data: java.nio.ByteBuffer,
        length: Int,
        name: String,
        mimeType: String?,
        ticket: Long,
    )

    private external fun nativeRequestClip(
        context: Long,
        durationSeconds: Double,
        ticket: Long,
    )

    private external fun nativeRequestRecording(
        context: Long,
        ticket: Long,
    )

    private external fun nativeInitProgress(completions: Class<*>)

    private external fun nativeDownloadClip(
        context: Long,
        playlistUrl: String,
        jwt: String?,
        outPath: String,
        predictedReadyAtMs: Double,
        readyTimeoutSeconds: Double,
        local: Boolean,
        wantProgress: Boolean,
        ticket: Long,
    )

    private external fun nativeFetchJwt(
        apiUrl: String,
        apiKey: String,
        optionsJson: String?,
        local: Boolean,
        ticket: Long,
    )

    /**
     * Global references the bridge could not release, kept forever on purpose.
     *
     * `reactor_destroy` returning -1 means a callback is still executing and could not be waited
     * for; the handle is gone either way, but that callback still holds the references. Releasing
     * them is a use-after-free; leaking them is correct, and the leak is bounded by how often a
     * client is torn down from inside its own callback.
     *
     * Kept as a count rather than as the pointers themselves: nothing may ever look at them
     * again, and a list of addresses nobody may dereference is an invitation. The count is what
     * the endurance suite asserts stays at zero in A14 — with `assertAlwaysZero`, because a leak
     * already present on the first cycle would otherwise become the accepted baseline.
     */
    @Volatile
    var orphanedContexts: Int = 0
        private set

    @Synchronized
    private fun recordOrphan() {
        orphanedContexts += 1
    }

    /**
     * Handles created and not yet closed.
     *
     * For the endurance suite, which needs to tell "this scenario leaks a client every cycle" from
     * "this scenario holds one on purpose". A churn scenario asserts it returns to zero at every
     * cycle boundary; a steady one expects exactly one for the whole run.
     *
     * Not a debugging aid bolted on: a count that is only correct when someone remembers to
     * maintain it is worse than none, so it moves in the same two places the handle itself does —
     * [create] and [Handle.close] — and nowhere else.
     */
    @Volatile
    var liveClients: Int = 0
        private set

    @Synchronized
    private fun clientOpened() {
        liveClients += 1
    }

    @Synchronized
    private fun clientClosed() {
        liveClients -= 1
    }

    /**
     * Tell the bridge where to settle completions. Idempotent, and done once before the first
     * handle exists — the lookup needs a thread with an application class loader, which an
     * FFI-owned callback thread does not have.
     */
    @Synchronized
    private fun ensureCompletionsWired() {
        if (completionsWired) return
        nativeInitCompletions(Completions::class.java)
        nativeInitProgress(Completions::class.java)
        completionsWired = true
    }

    @Volatile
    private var completionsWired = false

    private external fun nativeFrameDelivered()

    /**
     * Mark the frame the current video callback is delivering as handed to the app.
     *
     * Ends the frame's delivery stage in the client statistics. Only meaningful from inside the
     * video callback, on its thread, right before the app's handler runs — `reactor_frame_delivered`
     * does nothing anywhere else. Takes no handle.
     */
    fun frameDelivered() = nativeFrameDelivered()

    /** A live handle. Safe to use from any thread, including against its own [close] — see [lifecycle]. */
    class Handle internal constructor(
        private val context: Long,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        /**
         * Held across a getter and across [close], and nothing else.
         *
         * `checkOpen` alone only rules out a handle that is *already* closed. It does not stop a
         * getter that passed the check and then lost the race: `nativeDestroy` frees the Context
         * the getter is about to hand to `nativeStatus`, which is a use-after-free at exactly the
         * boundary this class exists to make safe. The class doc used to say the object model
         * serialised this; nothing enforced that, and a comment is not a lock.
         *
         * Cheap because nothing holds it for long: the getters are reads the native side answers
         * immediately, and the suspend operations take it only around *launching* their native
         * call — never across the await, which would mean suspending while holding a monitor.
         */
        private val lifecycle = Any()

        /**
         * Create or adopt a session and bring the transport up, with the *launch* of the native
         * call under the lifecycle lock.
         *
         * `checkOpen()` on its own left the same window the getters had: a caller that passed the
         * check and was then descheduled would hand a freed Context to `nativeConnect`, because
         * `close()` ran `nativeDestroy` in between. Taking the lock around the launch closes it,
         * and costs nothing — these calls register a completion and return; the waiting happens
         * outside the lock, where it must, since a suspension may not hold a monitor.
         */
        suspend fun connect(sessionId: String?) {
            launch("connect", decode = { }) { ticket -> nativeConnect(context, sessionId, ticket) }
        }

        /** End the session server-side. */
        suspend fun disconnect() {
            launch("disconnect", decode = { }) { ticket -> nativeDisconnect(context, ticket) }
        }

        /** Cycle the connection, keeping the session. */
        suspend fun reconnect() {
            launch("reconnect", decode = { }) { ticket -> nativeReconnect(context, ticket) }
        }

        /**
         * Register a completion owned by this handle and launch [call] under the lifecycle lock.
         *
         * The one way an operation of this handle starts. Owned, so closing this handle settles
         * it and no other client's; launched under the lock, so it cannot hand a Context that
         * [close] has already freed to the native side.
         */
        private suspend fun <T> launch(
            operation: String,
            decode: (String?) -> T,
            call: (ticket: Long) -> Unit,
        ): T = Completions.await(operation, decode, owner = this) { ticket -> withOpenHandle { call(ticket) } }

        suspend fun publishTrack(name: String) {
            launch("publish_track", decode = { }) { ticket -> nativePublishTrack(context, name, ticket) }
        }

        /**
         * Unpublish, which the ABI answers synchronously with an error payload rather than a
         * completion. A non-null return is the failure.
         */
        fun unpublishTrack(name: String): String? = withOpenHandle { nativeUnpublishTrack(context, name) }

        suspend fun pauseTrack(name: String) {
            launch("pause_track", decode = { }) { ticket -> nativePauseTrack(context, name, ticket) }
        }

        suspend fun resumeTrack(name: String) {
            launch("resume_track", decode = { }) { ticket -> nativeResumeTrack(context, name, ticket) }
        }

        fun pushVideoFrame(
            name: String,
            pixels: java.nio.ByteBuffer,
            width: Int,
            height: Int,
            userData: ByteArray?,
        ) {
            withOpenHandle { nativePushVideoFrame(context, name, pixels, width, height, userData) }
        }

        fun pushAudioFrame(
            name: String,
            pcm: java.nio.ByteBuffer,
            samplesPerChannel: Int,
            sampleRate: Int,
            channels: Int,
        ) {
            withOpenHandle { nativePushAudioFrame(context, name, pcm, samplesPerChannel, sampleRate, channels) }
        }

        /**
         * Send a command and await its correlated reply.
         *
         * `decode` runs before the promise is claimed, so a reply that will not parse fails with
         * DECODE_FAILED rather than hanging — see Completions.
         */
        suspend fun sendCommand(
            name: String,
            argsJson: String?,
            uploadsJson: String?,
        ): CommandReply =
            launch(
                "send_command",
                decode = ::decodeCommandReply,
            ) { ticket -> nativeSendCommand(context, name, argsJson, uploadsJson, ticket) }

        suspend fun requestSchema(): Map<String, Any?> =
            launch("request_schema", decode = ::decodeSchema) { ticket -> nativeRequestSchema(context, ticket) }

        suspend fun stats(): Stats = launch("get_stats", decode = ::decodeStats) { ticket -> nativeGetStats(context, ticket) }

        /**
         * Upload a file the native side opens later, from its own task.
         *
         * Started through [launch], like every operation of this handle.
         */
        suspend fun uploadFile(path: String): FileRef =
            launch("upload_file", decode = Uploads::decode) { ticket -> nativeUploadFile(context, path, ticket) }

        suspend fun uploadBytes(
            data: java.nio.ByteBuffer,
            length: Int,
            name: String,
            mimeType: String?,
        ): FileRef =
            launch("upload_bytes", decode = Uploads::decode) { ticket -> nativeUploadBytes(context, data, length, name, mimeType, ticket) }

        suspend fun requestClip(durationSeconds: Double): Clip =
            launch("request_clip", decode = Recordings::decodeClip) { ticket -> nativeRequestClip(context, durationSeconds, ticket) }

        suspend fun requestRecording(): Clip =
            launch("request_recording", decode = Recordings::decodeClip) { ticket -> nativeRequestRecording(context, ticket) }

        /**
         * Download a clip.
         *
         * Deliberately does **not** refuse a closed handle: a download outlives the handle it was
         * given, and refusing to start one because the client is closing would be inventing a
         * rule the ABI does not have — `reactor_download_clip` takes a null handle. What it must
         * never take is a *freed* one, so the launch is under the lifecycle lock and passes 0, which
         * the bridge turns into that null, once [close] has run.
         *
         * Owned by this handle all the same, so closing it settles the caller rather than leaving
         * them waiting on a download whose client has gone — with a message saying it may finish.
         */
        suspend fun downloadClip(
            playlistUrl: String,
            jwt: String?,
            outPath: String,
            predictedReadyAtMs: Double,
            readyTimeoutSeconds: Double,
            local: Boolean,
            onProgress: ((Int, Int) -> Unit)?,
        ): DownloadedClip =
            Completions.await("download_clip", decode = Recordings::decodeDownload, owner = this) { ticket ->
                if (onProgress != null) Completions.watchProgress(ticket, onProgress)
                synchronized(lifecycle) {
                    nativeDownloadClip(
                        if (closed.get()) 0L else context,
                        playlistUrl,
                        jwt,
                        outPath,
                        predictedReadyAtMs,
                        readyTimeoutSeconds,
                        local,
                        onProgress != null,
                        ticket,
                    )
                }
            }

        val status: String?
            get() = withOpenHandle { nativeStatus(context) }

        val sessionId: String?
            get() = withOpenHandle { nativeSessionId(context) }

        val tracks: String?
            get() = withOpenHandle { nativeTracks(context) }

        val pausedTracks: String?
            get() = withOpenHandle { nativePausedTracks(context) }

        /** Check and read under one lock, so the answer cannot be about a freed Context. */
        private inline fun <T> withOpenHandle(read: () -> T): T =
            synchronized(lifecycle) {
                checkOpen()
                read()
            }

        private fun checkOpen() {
            check(!closed.get()) { "This Reactor handle is closed" }
        }

        /**
         * Destroy the handle. Idempotent: a second call is a no-op rather than a double free,
         * which at this boundary is a process death rather than an exception.
         */
        override fun close() {
            synchronized(lifecycle) {
                if (!closed.compareAndSet(false, true)) return
                // Inside the lock, not after it: a getter that has already passed checkOpen must
                // not still be holding the Context when this frees it.
                if (nativeDestroy(context) != 0) recordOrphan()
                // Under the lock too, so the count the endurance suite reads can never show a
                // handle as live after its Context is gone.
                clientClosed()
            }
        }
    }

    /**
     * Exchange an API key for a short-lived token. Detached, like a download: `reactor_fetch_jwt`
     * takes no handle, so this is not bounded by any client's lifetime and deliberately lives on
     * the object — which is what makes it callable *before* the first handle exists, the one place
     * a key-only connect mints from.
     *
     * @param apiUrl the coordinator's base URL
     * @param apiKey the key to exchange
     * @param optionsJson scoping options as JSON, or null for a token carrying everything the
     *   key's roles allow — fine server to server, wrong to hand to a client you do not control
     * @return the token
     */
    suspend fun fetchJwt(
        apiUrl: String,
        apiKey: String,
        optionsJson: String?,
        local: Boolean,
    ): String {
        // A connect mints before the first handle exists, so the load and the wiring a create()
        // would have done must happen here — the completion thread resolves Completions through
        // the refs ensureCompletionsWired captures, and it does not know they are still null.
        NativeLibrary.ensureLoaded()
        ensureCompletionsWired()
        return Completions.await("fetch_jwt", Authentication::decodeJwt) { ticket ->
            nativeFetchJwt(apiUrl, apiKey, optionsJson, local, ticket)
        }
    }

    /**
     * Create a handle.
     *
     * @throws UnsatisfiedLinkError if the native stack cannot load, or if [NativeEvents] and the
     *   bridge have drifted apart.
     * @throws IllegalStateException if the native layer refused to create a client.
     */
    fun create(
        apiUrl: String,
        modelName: String,
        jwt: String? = null,
        local: Boolean = false,
        autoResumeTracks: Boolean = true,
        listener: NativeEvents,
        sdkVersion: String? = null,
        sdkType: String? = "android",
    ): Handle {
        NativeLibrary.ensureLoaded()
        ensureCompletionsWired()
        val context =
            nativeCreate(apiUrl, modelName, jwt, local, autoResumeTracks, listener, sdkVersion, sdkType)
        check(context != 0L) { "The native layer refused to create a client for $modelName" }
        // After the check, not before: a refused create produced no handle to close, and counting
        // it would leave the number permanently one too high.
        clientOpened()
        // One pointer: the context owns the ReactorHandle, so there are not two things that
        // have to agree about which client this is.
        return Handle(context = context)
    }
}
