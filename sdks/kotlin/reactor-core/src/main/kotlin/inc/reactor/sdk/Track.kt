package inc.reactor.sdk

import java.lang.ref.WeakReference

enum class TrackKind { VIDEO, AUDIO }

enum class TrackDirection { SENDONLY, RECVONLY }

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
        require(pixels.size.toLong() == width.toLong() * height * 4) { "BGRA pixels must contain width * height * 4 bytes" }
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

    suspend fun pause() = client().trackOperation(this, 3)

    suspend fun resume() = client().trackOperation(this, 4)
}
