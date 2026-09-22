package inc.reactor.sdk.android

import java.nio.ByteBuffer

/**
 * One decoded video frame, in BGRA.
 *
 * **[pixels] is borrowed.** It is a direct [ByteBuffer] over memory the native layer owns, valid
 * only until the handler returns — the FFI frees it the moment it does. Keeping the buffer and
 * reading it later is a use-after-free, which reproduces under load and not in tests. Copy what
 * you keep, or consume it in place with the renderer helpers in `reactor-sdk-android-media`.
 *
 * The trailer fields are 0 when the sender attached no metadata. A tag is dropped unless the far
 * end declared that it reads tags, so an absent [frameId] is a statement about the sender rather
 * than about this frame.
 */
public class VideoFrame internal constructor(
    /** The track this arrived on. Every recvonly video track decodes into one callback. */
    public val trackName: String,
    /** Borrowed BGRA pixels: `width * height * 4` bytes. Valid only for this call. */
    public val pixels: ByteBuffer,
    public val width: Int,
    public val height: Int,
    /** The sender's frame counter, or 0 when it attached no metadata. */
    public val frameId: Long,
    /**
     * When the sender says it captured this, in microseconds **on the sender's own clock**.
     *
     * Differences between stamps from one sender are what it supports. It is not comparable with
     * a local clock, and subtracting it from `System.currentTimeMillis()` is meaningless.
     */
    public val timestampUs: Long,
    /** The sender's opaque tag, or null when there was none. Borrowed, like [pixels]. */
    public val userData: ByteArray?,
) {
    /** A copy of [pixels] that outlives the callback. */
    public fun copyPixels(): ByteArray {
        val duplicate = pixels.duplicate()
        return ByteArray(duplicate.remaining()).also { duplicate.get(it) }
    }

    override fun toString(): String = "VideoFrame($trackName, ${width}x$height, frameId=$frameId, timestampUs=$timestampUs)"
}
