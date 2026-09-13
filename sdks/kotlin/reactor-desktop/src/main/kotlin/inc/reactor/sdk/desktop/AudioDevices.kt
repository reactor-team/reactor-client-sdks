package inc.reactor.sdk.desktop

import inc.reactor.sdk.AudioFrame
import inc.reactor.sdk.Track
import inc.reactor.sdk.TrackDirection
import inc.reactor.sdk.TrackKind
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/** Signed PCM16, interleaved, little endian at the Java Sound boundary. No implicit resampling. */
data class DesktopAudioFormat(
    val sampleRate: Int = 48000,
    val channels: Int = 1,
) {
    init {
        require(sampleRate in setOf(8000, 16000, 24000, 32000, 44100, 48000) && channels in 1..2) {
            "Use a core-supported sample rate and mono/stereo PCM"
        }
    }

    internal val bytesPerFrame get() = channels * 2
    internal val blockBytes get() = sampleRate / 100 * bytesPerFrame

    internal fun javaFormat() = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)

    internal fun requireMatch(format: AudioFormat) {
        require(
            format.encoding == AudioFormat.Encoding.PCM_SIGNED &&
                format.sampleSizeInBits == 16 &&
                format.sampleRate == sampleRate.toFloat() &&
                format.channels == channels &&
                !format.isBigEndian &&
                format.frameSize == bytesPerFrame,
        ) {
            "Device did not open the requested PCM16 format; choose a supported device/rate"
        }
    }
}

internal interface CaptureDevice : AutoCloseable {
    fun read(
        bytes: ByteArray,
        offset: Int,
        size: Int,
    ): Int
}

internal interface PlaybackDevice : AutoCloseable {
    fun write(
        bytes: ByteArray,
        offset: Int,
        size: Int,
    ): Int
}

internal interface AudioBackend {
    fun capture(format: DesktopAudioFormat): CaptureDevice

    fun playback(format: DesktopAudioFormat): PlaybackDevice
}

internal object JavaSoundBackend : AudioBackend {
    override fun capture(format: DesktopAudioFormat): CaptureDevice {
        val line = AudioSystem.getTargetDataLine(format.javaFormat())
        try {
            line.open(format.javaFormat(), format.blockBytes * 4)
            format.requireMatch(line.format)
            line.start()
        } catch (failure: Throwable) {
            line.close()
            throw failure
        }
        return object : CaptureDevice {
            override fun read(
                bytes: ByteArray,
                offset: Int,
                size: Int,
            ) = line.read(bytes, offset, size)

            override fun close() {
                try {
                    line.stop()
                    line.flush()
                } finally {
                    line.close()
                }
            }
        }
    }

    override fun playback(format: DesktopAudioFormat): PlaybackDevice {
        val line = AudioSystem.getSourceDataLine(format.javaFormat())
        try {
            line.open(format.javaFormat(), format.blockBytes * 4)
            format.requireMatch(line.format)
            line.start()
        } catch (failure: Throwable) {
            line.close()
            throw failure
        }
        return object : PlaybackDevice {
            override fun write(
                bytes: ByteArray,
                offset: Int,
                size: Int,
            ) = line.write(bytes, offset, size)

            override fun close() {
                try {
                    line.stop()
                    line.flush()
                } finally {
                    line.close()
                }
            }
        }
    }
}

/** Single-use worker. Close invalidates capture before closing the device, without holding a callback lock. */
internal class AudioWorker(
    private val onFailure: (Throwable) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private var started = false
    private val active = AtomicBoolean(false)
    private val device = AtomicReference<AutoCloseable?>()
    private var thread: Thread? = null
    val running get() = active.get()

    fun <T : AutoCloseable> start(
        name: String,
        open: () -> T,
        run: (T) -> Unit,
    ) {
        synchronized(lock) {
            check(!started) { "Device helper is single-use; create a new helper after stopping" }
            started = true
            val opened = open()
            device.set(opened)
            active.set(true)
            val worker =
                Thread({
                    try {
                        run(opened)
                    } catch (failure: Throwable) {
                        if (running) runCatching { onFailure(failure) }
                    } finally {
                        close()
                    }
                }, name).also { it.isDaemon = true }
            thread = worker
            try {
                worker.start()
            } catch (failure: Throwable) {
                close()
                throw failure
            }
        }
    }

    override fun close() {
        val (owned, worker) =
            synchronized(lock) {
                started = true
                active.set(false)
                device.getAndSet(null) to thread
            }
        try {
            owned?.close()
        } catch (failure: Throwable) {
            runCatching { onFailure(failure) }
        }
        if (worker != null && worker !== Thread.currentThread()) {
            worker.join(2000)
            if (worker.isAlive) {
                runCatching {
                    onFailure(
                        IllegalStateException("Device worker is still stopping; a handler or device may be blocked"),
                    )
                }
            }
        }
    }
}

/** Opt-in microphone. Publish the track first. Constructing this helper opens nothing. */
class DesktopMicrophone internal constructor(
    val format: DesktopAudioFormat,
    private val backend: AudioBackend,
    onFailure: (Throwable) -> Unit,
) : AutoCloseable {
    constructor(
        format: DesktopAudioFormat = DesktopAudioFormat(),
        onFailure: (Throwable) -> Unit = { System.err.println("Reactor microphone: $it") },
    ) :
        this(format, JavaSoundBackend, onFailure)

    private val worker = AudioWorker(onFailure)
    val running get() = worker.running

    fun start(track: Track) {
        require(
            track.kind == TrackKind.AUDIO && track.direction == TrackDirection.SENDONLY,
        ) { "Microphone requires a sendonly audio track" }
        check(track.published) { "Publish the audio track and await completion before starting the microphone" }
        startCapture { track.pushFrame(it) }
    }

    internal fun startCapture(deliver: (AudioFrame) -> Unit) {
        worker.start("reactor-microphone", { backend.capture(format) }) { device ->
            val bytes = ByteArray(format.blockBytes)
            while (worker.running) {
                var offset = 0
                while (offset < bytes.size && worker.running) {
                    val count = device.read(bytes, offset, bytes.size - offset)
                    if (!worker.running) break
                    check(count > 0 && count <= bytes.size - offset && count % format.bytesPerFrame == 0) {
                        "Microphone stopped or returned an incomplete PCM frame"
                    }
                    offset += count
                }
                if (worker.running && offset == bytes.size) deliver(AudioFrame(decodePcm(bytes), format.sampleRate, format.channels))
            }
        }
    }

    override fun close() = worker.close()
}

/** Opt-in speaker, bounded to four queued blocks of at most 20 ms plus the device buffer.
 * On overflow the oldest queued block is dropped. No resampling or unbounded event queue.
 */
class DesktopSpeaker internal constructor(
    val format: DesktopAudioFormat,
    private val backend: AudioBackend,
    onFailure: (Throwable) -> Unit,
) : AutoCloseable {
    constructor(
        format: DesktopAudioFormat = DesktopAudioFormat(),
        onFailure: (Throwable) -> Unit = { System.err.println("Reactor speaker: $it") },
    ) :
        this(format, JavaSoundBackend, onFailure)

    private val worker = AudioWorker(onFailure)
    private val queue = ArrayBlockingQueue<ByteArray>(4)
    private val subscription = AtomicReference<AutoCloseable?>()
    val running get() = worker.running
    internal val queuedBlocks get() = queue.size

    fun start(track: Track) {
        require(track.kind == TrackKind.AUDIO && track.direction == TrackDirection.RECVONLY) { "Speaker requires a recvonly audio track" }
        startPlayback()
        try {
            subscription.set(track.onFrame { frame -> if (running) submit(frame as AudioFrame) })
            if (!running) subscription.getAndSet(null)?.close()
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    internal fun startPlayback() {
        worker.start("reactor-speaker", { backend.playback(format) }) { device ->
            try {
                while (worker.running) {
                    val bytes = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    var offset = 0
                    while (offset < bytes.size && worker.running) {
                        val count = device.write(bytes, offset, bytes.size - offset)
                        if (!worker.running) break
                        check(count > 0 && count <= bytes.size - offset && count % format.bytesPerFrame == 0) {
                            "Speaker stopped or returned an incomplete PCM frame"
                        }
                        offset += count
                    }
                }
            } finally {
                subscription.getAndSet(null)?.close()
                queue.clear()
            }
        }
    }

    fun submit(frame: AudioFrame) {
        check(running) { "Start the speaker before submitting audio" }
        require(frame.sampleRate == format.sampleRate && frame.channels == format.channels) {
            "Speaker PCM format mismatch; configure the helper for the track format"
        }
        require(frame.samples.isNotEmpty() && frame.samplesPerChannel <= format.sampleRate / 50) { "Submit at most 20 ms of PCM per block" }
        val bytes = encodePcm(frame.samples)
        if (!queue.offer(bytes)) {
            queue.poll()
            queue.offer(bytes)
        }
        if (!running) queue.clear()
    }

    override fun close() {
        // Stop delivery before removing the native subscription; never hold a device lock while waiting for callbacks.
        worker.close()
        subscription.getAndSet(null)?.close()
        queue.clear()
    }
}

internal fun decodePcm(bytes: ByteArray): ShortArray {
    require(bytes.size % 2 == 0)
    return ShortArray(bytes.size / 2) { i -> ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort() }
}

internal fun encodePcm(samples: ShortArray): ByteArray =
    ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { i, sample ->
            bytes[i * 2] = sample.toByte()
            bytes[i * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
    }
