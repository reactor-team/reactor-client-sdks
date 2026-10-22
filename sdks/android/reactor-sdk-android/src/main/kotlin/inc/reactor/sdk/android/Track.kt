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

    /** Whether a sender is attached — see [PublishState]. */
    public val publishState: PublishState
        get() = owner.publishState(name)

    /** True only in [PublishState.PUBLISHED]. */
    public val published: Boolean
        get() = publishState == PublishState.PUBLISHED

    /**
     * Attach a sender to this slot.
     *
     * Publishing is what puts a sender behind the slot; pushing before it completes is dropped by
     * the FFI. The state does **not** survive the session leaving `ready` — a reconnect resumes
     * recvonly tracks and nothing else — so a slot published before one is not published after.
     *
     * Not cancellable mid-flight: the native publish runs to its answer regardless, so a cancelled
     * caller waits for that answer, the state records it, and the cancellation is thrown after.
     *
     * @throws InvalidStateException on a recvonly track, which nothing can be pushed into.
     * @throws DisconnectedException when the session left `ready` while the publish was in flight.
     */
    public suspend fun publish() {
        requireSendable("publish")
        owner.publish(name)
    }

    /**
     * Detach the sender.
     *
     * Only a *successful* unpublish clears the local state: a failed one that cleared it anyway
     * would be unretryable, because the SDK would refuse the next `unpublish()` as a no-op.
     */
    public suspend fun unpublish() {
        requireSendable("unpublish")
        owner.unpublish(name)
    }

    /**
     * Stop this track's transceiver. Nothing is generated while paused.
     *
     * @throws InvalidStateException on a sendonly track: what stops one sending is [unpublish].
     */
    public suspend fun pause() {
        requireReceivable("pause", "To stop sending into it, unpublish() it.")
        owner.pause(name)
    }

    /**
     * Restart it.
     *
     * @throws InvalidStateException on a sendonly track: what starts one sending is [publish].
     */
    public suspend fun resume() {
        requireReceivable("resume", "To start sending into it, publish() it.")
        owner.resume(name)
    }

    /**
     * Push one BGRA video frame.
     *
     * @param pixels a **direct** [java.nio.ByteBuffer] with exactly `width * height * 4` bytes
     *   remaining, read from its current position. Direct because the native layer reads it in
     *   place: a heap buffer has no address to pass, and copying every frame would be an
     *   expensive default for the common case.
     * @param userData an opaque per-frame tag. Dropped unless the far end declared that it reads
     *   tags, so attaching one is safe whatever the model supports.
     */
    public fun pushFrame(
        pixels: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        userData: ByteArray? = null,
    ) {
        requireSendable("pushFrame")
        requireKindForPush(TrackKind.VIDEO, "video frames")
        requirePublished()

        // Positive first: the native layer takes both as unsigned, so -1 x -1 would pass the
        // length check below with a 4-byte buffer and then be read as an enormous frame.
        if (width <= 0 || height <= 0) {
            throw ErrorCode.toException(
                wire = "BAD_REQUEST",
                message = "'$name' was given a ${width}x$height frame; both dimensions must be positive.",
                operation = "pushFrame",
            )
        }
        val expected = width.toLong() * height.toLong() * 4L
        if (pixels.remaining().toLong() != expected) {
            throw ErrorCode.toException(
                wire = "BAD_REQUEST",
                message =
                    "'$name' was given ${pixels.remaining()} bytes for a ${width}x$height " +
                        "BGRA frame, which needs exactly $expected. Reading past the end is what the " +
                        "native layer would do with the wrong length.",
                operation = "pushFrame",
            )
        }
        require(pixels.isDirect) {
            "pushFrame needs a direct ByteBuffer — ByteBuffer.allocateDirect(), not allocate()"
        }
        // A slice, because the native side reads from the buffer's base address: without it a
        // buffer with a nonzero position would send the wrong bytes.
        owner.pushVideoFrame(name, pixels.slice(), width, height, userData)
    }

    /**
     * Push interleaved signed 16-bit PCM.
     *
     * @param pcm a **direct** [java.nio.ByteBuffer] with exactly `samplesPerChannel * channels * 2`
     *   bytes remaining, read from its current position.
     * @param samplesPerChannel frames per channel, **not** total samples — the ABI's own units.
     * @param sampleRate 8000, 16000, 24000, 32000, 44100 or 48000 Hz.
     * @param channels 1 or 2.
     */
    public fun pushFrame(
        pcm: java.nio.ByteBuffer,
        samplesPerChannel: Int,
        sampleRate: Int,
        channels: Int,
    ) {
        requireSendable("pushFrame")
        requireKindForPush(TrackKind.AUDIO, "audio frames")
        requirePublished()
        // Checked here because the native layer takes every count as unsigned and reads exactly
        // what they describe: a negative count, or a buffer shorter than they say, reads past it.
        if (sampleRate !in SUPPORTED_SAMPLE_RATES || channels !in 1..2) {
            throw ErrorCode.toException(
                wire = "BAD_REQUEST",
                message =
                    "'$name' was given $sampleRate Hz with $channels channel(s); the supported " +
                        "formats are ${SUPPORTED_SAMPLE_RATES.joinToString()} Hz and 1 or 2 channels.",
                operation = "pushFrame",
            )
        }
        if (samplesPerChannel <= 0) {
            throw ErrorCode.toException(
                wire = "BAD_REQUEST",
                message = "'$name' was given $samplesPerChannel samples per channel; it must be positive.",
                operation = "pushFrame",
            )
        }
        val expected = samplesPerChannel.toLong() * channels.toLong() * 2L
        if (pcm.remaining().toLong() != expected) {
            throw ErrorCode.toException(
                wire = "BAD_REQUEST",
                message =
                    "'$name' was given ${pcm.remaining()} bytes for $samplesPerChannel samples " +
                        "per channel x $channels channel(s) of 16-bit PCM, which needs exactly $expected.",
                operation = "pushFrame",
            )
        }
        require(pcm.isDirect) {
            "pushFrame needs a direct ByteBuffer — ByteBuffer.allocateDirect(), not allocate()"
        }
        // The native side reads the samples as 16-bit values straight from the slice's address,
        // which an odd position would leave misaligned — undefined behaviour, not just slow.
        if (pcm.position() % 2 != 0) {
            throw ErrorCode.toException(
                wire = "BAD_REQUEST",
                message =
                    "'$name' was given PCM at byte position ${pcm.position()}; 16-bit samples " +
                        "must start at an even position.",
                operation = "pushFrame",
            )
        }
        owner.pushAudioFrame(name, pcm.slice(), samplesPerChannel, sampleRate, channels)
    }

    private fun requireSendable(operation: String) {
        if (direction != TrackDirection.SENDONLY) {
            throw ErrorCode.toException(
                wire = "INVALID_STATE",
                message =
                    "'$name' is ${direction.wire} — nothing can be pushed into it. " +
                        "A recvonly track is one you receive from with onFrame().",
                operation = operation,
            )
        }
    }

    private fun requireKindForPush(
        expected: TrackKind,
        what: String,
    ) {
        if (kind != expected) {
            throw ErrorCode.toException(
                wire = "INVALID_STATE",
                message = "'$name' carries ${kind.wire}, not ${expected.wire} — it accepts no $what",
                operation = "pushFrame",
            )
        }
    }

    private fun requirePublished() {
        when (publishState) {
            PublishState.PUBLISHED -> return
            PublishState.PUBLISHING -> throw ErrorCode.toException(
                wire = "INVALID_STATE",
                message =
                    "'$name' is still publishing. Await publish() before pushing — until it " +
                        "completes there is no sender behind the slot and the frame is dropped.",
                operation = "pushFrame",
            )
            PublishState.UNPUBLISHED -> throw ErrorCode.toException(
                wire = "INVALID_STATE",
                message =
                    "'$name' is not published. Call publish() first — publishing is what " +
                        "puts a sender behind the slot, and a push before it is silently dropped.",
                operation = "pushFrame",
            )
        }
    }

    private fun requireReceivable(
        operation: String = "onFrame",
        instead: String = "A sendonly track is one you push into with pushFrame().",
    ) {
        if (direction != TrackDirection.RECVONLY) {
            throw ErrorCode.toException(
                wire = "INVALID_STATE",
                message = "'$name' is ${direction.wire} — it never receives frames. $instead",
                operation = operation,
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

    fun publishState(track: String): PublishState

    suspend fun publish(track: String)

    suspend fun unpublish(track: String)

    suspend fun pause(track: String)

    suspend fun resume(track: String)

    fun pushVideoFrame(
        track: String,
        pixels: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        userData: ByteArray?,
    )

    fun pushAudioFrame(
        track: String,
        pcm: java.nio.ByteBuffer,
        samplesPerChannel: Int,
        sampleRate: Int,
        channels: Int,
    )
}

/** The capture rates the native audio path accepts; anything else is logged and dropped there. */
private val SUPPORTED_SAMPLE_RATES = setOf(8000, 16000, 24000, 32000, 44100, 48000)
