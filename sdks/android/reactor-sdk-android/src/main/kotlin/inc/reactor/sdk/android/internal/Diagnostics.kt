package inc.reactor.sdk.android.internal

/**
 * Resource counts, for the endurance suite and nothing else.
 *
 * **Not part of the supported API.** It is `public` only because Kotlin's `internal` is scoped to
 * a compilation module, and the endurance suite is a separate one — the same reason the Java SDK
 * puts `ClientPeer.liveClients()` in an `internal` package rather than behind a modifier. It lives
 * under `.internal` so that is visible at the import, and it will not be documented, versioned or
 * kept stable.
 *
 * These exist because the suite has to tell three things apart that look identical from outside:
 * a scenario that leaks a client every cycle, one that holds one on purpose, and one whose
 * teardown left a JNI global behind. Nothing in the consumer-facing API answers that.
 */
public object Diagnostics {
    /** Native handles created and not yet closed. */
    public val liveClients: Int get() = NativeClient.liveClients

    /**
     * JNI globals the bridge could not release, kept forever on purpose.
     *
     * `reactor_destroy` returning -1 means a callback is still executing; the references it holds
     * cannot be freed without a use-after-free, so they are leaked deliberately. Bounded by how
     * often a client is torn down from inside its own callback — which is to say, it should be
     * zero, and the endurance suite asserts exactly that with `assertAlwaysZero`.
     */
    public val orphanedGlobals: Int get() = NativeClient.orphanedContexts
}
