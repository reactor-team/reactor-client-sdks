package inc.reactor.sdk

import java.lang.ref.WeakReference

enum class TrackKind { VIDEO, AUDIO }

enum class TrackDirection { SENDONLY, RECVONLY }

enum class PublicationState { UNPUBLISHED, PUBLISHING, PUBLISHED }

/** The engine clock, in microseconds. Read once for all video tracks in one capture. */
fun timeMicros(): Long =
    inc.reactor.sdk.internal.NativeAbi
        .timeMicros()

sealed interface MediaFrame

/** Owned BGRA pixels. Metadata timestamps use the sender's clock, never local/Unix time. */
data class VideoFrame(
    val pixels: ByteArray,
    val width: Int,
    val height: Int,
    val frameId: ULong = 0u,
    val timestampMicros: ULong = 0u,
    val userData: ByteArray? = null,
) : MediaFrame {
    init {
        require(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE / 4) { "BGRA dimensions exceed the JVM buffer limit" }
        require(pixels.size.toLong() == width.toLong() * height * 4) {
            "BGRA length ${pixels.size} must equal ${width.toLong() * height * 4} bytes for ${width}x$height"
        }
    }
}

/** Owned interleaved signed PCM16 samples, preserving the native format. */
data class AudioFrame(
    val samples: ShortArray,
    val sampleRate: Int,
    val channels: Int,
) : MediaFrame {
    init {
        require(
            sampleRate > 0 && channels > 0 && samples.size % channels == 0,
        ) { "PCM requires a positive format and complete interleaved frames" }
    }

    val samplesPerChannel: Int get() = samples.size / channels
}

class TrackList internal constructor(
    private val values: List<Track>,
) : AbstractList<Track>() {
    override val size: Int get() = values.size

    override fun get(index: Int): Track = values[index]

    fun withKind(kind: TrackKind): TrackList = TrackList(filter { it.kind == kind })

    fun withDirection(direction: TrackDirection): TrackList = TrackList(filter { it.direction == direction })

    fun one(): Track {
        require(size == 1) { "Expected one track, found $size: ${joinToString { it.name }}. Narrow the filters." }
        return values.single()
    }
}

/** A named slot declared by the model, with a weak reference to its client. */
class Track internal constructor(
    client: Reactor,
    val name: String,
    val kind: TrackKind,
    val direction: TrackDirection,
) {
    private val owner = WeakReference(client)

    internal fun client(): Reactor = owner.get() ?: throw InvalidStateError(ErrorDetails("INVALID_STATE", "Track's client is gone"))

    val mid: String? get() = client().trackMid(this)
    val paused: Boolean get() = client().trackPaused(this)

    /** Runs inline on the native delivery thread. Slow handlers apply native backpressure. */
    fun onFrame(handler: (MediaFrame) -> Unit): AutoCloseable {
        require(direction == TrackDirection.RECVONLY) { "Track '$name' is sendonly; only recvonly tracks receive frames" }
        return client().receive(this, handler)
    }

    val publicationState: PublicationState get() = client().publicationState(this)
    val published: Boolean get() = publicationState == PublicationState.PUBLISHED

    suspend fun publish(): Track {
        requireSend("publish")
        client().publish(this)
        return this
    }

    fun unpublish() {
        requireSend("unpublish")
        client().unpublish(this)
    }

    /** BGRA with optional metadata; captureTimeMicros must come from timeMicros(). */
    fun pushFrame(
        frame: VideoFrame,
        captureTimeMicros: Long? = null,
    ) {
        requireSend("pushFrame")
        require(kind == TrackKind.VIDEO) { "Track '$name' is audio; push an AudioFrame" }
        require(captureTimeMicros == null || captureTimeMicros >= 0) { "Capture time must be nonnegative engine microseconds" }
        client().push(this, frame, captureTimeMicros)
    }

    /** Pace interleaved PCM at its capture rate. Audio has no metadata or capture-time argument. */
    fun pushFrame(frame: AudioFrame) {
        requireSend("pushFrame")
        require(kind == TrackKind.AUDIO) { "Track '$name' is video; push a VideoFrame" }
        require(frame.sampleRate in setOf(8000, 16000, 24000, 32000, 44100, 48000) && frame.channels in 1..2) {
            "Audio requires 8000/16000/24000/32000/44100/48000 Hz and one or two channels"
        }
        client().push(this, frame)
    }

    suspend fun setBitrate(
        minBps: Int = -1,
        maxBps: Int = -1,
    ) {
        requireSend("setBitrate")
        client().trackBitrate(this, minBps, maxBps)
    }

    private fun requireSend(operation: String) {
        require(direction == TrackDirection.SENDONLY) { "Track '$name' is recvonly; $operation requires a sendonly track" }
    }

    suspend fun pause() = client().trackOperation(this, 3)

    suspend fun resume() = client().trackOperation(this, 4)
}
