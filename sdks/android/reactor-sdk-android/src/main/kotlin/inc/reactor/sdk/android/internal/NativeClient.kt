package inc.reactor.sdk.android.internal

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
            Completions.await("connect", decode = { }) { ticket ->
                withOpenHandle { nativeConnect(context, sessionId, ticket) }
            }
        }

        /** End the session server-side. */
        suspend fun disconnect() {
            Completions.await("disconnect", decode = { }) { ticket ->
                withOpenHandle { nativeDisconnect(context, ticket) }
            }
        }

        /** Cycle the connection, keeping the session. */
        suspend fun reconnect() {
            Completions.await("reconnect", decode = { }) { ticket ->
                withOpenHandle { nativeReconnect(context, ticket) }
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
            }
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
        // One pointer: the context owns the ReactorHandle, so there are not two things that
        // have to agree about which client this is.
        return Handle(context = context)
    }
}
