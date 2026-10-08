package inc.reactor.sdk.android.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Playout, for real.
 *
 * A [RenderDevice] backed by `AudioTrack`, which is what [Speaker] drives. Needs no permission:
 * playing audio is not a protected operation, which is why this class is shorter than its capture
 * sibling and has no runtime check.
 *
 * **`write` blocks when the buffer is full, and that is the backpressure.** An `AudioTrack` in
 * streaming mode accepts samples at the rate the hardware consumes them; feeding it from the
 * FFI's delivery thread means a slow sink slows the stream rather than growing a queue nobody
 * bounded. [Speaker] holds its mutex across this call, which is safe precisely because playout
 * runs on the platform's own thread and never calls back into us.
 *
 * ### Usage
 *
 * `VOICE_COMMUNICATION`, matching [AudioRecordCapture]'s source. The pair is what routes a session
 * to the earpiece and engages the platform's echo canceller; mixing a `MEDIA` usage with a
 * `VOICE_COMMUNICATION` capture gets neither.
 */
public class AudioTrackRender
    @JvmOverloads
    constructor(
        public val sampleRate: Int = 48_000,
        public val channels: Int = 1,
        private val usage: Int = AudioAttributes.USAGE_VOICE_COMMUNICATION,
    ) : RenderDevice {
        init {
            AudioFormats.requireSupported(sampleRate, channels)
        }

        private var track: AudioTrack? = null

        override fun start() {
            check(track == null) { "This AudioTrackRender is already started" }

            val channelMask =
                if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minimum = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            val bufferBytes = AudioFormats.bufferSizeBytes(minimum, sampleRate, channels)

            val opened =
                AudioTrack
                    .Builder()
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(usage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    ).setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelMask)
                            .build(),
                    ).setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

            if (opened.state != AudioTrack.STATE_INITIALIZED) {
                // Released rather than left to a finalizer, for the same reason as capture: an
                // uninitialised AudioTrack still owns a native object.
                opened.release()
                error(
                    "The speaker could not be opened at ${sampleRate}Hz with $channels " +
                        "channel(s). Playout needs no permission, so this is the device refusing " +
                        "the format.",
                )
            }

            track = opened
            opened.play()
        }

        override fun write(samples: ShortArray) {
            // WRITE_BLOCKING, not the non-blocking mode: a short write would silently drop the
            // tail of a buffer, which is a click the user hears and nothing reports. Blocking is
            // the backpressure — see the class note.
            track?.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        }

        override fun stop() {
            val open = track ?: return
            track = null
            // pause() before flush(): stop() would play out whatever is buffered first, which on
            // teardown is a tail of audio arriving after the caller believes it has stopped.
            runCatching {
                open.pause()
                open.flush()
                open.stop()
            }
            open.release()
        }
    }
