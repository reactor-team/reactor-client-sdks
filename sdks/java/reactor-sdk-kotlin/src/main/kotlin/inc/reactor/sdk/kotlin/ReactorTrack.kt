package inc.reactor.sdk.kotlin

import inc.reactor.sdk.AudioFrame
import inc.reactor.sdk.PublishState
import inc.reactor.sdk.Subscription
import inc.reactor.sdk.Track
import inc.reactor.sdk.TrackDirection
import inc.reactor.sdk.TrackKind
import inc.reactor.sdk.VideoFrame
import java.util.Objects
import kotlinx.coroutines.future.await

/**
 * One named media slot, with `suspend` where the Java track returns a `CompletableFuture`.
 *
 * Every refusal the Java track makes is made here, by the same code: a name the session never
 * declared, a push against a recvonly track, a handler on a sendonly one, a frame pushed before
 * `publish()` or while paused. The exception is the same typed [inc.reactor.sdk.ReactorException],
 * thrown from the same place.
 */
public class ReactorTrack(
    /** The Java track underneath. Everything here forwards to it. */
    public val java: Track
) {

    /** As the session declared it. */
    public val name: String
        get() = java.name()

    /** Video or audio. */
    public val kind: TrackKind
        get() = java.kind()

    /** Which way it points. */
    public val direction: TrackDirection
        get() = java.direction()

    /** The media id the session assigned, or null before there is one. */
    public val mid: String?
        get() = java.mid().orElse(null)

    /** Whether this track is currently paused. */
    public val isPaused: Boolean
        get() = java.isPaused()

    /** Whether a sender is behind this track. */
    public val isPublished: Boolean
        get() = java.isPublished()

    /** Published, publishing, or neither. */
    public val publishState: PublishState
        get() = java.publishState()

    // ── Receiving ───────────────────────────────────────────────────────────

    /**
     * Receives this track's video frames on the FFI's own delivery thread.
     *
     * Blocking in [handler] is the backpressure: while it runs, the FFI keeps only the newest
     * frame. The frame is valid only until the handler returns — copy what is kept.
     *
     * @param handler called inline, per frame
     * @return a subscription that removes the handler
     */
    public fun onVideoFrame(handler: (VideoFrame) -> Unit): Subscription = java.onFrame(handler)

    /**
     * Receives this track's audio frames on the FFI's own delivery thread.
     *
     * @param handler called inline, per frame
     * @return a subscription that removes the handler
     */
    public fun onAudioFrame(handler: (AudioFrame) -> Unit): Subscription = java.onFrame(handler)

    // ── Sending ─────────────────────────────────────────────────────────────

    /** Asks for a sender behind this track. Nothing can be pushed until this settles. */
    public suspend fun publish(): Unit = java.publish().await().let {}

    /** Tells the session this track is no longer sending. */
    public fun unpublish(): Unit = java.unpublish()

    /** Stops delivery without unpublishing. */
    public suspend fun pause(): Unit = java.pause().await().let {}

    /** Resumes delivery. */
    public suspend fun resume(): Unit = java.resume().await().let {}

    /**
     * Pushes one video frame.
     *
     * @param bgra the pixels, `width * height * 4` bytes of them
     * @param width in pixels
     * @param height in pixels
     */
    public fun pushFrame(bgra: ByteArray, width: Int, height: Int): Unit =
        java.pushFrame(bgra, width, height)

    /**
     * Pushes one block of audio.
     *
     * @param pcm interleaved signed 16-bit samples
     * @param sampleRate in hertz
     * @param channels how many are interleaved
     */
    public fun pushFrame(pcm: ShortArray, sampleRate: Int, channels: Int): Unit =
        java.pushFrame(pcm, sampleRate, channels)

    /**
     * Asks the platform for a different bitrate on this track.
     *
     * @param minBps the floor
     * @param maxBps the ceiling
     */
    public suspend fun setBitrate(minBps: Int, maxBps: Int): Unit =
        java.setBitrate(minBps, maxBps).await().let {}

    // By name, kind and direction, not by identity, and not by the Java track underneath. The
    // binding builds a fresh Track on every call — `client.track("x")` and `client.tracks[0]` are
    // two objects — and Track declares no equality of its own, so a caller who stores a track and
    // compares it later needs this side to answer for both. Caching one wrapper per name would
    // have answered too, and wrongly: a track's mid is assigned by the session, so a wrapper held
    // across a reconnect would keep reporting the one from before it.
    //
    // Two clients' same-named tracks therefore compare equal. Nothing in this SDK compares tracks
    // across clients, and the alternative is a wrapper that holds its client alive.
    override fun equals(other: Any?): Boolean =
        other is ReactorTrack &&
            other.name == name &&
            other.kind == kind &&
            other.direction == direction

    override fun hashCode(): Int = Objects.hash(name, kind, direction)

    override fun toString(): String = java.toString()
}

// ── Choosing a track without hardcoding a name ──────────────────────────────

/**
 * The tracks of one kind.
 *
 * @param kind video or audio
 * @return those tracks, in declared order
 */
public fun List<ReactorTrack>.withKind(kind: TrackKind): List<ReactorTrack> = filter {
    it.kind == kind
}

/**
 * The tracks pointing one way.
 *
 * @param direction sendonly or recvonly
 * @return those tracks, in declared order
 */
public fun List<ReactorTrack>.withDirection(direction: TrackDirection): List<ReactorTrack> =
    filter {
        it.direction == direction
    }

/**
 * The only track left, when a caller means "the one".
 *
 * @return that track
 * @throws IllegalArgumentException when there is none, or more than one
 */
public fun List<ReactorTrack>.one(): ReactorTrack =
    when (size) {
        1 -> this[0]
        0 -> throw IllegalArgumentException("no track matched")
        else -> throw IllegalArgumentException("$size tracks matched, not one: ${map { it.name }}")
    }

/**
 * One track by name.
 *
 * @param name as the session declared it
 * @return that track
 * @throws IllegalArgumentException when no track carries that name
 */
public operator fun List<ReactorTrack>.get(name: String): ReactorTrack =
    firstOrNull { it.name == name }
        ?: throw IllegalArgumentException(
            "no track named '$name' — the session declared ${map { it.name }}"
        )
