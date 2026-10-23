package inc.reactor.sdk.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What one [step][Reactor.step] produced: its number in the session, per output track the frames
 * it generated, the files the model kept with it, and — when the step ran but the model reported a
 * failure — why.
 *
 * ```kotlin
 * val result = reactor.step(state = """{"prompt": "a red fox"}""")
 * result.error?.let { why -> Log.w(TAG, "step ${result.step} failed: $why") }
 * for (frame in result.track("main_video").frames) {
 *     show(frame.data(), frame.shape) // (height, width, 3) uint8 RGB
 * }
 * ```
 *
 * Copied out of the native library once, when the step completed; nothing here points into native
 * memory, so it can be kept and shared freely.
 *
 * @property step the step's number in the session, counted from 1: the `step` of the session's
 *   step routes (`GET /sessions/{id}/steps/{step}`)
 * @property tracks the output tracks, in the order the runtime sent them
 * @property elapsed seconds the step's `generate()` took, or the time the model reported for it
 * @property files the extra files the model kept with the step, in the order the runtime listed
 *   them; empty when it kept none or the result was not saved. Only named here: see [StepFile]
 * @property error why the step failed, when it ran and the model reported a failure; null when it
 *   worked. A step that did not run has no result: [Reactor.step] throws [StepRefusedException],
 *   [StepNotReadyException] or [StepCommandFailedException] instead
 */
public data class StepResult(
    public val step: Long,
    public val tracks: List<StepTrackOutput>,
    public val elapsed: Double = 0.0,
    public val files: List<StepFile> = emptyList(),
    public val error: String? = null,
) {
    /**
     * The output of one track, by name.
     *
     * @throws NotFoundException naming the tracks that did produce output, when this one produced
     *   none: a model can legitimately leave a track out of a step, and that is worth telling apart
     *   from a typo
     */
    public fun track(name: String): StepTrackOutput =
        findTrack(name) ?: throw ErrorCode.toException(
            wire = "NOT_FOUND",
            message = "Step $step produced no output on \"$name\". It produced: ${tracks.map { it.name }}.",
            operation = "step",
        )

    /** The output of one track, by name, or null when the step produced none on it. */
    public fun findTrack(name: String): StepTrackOutput? = tracks.firstOrNull { it.name == name }
}

/**
 * The frames one output track produced in a [step][Reactor.step], in production order.
 *
 * @property name the output track's declared name
 * @property kind whether it carries video or audio
 * @property rate the rate the track's frames play at, as the runtime reported it: frames per
 *   second for video, samples per second for audio; 0 when the runtime did not say
 * @property frames what it produced; may be empty, since a model can legitimately produce nothing
 *   on a track in a step
 */
public data class StepTrackOutput(
    public val name: String,
    public val kind: TrackKind,
    public val rate: Double = 0.0,
    public val frames: List<StepFrame>,
)

/**
 * An extra file the model kept with a step, as [StepResult.files] names it.
 *
 * The result names the file; it does not carry it. Fetch its bytes over HTTP from the session's
 * step results — `GET /sessions/{id}/steps/{step}` answers with each of the step's files and a URL
 * to download it from, or 202 while they are not stored yet.
 *
 * @property name the file's name, one path segment
 * @property contentType its media type, guessed from the name
 * @property size its size in bytes
 */
public data class StepFile(
    public val name: String,
    public val contentType: String,
    public val size: Long,
)

/**
 * One frame a [step][Reactor.step] produced on an output track: an n-dimensional array, its element
 * type and its shape.
 *
 * Video is `(height, width, 3)` `uint8` RGB — three bytes a pixel, in R, G, B order, unlike the BGRA
 * a streamed [VideoFrame] carries. Audio is `(channels, samples)` `int16` PCM, planar rather than
 * interleaved. Multi-byte elements are little-endian, which is the order [data] is already set to,
 * so `frame.data().asShortBuffer()` reads audio samples directly.
 *
 * Unlike a streamed frame this is **not** scoped to a callback: it was copied onto the heap once,
 * when the step completed, and the native result was freed straight away. Keep it as long as you
 * like.
 */
public class StepFrame internal constructor(
    /**
     * How the frame travelled on the wire — `"raw"` today. The bytes in [data] are already decoded
     * whatever this says.
     */
    public val encoding: String,
    /** The element type, in NumPy notation: `"uint8"` for video, `"int16"` for audio. */
    public val dtype: String,
    shape: IntArray,
    private val bytes: ByteArray,
    private val tag: ByteArray?,
    /** When the sender says the frame was captured, in microseconds, or null when it carries none. */
    public val captureTimeUs: Long?,
) {
    private val dimensions: IntArray = shape.copyOf()

    /** The array's dimensions — `(height, width, 3)` for video, `(channels, samples)` for audio. A copy. */
    public val shape: IntArray
        get() = dimensions.copyOf()

    /** How many bytes [data] holds: the product of [shape] times the size of [dtype]. */
    public val byteSize: Int
        get() = bytes.size

    /**
     * The array's bytes, row-major.
     *
     * A read-only, little-endian view over this frame's bytes. Each call answers a fresh view, so
     * its position is the caller's own.
     */
    public fun data(): ByteBuffer = ByteBuffer.wrap(bytes).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)

    /** A copy of the array's bytes. */
    public fun toByteArray(): ByteArray = bytes.copyOf()

    /**
     * What the sender attached to the frame, opaque to the transport. A copy, or null when the
     * sender attached nothing — an attached but empty tag is present and empty.
     */
    public val metadata: ByteArray?
        get() = tag?.copyOf()

    /**
     * Equal to another frame with the same encoding, dtype, shape, bytes, metadata and capture time,
     * so the classes that hold frames ([StepTrackOutput], [StepResult]) compare by value too. No
     * metadata and empty metadata are different frames, as they are on the wire.
     */
    override fun equals(other: Any?): Boolean =
        other is StepFrame &&
            encoding == other.encoding &&
            dtype == other.dtype &&
            dimensions.contentEquals(other.dimensions) &&
            bytes.contentEquals(other.bytes) &&
            tag.contentEquals(other.tag) &&
            captureTimeUs == other.captureTimeUs

    override fun hashCode(): Int {
        var hash = encoding.hashCode()
        hash = 31 * hash + dtype.hashCode()
        hash = 31 * hash + dimensions.contentHashCode()
        hash = 31 * hash + bytes.contentHashCode()
        hash = 31 * hash + (tag?.contentHashCode() ?: 0)
        return 31 * hash + (captureTimeUs?.hashCode() ?: 0)
    }

    override fun toString(): String = "StepFrame($dtype ${dimensions.contentToString()}, ${bytes.size} bytes)"
}
