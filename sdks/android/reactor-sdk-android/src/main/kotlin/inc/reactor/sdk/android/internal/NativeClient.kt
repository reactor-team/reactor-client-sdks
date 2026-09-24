package inc.reactor.sdk.android.internal

import inc.reactor.sdk.android.CommandReply
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
     * Tell the bridge where to settle completions. Idempotent, and done once before the first
     * handle exists — the lookup needs a thread with an application class loader, which an
     * FFI-owned callback thread does not have.
     */
    @Synchronized
    private fun ensureCompletionsWired() {
        if (completionsWired) return
        nativeInitCompletions(Completions::class.java)
        completionsWired = true
    }

    @Volatile
    private var completionsWired = false

    /** A live handle. Not thread-safe against its own [close]; the object model serialises that. */
    class Handle internal constructor(
        private val context: Long,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        /** Create or adopt a session and bring the transport up. */
        suspend fun connect(sessionId: String?) {
            checkOpen()
            Completions.await("connect", decode = { }) { ticket ->
                nativeConnect(context, sessionId, ticket)
            }
        }

        /** End the session server-side. */
        suspend fun disconnect() {
            checkOpen()
            Completions.await("disconnect", decode = { }) { ticket ->
                nativeDisconnect(context, ticket)
            }
        }

        /** Cycle the connection, keeping the session. */
        suspend fun reconnect() {
            checkOpen()
            Completions.await("reconnect", decode = { }) { ticket ->
                nativeReconnect(context, ticket)
            }
        }

        suspend fun publishTrack(name: String) {
            checkOpen()
            Completions.await("publish_track", decode = { }) { ticket ->
                nativePublishTrack(context, name, ticket)
            }
        }

        /**
         * Unpublish, which the ABI answers synchronously with an error payload rather than a
         * completion. A non-null return is the failure.
         */
        fun unpublishTrack(name: String): String? {
            checkOpen()
            return nativeUnpublishTrack(context, name)
        }

        suspend fun pauseTrack(name: String) {
            checkOpen()
            Completions.await("pause_track", decode = { }) { ticket ->
                nativePauseTrack(context, name, ticket)
            }
        }

        suspend fun resumeTrack(name: String) {
            checkOpen()
            Completions.await("resume_track", decode = { }) { ticket ->
                nativeResumeTrack(context, name, ticket)
            }
        }

        fun pushVideoFrame(
            name: String,
            pixels: java.nio.ByteBuffer,
            width: Int,
            height: Int,
            userData: ByteArray?,
        ) {
            checkOpen()
            nativePushVideoFrame(context, name, pixels, width, height, userData)
        }

        fun pushAudioFrame(
            name: String,
            pcm: java.nio.ByteBuffer,
            samplesPerChannel: Int,
            sampleRate: Int,
            channels: Int,
        ) {
            checkOpen()
            nativePushAudioFrame(context, name, pcm, samplesPerChannel, sampleRate, channels)
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
            Completions.await("send_command", decode = ::decodeCommandReply) { ticket ->
                checkOpen()
                nativeSendCommand(context, name, argsJson, uploadsJson, ticket)
            }

        suspend fun requestSchema(): Map<String, Any?> =
            Completions.await("request_schema", decode = ::decodeSchema) { ticket ->
                checkOpen()
                nativeRequestSchema(context, ticket)
            }

        suspend fun stats(): Stats =
            Completions.await("get_stats", decode = ::decodeStats) { ticket ->
                checkOpen()
                nativeGetStats(context, ticket)
            }

        suspend fun uploadFile(path: String): FileRef =
            Completions.await("upload_file", decode = Uploads::decode) { ticket ->
                checkOpen()
                nativeUploadFile(context, path, ticket)
            }

        suspend fun uploadBytes(
            data: java.nio.ByteBuffer,
            length: Int,
            name: String,
            mimeType: String?,
        ): FileRef =
            Completions.await("upload_bytes", decode = Uploads::decode) { ticket ->
                checkOpen()
                nativeUploadBytes(context, data, length, name, mimeType, ticket)
            }

        val status: String?
            get() = checkOpen().let { nativeStatus(context) }

        val sessionId: String?
            get() = checkOpen().let { nativeSessionId(context) }

        val tracks: String?
            get() = checkOpen().let { nativeTracks(context) }

        val pausedTracks: String?
            get() = checkOpen().let { nativePausedTracks(context) }

        private fun checkOpen() {
            check(!closed.get()) { "This Reactor handle is closed" }
        }

        /**
         * Destroy the handle. Idempotent: a second call is a no-op rather than a double free,
         * which at this boundary is a process death rather than an exception.
         */
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            if (nativeDestroy(context) != 0) recordOrphan()
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
        listener: NativeEvents,
        sdkVersion: String? = null,
        sdkType: String? = "android",
    ): Handle {
        NativeLibrary.ensureLoaded()
        ensureCompletionsWired()
        val context = nativeCreate(apiUrl, modelName, jwt, local, listener, sdkVersion, sdkType)
        check(context != 0L) { "The native layer refused to create a client for $modelName" }
        // One pointer: the context owns the ReactorHandle, so there are not two things that
        // have to agree about which client this is.
        return Handle(context = context)
    }
}
