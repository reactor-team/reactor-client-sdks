package inc.reactor.sdk.android.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
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
             * 48000, the rate the platform's own voice paths run at.
             *
             * A capture at any other rate is held to it by whichever backend it was opened on, and
             * a route change does not always carry it across: a Galaxy S9 on Android 10, opened at
             * 44100 on USB-C earphones, kept recording garbled audio after unplugging and plugging
             * them back in until the microphone was reopened, because the communication-mode path
             * it passed through refuses 44100. At 48000 every path agrees and nothing has to be
             * renegotiated. A device that refuses it says so at [start], and 44100 is the rate to
             * pass instead.
             */
            public const val DEFAULT_SAMPLE_RATE: Int = 48_000
        }

        /**
         * Open the device and start delivering buffers to [onData].
         *
         * [onData] runs on this class's own reader thread, not the caller's.
         */
        override fun start(onData: (ShortArray) -> Unit) {
            start(onData) { }
        }

        /**
         * Open the device and start delivering buffers to [onData]; [onFailure] is told if a read
         * fails and capture ends on its own.
         */
        @SuppressLint("MissingPermission") // Checked below, with a message worth reading.
        override fun start(
            onData: (ShortArray) -> Unit,
            onFailure: (Throwable) -> Unit,
        ) {
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

            try {
                opened.startRecording()
            } catch (failed: Throwable) {
                // Nothing is committed yet, so a failed start leaves this backend as it found it:
                // startable again, and holding no native recorder.
                opened.release()
                throw failed
            }
            record = opened
            capturing.set(true)

            reader =
                Thread({ pump(opened, onData, onFailure) }, "reactor-mic").apply {
                    // Audio priority: a capture thread that misses its slot drops buffers, and the
                    // default priority competes with everything the app is doing.
                    priority = Thread.MAX_PRIORITY
                    start()
                }
        }

        private fun pump(
            opened: AudioRecord,
            onData: (ShortArray) -> Unit,
            onFailure: (Throwable) -> Unit,
        ) {
            val buffer = ShortArray(framesPerRead * channels)
            while (capturing.get()) {
                val read = opened.read(buffer, 0, buffer.size)
                if (read < 0) {
                    // An error code: ERROR_DEAD_OBJECT after the audio server restarts, or
                    // ERROR_INVALID_OPERATION from a device torn down under us. Not thrown — this
                    // thread has nobody to throw to — but reported, unless stop() is what caused
                    // it: the compareAndSet loses to a stop() that already cleared the flag.
                    if (capturing.compareAndSet(true, false)) {
                        onFailure(IllegalStateException("AudioRecord.read() failed with code $read; capture has stopped"))
                    }
                    return
                }
                if (read == 0) continue
                // Checked again after the read: `stop()` may have landed while this call was
                // blocked, and delivering then would push into a track already torn down.
                if (!capturing.get()) return
                try {
                    onData(if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read))
                } catch (thrown: Throwable) {
                    // The caller's callback threw, and this thread is the only one reading: left
                    // to escape, it ends the reader while everything above keeps reporting a live
                    // capture. Treated as the capture ending — reported once, the same way as a
                    // read error, and still released by stop().
                    if (capturing.compareAndSet(true, false)) {
                        onFailure(IllegalStateException("The capture callback threw; capture has stopped", thrown))
                    }
                    return
                }
            }
        }

        /** Stop, **waiting for the reader thread to leave [onData]** — see the class note. */
        override fun stop() {
            val open = record ?: return
            val pumping = reader
            capturing.set(false)
            reader = null
            record = null
            // stop() before the join, not after: it is what returns a read() blocked waiting for
            // data, so the join below waits for one buffer at most rather than for a timeout.
            runCatching { open.stop() }
            // Then join, and release only once the reader is out. Releasing the AudioRecord while
            // the reader is inside read() is a use-after-free in the platform's native layer. The
            // reader itself calling stop() — from onData — is not waited for: it would be
            // waiting for itself, and it is not inside read() while it runs this.
            if (pumping != null && pumping !== Thread.currentThread()) {
                pumping.join(TEARDOWN_WAIT_MS)
                if (pumping.isAlive) {
                    // Still inside read() despite stop(). Releasing now would be the native crash
                    // the join exists to prevent; leaking one AudioRecord is the lesser failure.
                    Log.w(
                        TAG,
                        "The microphone reader did not exit within ${TEARDOWN_WAIT_MS}ms of " +
                            "stop(); leaving its AudioRecord unreleased rather than freeing it " +
                            "under a read.",
                    )
                    return
                }
            }
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

private const val TAG = "ReactorMicrophone"
