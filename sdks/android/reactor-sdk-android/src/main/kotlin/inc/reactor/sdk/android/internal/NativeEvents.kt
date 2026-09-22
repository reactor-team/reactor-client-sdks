package inc.reactor.sdk.android.internal

/**
 * What the native bridge calls back into.
 *
 * The method names and signatures are resolved by [NativeClient] at creation time, from
 * `client.cpp`'s `GetMethodID` calls. Renaming a method here without renaming it there fails at
 * `nativeCreate` with a `NoSuchMethodError` naming the drift, rather than at the first event.
 *
 * Every method arrives on **a thread the FFI owns**, not on a Kotlin dispatcher. Implementations
 * must not block: control events are cheap, but the same threads deliver media later in the
 * stack, where blocking is the backpressure. Marshalling to a dispatcher is the object model's
 * job (A05), not this interface's.
 *
 * An exception thrown from any of these is caught and logged by the bridge and then discarded —
 * a pending Java exception on an FFI-owned thread has nowhere to go, and the next JNI call made
 * with one pending is undefined behaviour.
 */
internal interface NativeEvents {
    fun onStatus(status: String?)

    fun onError(errorJson: String?)

    /** Null when the session is cleared, which is distinct from "no event". */
    fun onSessionId(sessionId: String?)
}
