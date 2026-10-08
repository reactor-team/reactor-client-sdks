package inc.reactor.sdk.android.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The microphone, for real.
 *
 * A [CaptureDevice] backed by `AudioRecord`, which is what [Microphone] drives. Hand one to a
 * Microphone and the lifecycle, the resampling and the stop-while-capturing race are all already
 * handled above it — this class only has to open the hardware, pump a thread, and close it.
 *
 * **`stop()` waits for the reader thread to leave the callback**, which is the contract
 * [CaptureDevice] documents and the reason Microphone guards its state with an atomic rather than
 * a mutex. Getting that wrong here is what the deadlock test upstairs exists to catch.
 *
 * ### Permission
 *
 * The SDK declares none. `RECORD_AUDIO` is the app's to request, because only the app knows when
 * to ask — and [start] checks for it so a missing grant is a message rather than a
 * `SecurityException` from inside the constructor.
 *
 * ### Source
 *
 * `VOICE_COMMUNICATION` rather than `MIC`: it is the source that gets the platform's echo
 * cancellation, noise suppression and gain control, and a real-time session piping raw `MIC` into
 * a model that is also playing audio back produces feedback on a speakerphone.
 */
public class AudioRecordCapture
    @JvmOverloads
    constructor(
        private val context: Context,
        public val sampleRate: Int = DEFAULT_SAMPLE_RATE,
        public val channels: Int = 1,
        private val audioSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
    ) : CaptureDevice {
        init {
            AudioFormats.requireSupported(sampleRate, channels)
        }

        private var record: AudioRecord? = null
        private var reader: Thread? = null
        private val capturing = AtomicBoolean(false)

        /** Frames per callback: 10ms, the unit WebRTC's pipeline is built around. */
        private val framesPerRead = sampleRate / 100

        public companion object {
            /**
             * 44100, not 48000.
             *
             * It is the one rate every Android device supports for capture. Microphone resamples
             * to whatever the track wants, so paying a conversion is better than opening at a rate
             * a particular handset silently refuses.
             */
            public const val DEFAULT_SAMPLE_RATE: Int = 44_100
        }

        /**
         * Open the device and start delivering buffers to [onData].
         *
         * [onData] runs on this class's own reader thread, not the caller's.
         */
        @SuppressLint("MissingPermission") // Checked below, with a message worth reading.
        override fun start(onData: (ShortArray) -> Unit) {
            check(record == null) { "This AudioRecordCapture is already started" }
            val granted =
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            check(granted) {
                AudioFormats.captureFailureMessage(sampleRate, channels, hasPermission = false)
            }

            val channelMask =
                if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
            val minimum = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            val bufferBytes = AudioFormats.bufferSizeBytes(minimum, sampleRate, channels)

            val opened =
                AudioRecord(audioSource, sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            if (opened.state != AudioRecord.STATE_INITIALIZED) {
                // Released before throwing: an uninitialised AudioRecord still holds a native
                // object, and leaking one per failed attempt is how an app ends up unable to open
                // the microphone at all.
                opened.release()
                error(AudioFormats.captureFailureMessage(sampleRate, channels, hasPermission = true))
            }

            record = opened
            capturing.set(true)
            opened.startRecording()

            reader =
                Thread({ pump(opened, onData) }, "reactor-mic").apply {
                    // Audio priority: a capture thread that misses its slot drops buffers, and the
                    // default priority competes with everything the app is doing.
                    priority = Thread.MAX_PRIORITY
                    start()
                }
        }

        private fun pump(
            opened: AudioRecord,
            onData: (ShortArray) -> Unit,
        ) {
            val buffer = ShortArray(framesPerRead * channels)
            while (capturing.get()) {
                val read = opened.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    // Negative codes are errors, and ERROR_INVALID_OPERATION is what a device
                    // being torn down under us returns. Neither is worth throwing from a thread
                    // nobody is waiting on; stopping is the only useful response.
                    if (read < 0) capturing.set(false)
                    continue
                }
                // Checked again after the read: `stop()` may have landed while this call was
                // blocked, and delivering then would push into a track already torn down.
                if (!capturing.get()) return
                onData(if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read))
            }
        }

        /** Stop, **waiting for the reader thread to leave [onData]** — see the class note. */
        override fun stop() {
            val open = record ?: return
            capturing.set(false)
            // Joined before release, not after: releasing the AudioRecord while the reader is
            // inside read() is a use-after-free in the platform's own native layer.
            reader?.join(TEARDOWN_WAIT_MS)
            reader = null
            record = null
            runCatching { open.stop() }
            open.release()
        }
    }

/**
 * How long [AudioRecordCapture.stop] waits for its reader thread.
 *
 * Generous against a blocked `read()` — one buffer is 10ms — and bounded, because a thread that
 * will not come back must not hold the caller's teardown open for ever. The buffer it was reading
 * is dropped either way.
 */
private const val TEARDOWN_WAIT_MS = 2_000L
