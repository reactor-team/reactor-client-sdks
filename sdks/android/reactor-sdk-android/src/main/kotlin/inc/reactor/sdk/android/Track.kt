package inc.reactor.sdk.android

/**
 * A named media slot the **model** declares.
 *
 * One type for all four kind/direction combinations, because the operations are the same
 * operations. What a caller may do with it is decided by [kind] and [direction], and this SDK
 * refuses the combinations that would otherwise reach the native layer, find nothing to do, and
 * return — leaving a loop pushing at 30 fps into a model that receives nothing.
 */
public class Track internal constructor(
    public val name: String,
    public val kind: TrackKind,
    public val direction: TrackDirection,
    /** The transceiver's mid, once negotiated. Null until then. */
    public val mid: String? = null,
    private val owner: TrackOwner,
) {
    /**
     * Receive frames on this track, **inline on the FFI's delivery thread**.
     *
     * This is the primitive, not a convenience over a Flow. Blocking here is the backpressure:
     * while the handler runs, the FFI keeps only the newest video frame and drops the rest, which
     * is a bounded, well-defined loss. Handing frames to an unbounded queue instead trades that
     * for unbounded latency and memory — the reason `frames` is conflated.
     *
     * The frame's buffer is borrowed and valid only until this returns.
     *
     * @throws InvalidStateException on a sendonly track, which never receives.
     */
    public fun onFrame(handler: (VideoFrame) -> Unit) {
        requireReceivable()
        requireKind(TrackKind.VIDEO, "video frames")
        owner.setVideoHandler(name, handler)
    }

    /**
     * As [onFrame], for audio.
     *
     * The same name on purpose: the object model every Reactor SDK shares has **one** frame API
     * for both kinds, and [kind] is what decides which a handler receives. A separate
     * `onAudioFrame` would be a second way to say the same thing and the one no other binding has.
     *
     * `@JvmName` because the two overloads erase to the same JVM signature — both are
     * `Function1` — which is a platform declaration clash rather than anything about Kotlin's own
     * resolution. Java callers see `onAudioFrame`; Kotlin callers write `onFrame`, annotating the
     * lambda's parameter when nothing else in the call fixes the type.
     *
     * @throws InvalidStateException on a sendonly or video track.
     */
    @JvmName("onAudioFrame")
    public fun onFrame(handler: (AudioFrame) -> Unit) {
        requireReceivable()
        requireKind(TrackKind.AUDIO, "audio frames")
        owner.setAudioHandler(name, handler)
    }

    /** Stop receiving. Registering again replaces the handler. */
    public fun removeHandlers() {
        owner.clearHandlers(name)
    }

    /** Whether this track is currently paused. */
    public val paused: Boolean
        get() = owner.isPaused(name)

    private fun requireReceivable() {
        if (direction != TrackDirection.RECVONLY) {
            throw ErrorCode.toException(
                wire = "INVALID_STATE",
                message =
                    "'$name' is ${direction.wire} — it never receives frames. " +
                        "A sendonly track is one you push into with pushFrame().",
                operation = "onFrame",
            )
        }
    }

    private fun requireKind(
        expected: TrackKind,
        what: String,
    ) {
        if (kind != expected) {
            throw ErrorCode.toException(
                wire = "INVALID_STATE",
                message = "'$name' carries ${kind.wire}, not ${expected.wire} — it delivers no $what",
                operation = "onFrame",
            )
        }
    }

    override fun toString(): String = "Track($name, ${kind.wire}, ${direction.wire})"
}

/** What a [Track] needs from the client that owns it. Kept narrow so tracks are testable alone. */
internal interface TrackOwner {
    fun setVideoHandler(
        track: String,
        handler: (VideoFrame) -> Unit,
    )

    fun setAudioHandler(
        track: String,
        handler: (AudioFrame) -> Unit,
    )

    fun clearHandlers(track: String)

    fun isPaused(track: String): Boolean
}
