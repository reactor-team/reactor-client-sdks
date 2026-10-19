package inc.reactor.sdk.android.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log

/**
 * Playout, for real.
 *
 * A [RenderDevice] backed by `AudioTrack`, which is what [Speaker] drives. Needs no runtime
 * permission: playing audio is not a protected operation. Routing it needs
 * `MODIFY_AUDIO_SETTINGS`, an install-time permission this module's manifest declares, so there is
 * nothing for the app to ask for.
 *
 * **`write` blocks when the buffer is full, and that is the backpressure.** An `AudioTrack` in
 * streaming mode accepts samples at the rate the hardware consumes them; feeding it from the
 * FFI's delivery thread means a slow sink slows the stream rather than growing a queue nobody
 * bounded. [Speaker] holds its mutex across this call, which is safe precisely because playout
 * runs on the platform's own thread and never calls back into us.
 *
 * ### Usage
 *
 * `VOICE_COMMUNICATION`, matching [AudioRecordCapture]'s source. The pair is what engages the
 * platform's echo canceller; mixing a `MEDIA` usage with a `VOICE_COMMUNICATION` capture loses it.
 *
 * ### Output
 *
 * **The loudspeaker by default**, as the Swift SDK's `Speaker` and `Microphone` do with
 * `.defaultToSpeaker`. Left alone, a `VOICE_COMMUNICATION` stream plays through the earpiece — the
 * call speaker at the top of the phone — and a session held at arm's length is silent. While
 * playing, this class puts the platform in communication mode and selects the built-in speaker,
 * and restores both on [stop]. A connected headset or Bluetooth device is left to win, as on iOS.
 * [Output.EARPIECE] keeps the earpiece, for an app that is a phone call.
 *
 * That routing is process-wide audio state, as `AVAudioSession` is on iOS, so it is shared: the
 * first renderer to start records the platform's state and routes, and only the last to stop
 * restores it.
 */
public class AudioTrackRender
    @JvmOverloads
    constructor(
        private val context: Context,
        public val sampleRate: Int = 48_000,
        public val channels: Int = 1,
        public val output: Output = Output.SPEAKER,
    ) : RenderDevice {
        /** Where playout goes when no headset is connected. */
        public enum class Output { SPEAKER, EARPIECE }

        init {
            AudioFormats.requireSupported(sampleRate, channels)
        }

        private var track: AudioTrack? = null

        /** Whether this renderer holds a share of the process-wide speaker route. */
        private var holdsRoute = false

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
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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

            var routed = false
            try {
                if (output == Output.SPEAKER) {
                    SpeakerRoute.acquire(context)
                    routed = true
                }
                opened.play()
            } catch (failed: Throwable) {
                // Rolled back before anything is committed: a renderer that failed to start must
                // be startable again, and must not keep its share of the process-wide route — the
                // Speaker above never became running, so it will never call stop() to return it.
                if (routed) SpeakerRoute.release()
                opened.release()
                throw failed
            }
            track = opened
            holdsRoute = routed
        }

        override fun write(samples: ShortArray) {
            // WRITE_BLOCKING, not the non-blocking mode: a short write would silently drop the
            // tail of a buffer, which is a click the user hears and nothing reports. Blocking is
            // the backpressure — see the class note.
            val open = track ?: return
            val written = open.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            // Negative is an error code, not a count — ERROR_DEAD_OBJECT once the audio server
            // has restarted, after which every later write fails the same way. Thrown so that
            // [Speaker] stops claiming to play; see RenderDevice.write.
            check(written >= 0) { "The speaker stopped accepting audio: AudioTrack.write returned $written" }
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
            // After the track is gone, so the route never changes under audio still playing.
            if (holdsRoute) {
                holdsRoute = false
                SpeakerRoute.release()
            }
        }

        /**
         * The speaker route, counted across every renderer in the process.
         *
         * Per instance, two renderers corrupted it: the first recorded NORMAL and routed, the
         * second recorded the first one's communication mode, and stopping them restored NORMAL
         * under the second and then left communication mode on for good. One snapshot, taken by
         * the first acquire and undone by the last release, is the only order that holds.
         */
        private object SpeakerRoute {
            private val lock = Any()
            private var holders = 0
            private var undo: (() -> Unit)? = null

            fun acquire(context: Context) {
                synchronized(lock) {
                    if (holders == 0) undo = routeToSpeaker(context.applicationContext)
                    holders += 1
                }
            }

            fun release() {
                synchronized(lock) {
                    holders -= 1
                    if (holders > 0) return
                    holders = 0
                    undo?.let { runCatching(it) }
                    undo = null
                }
            }

            /**
             * Communication mode and the built-in speaker, returning how to undo both — or null when
             * a headset is connected or the platform refused, leaving the route as it was.
             *
             * Failures are logged rather than thrown: audio on the wrong speaker is a worse session,
             * not a broken one, and refusing to play over it would be.
             */
            private fun routeToSpeaker(context: Context): (() -> Unit)? {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
                if (audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSET_TYPES }) return null
                // Captured before anything changes, so every path out — success or a refusal half
                // way — can put back exactly what it touched. A refusal that left the mode switched
                // would otherwise hold the whole process in communication mode with no undo.
                val previousMode = audio.mode

                @Suppress("DEPRECATION")
                val previousSpeakerphone = audio.isSpeakerphoneOn
                var selected = false
                val undo: () -> Unit = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (selected) audio.clearCommunicationDevice()
                    } else {
                        @Suppress("DEPRECATION")
                        audio.isSpeakerphoneOn = previousSpeakerphone
                    }
                    audio.mode = previousMode
                }
                return try {
                    audio.mode = AudioManager.MODE_IN_COMMUNICATION
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val speaker =
                            audio.availableCommunicationDevices.firstOrNull {
                                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                            }
                        selected = speaker != null && audio.setCommunicationDevice(speaker)
                    } else {
                        @Suppress("DEPRECATION")
                        audio.isSpeakerphoneOn = true
                    }
                    undo
                } catch (refused: SecurityException) {
                    Log.w(TAG, "Could not route playout to the speaker; leaving the platform's route", refused)
                    runCatching(undo)
                    null
                }
            }
        }

        private companion object {
            const val TAG = "ReactorSpeaker"

            /**
             * Outputs that mean the user chose where audio goes. TYPE_BLE_HEADSET (26) by value:
             * its constant only exists from API 31, and the module compiles for 26.
             */
            val HEADSET_TYPES =
                setOf(
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_HEARING_AID,
                    26,
                )
        }
    }
