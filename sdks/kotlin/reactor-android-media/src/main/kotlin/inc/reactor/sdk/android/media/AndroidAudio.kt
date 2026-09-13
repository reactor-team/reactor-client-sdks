package inc.reactor.sdk.android.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import inc.reactor.sdk.AudioFrame
import inc.reactor.sdk.Track
import inc.reactor.sdk.TrackDirection
import inc.reactor.sdk.TrackKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

data class AndroidAudioFormat(
    val sampleRate: Int = 48000,
    val channels: Int = 1,
) {
    init {
        require(sampleRate in setOf(8000, 16000, 24000, 32000, 44100, 48000) && channels in 1..2)
    }

    internal val blockSamples get() = sampleRate / 100 * channels

    internal fun mask(input: Boolean): Int =
        if (input) {
            if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        } else {
            if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        }

    internal fun native(input: Boolean): AudioFormat =
        AudioFormat
            .Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(mask(input))
            .build()
}

internal interface AndroidCapture : AutoCloseable {
    fun read(
        samples: ShortArray,
        offset: Int,
        count: Int,
    ): Int
}

internal interface AndroidPlayback : AutoCloseable {
    fun write(
        samples: ShortArray,
        offset: Int,
        count: Int,
    ): Int
}

internal interface AndroidAudioBackend {
    fun capture(format: AndroidAudioFormat): AndroidCapture

    fun playback(format: AndroidAudioFormat): AndroidPlayback
}

internal class PlatformAudio(
    context: Context,
) : AndroidAudioBackend {
    private val application = context.applicationContext

    private fun requirePermission() {
        if (application.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("The application must grant RECORD_AUDIO before starting the microphone")
        }
    }

    @android.annotation.SuppressLint("MissingPermission") // Explicit permission checks before open and every read.
    override fun capture(format: AndroidAudioFormat): AndroidCapture {
        requirePermission()
        val minimum = AudioRecord.getMinBufferSize(format.sampleRate, format.mask(true), AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "AudioRecord does not support this PCM format" }
        @Suppress("MissingPermission") // Checked immediately above; revocation is checked before every read.
        val record =
            AudioRecord
                .Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(format.native(true))
                .setBufferSizeInBytes(maxOf(minimum, format.blockSamples * 8))
                .build()
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone is unavailable" }
            record.startRecording()
            check(
                record.recordingState == AudioRecord.RECORDSTATE_RECORDING,
            ) { "Microphone could not start; check permissions and privacy controls" }
        } catch (failure: Throwable) {
            record.release()
            throw failure
        }
        return object : AndroidCapture {
            override fun read(
                samples: ShortArray,
                offset: Int,
                count: Int,
            ): Int {
                requirePermission()
                if (Build.VERSION.SDK_INT >= 29 && record.activeRecordingConfiguration?.isClientSilenced == true) {
                    throw SecurityException("Android silenced microphone capture; check foreground state and microphone privacy controls")
                }
                return record.read(samples, offset, count, AudioRecord.READ_NON_BLOCKING)
            }

            override fun close() {
                try {
                    record.stop()
                } finally {
                    record.release()
                }
            }
        }
    }

    override fun playback(format: AndroidAudioFormat): AndroidPlayback {
        val minimum = AudioTrack.getMinBufferSize(format.sampleRate, format.mask(false), AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "AudioTrack does not support this PCM format" }
        val track =
            AudioTrack
                .Builder()
                .setAudioFormat(format.native(false))
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(
                            AudioAttributes.USAGE_VOICE_COMMUNICATION,
                        ).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                ).setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minimum, format.blockSamples * 8))
                .build()
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED) { "Speaker is unavailable" }
            track.play()
        } catch (
            failure: Throwable,
        ) {
            track.release()
            throw failure
        }
        return object : AndroidPlayback {
            override fun write(
                samples: ShortArray,
                offset: Int,
                count: Int,
            ) = track.write(samples, offset, count, AudioTrack.WRITE_NON_BLOCKING)

            override fun close() {
                try {
                    track.pause()
                    track.flush()
                } finally {
                    track.release()
                }
            }
        }
    }
}

/** Explicit microphone capture. Permission belongs to the application; construction opens nothing. */
class AndroidMicrophone internal constructor(
    private val deviceFormat: AndroidAudioFormat,
    outputFormat: AndroidAudioFormat,
    private val backend: AndroidAudioBackend,
    onFailure: (Throwable) -> Unit,
) : AndroidMediaResource {
    constructor(
        context: Context,
        deviceFormat: AndroidAudioFormat = AndroidAudioFormat(),
        outputFormat: AndroidAudioFormat = deviceFormat,
        onFailure: (Throwable) -> Unit = { System.err.println("Reactor Android microphone: $it") },
    ) :
        this(deviceFormat, outputFormat, PlatformAudio(context), onFailure)

    private val worker = MediaWorker(onFailure)
    private val converter = PcmConverter(outputFormat.sampleRate, outputFormat.channels)
    val running get() = worker.running

    suspend fun start(track: Track) {
        require(
            track.kind == TrackKind.AUDIO && track.direction == TrackDirection.SENDONLY,
        ) { "Microphone requires a sendonly audio track" }
        check(track.published) { "Publish the track and await completion before starting the microphone" }
        startCapture { track.pushFrame(it) }
    }

    internal suspend fun startCapture(deliver: (AudioFrame) -> Unit) {
        worker.start({ backend.capture(deviceFormat) }) { device ->
            val samples = ShortArray(deviceFormat.blockSamples)
            while (true) {
                var offset = 0
                while (offset < samples.size) {
                    currentCoroutineContext().ensureActive()
                    val count = device.read(samples, offset, samples.size - offset)
                    currentCoroutineContext().ensureActive()
                    check(count >= 0 && count <= samples.size - offset && count % deviceFormat.channels == 0) {
                        "AudioRecord failed ($count); recreate the microphone"
                    }
                    if (count == 0) delay(2) else offset += count
                }
                val frame = converter.convert(AudioFrame(samples, deviceFormat.sampleRate, deviceFormat.channels))
                currentCoroutineContext().ensureActive()
                if (frame.samples.isNotEmpty()) deliver(frame)
            }
        }
    }

    override fun close() = worker.close()

    override suspend fun awaitClosed() = worker.awaitClosed()
}

/** AudioTrack playback with four pending blocks, dropping the oldest on overflow. */
class AndroidSpeaker internal constructor(
    private val deviceFormat: AndroidAudioFormat,
    private val backend: AndroidAudioBackend,
    private val onFailure: (Throwable) -> Unit,
) : AndroidMediaResource {
    constructor(
        context: Context,
        deviceFormat: AndroidAudioFormat = AndroidAudioFormat(),
        onFailure: (Throwable) -> Unit = { System.err.println("Reactor Android speaker: $it") },
    ) : this(deviceFormat, PlatformAudio(context), onFailure)

    private val worker = MediaWorker(onFailure)
    private val converter = PcmConverter(deviceFormat.sampleRate, deviceFormat.channels)
    private val queue = Channel<AudioFrame>(4, BufferOverflow.DROP_OLDEST)
    private val subscription = AtomicReference<AutoCloseable?>()
    val running get() = worker.running

    suspend fun start(track: Track) {
        require(track.kind == TrackKind.AUDIO && track.direction == TrackDirection.RECVONLY) { "Speaker requires a recvonly audio track" }
        try {
            startPlayback()
            withContext(Dispatchers.IO) {
                subscription.set(
                    track.onFrame { frame ->
                        if (running) {
                            try {
                                submit(frame as AudioFrame)
                            } catch (failure: Throwable) {
                                close()
                                runCatching { onFailure(failure) }
                            }
                        }
                    },
                )
                if (!running) subscription.getAndSet(null)?.close()
            }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    internal suspend fun startPlayback() {
        worker.start({ backend.playback(deviceFormat) }) { device ->
            try {
                for (frame in queue) {
                    var offset = 0
                    while (offset < frame.samples.size) {
                        currentCoroutineContext().ensureActive()
                        val count = device.write(frame.samples, offset, frame.samples.size - offset)
                        currentCoroutineContext().ensureActive()
                        check(count >= 0 && count <= frame.samples.size - offset && count % deviceFormat.channels == 0) {
                            "AudioTrack failed ($count); recreate the speaker"
                        }
                        if (count == 0) delay(2) else offset += count
                    }
                }
            } finally {
                subscription.getAndSet(null)?.close()
                queue.cancel()
            }
        }
    }

    @Synchronized fun submit(frame: AudioFrame) {
        check(running) { "Start the speaker before submitting PCM" }
        require(frame.samplesPerChannel <= frame.sampleRate / 50) { "Submit at most 20 ms per block" }
        val converted = converter.convert(frame)
        if (converted.samples.isNotEmpty()) queue.trySend(converted)
    }

    override fun close() {
        worker.close()
        queue.cancel()
    }

    override suspend fun awaitClosed() = worker.awaitClosed()
}
