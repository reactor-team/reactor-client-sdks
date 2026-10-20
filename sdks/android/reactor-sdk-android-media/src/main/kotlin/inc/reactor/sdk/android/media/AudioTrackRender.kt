package inc.reactor.sdk.android.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
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
 * and restores both on [stop]. A headset or Bluetooth device wins whenever it is connected —
 * before playout starts or in the middle of it — and unplugging it brings the speaker back, as
 * `.defaultToSpeaker` does on iOS.
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
         *
         * Between the two, the route follows headsets. With none connected: communication mode and
         * the built-in speaker. With one: the mode the platform had before, and the speaker
         * explicitly off. Communication mode is not kept for a headset because a device may not
         * route it there at all — a Galaxy S9 on Android 10 sends communication-mode playout to the
         * earpiece with USB-C earphones plugged in, whatever device the track prefers — and with
         * the sound in the user's ears there is no speaker-to-microphone echo for it to cancel.
         * The speaker is switched off rather than put back as found, because what was found can
         * itself be a forced speaker left behind by another app.
         */
        private object SpeakerRoute {
            private val lock = Any()
            private var holders = 0
            private var audio: AudioManager? = null
            private var previousMode = AudioManager.MODE_NORMAL
            private var previousSpeakerphone = false

            /**
             * Follows headsets for as long as the route is held. Checking once at start is not
             * enough: a headset plugged in mid-session would otherwise stay silent under a forced
             * speaker, and one unplugged would leave playout on the earpiece.
             */
            private val devices =
                object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = follow()

                    override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = follow()
                }

            fun acquire(context: Context) {
                synchronized(lock) {
                    holders += 1
                    if (holders > 1) return
                    val manager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
                    previousMode = manager.mode
                    @Suppress("DEPRECATION")
                    previousSpeakerphone = manager.isSpeakerphoneOn
                    audio = manager
                    // Routed now, before the caller plays: the callback's first delivery arrives
                    // later on the main looper, and finds the route already chosen.
                    follow()
                    // The main looper, not the caller's: a renderer may be started from a thread
                    // without one, and the callback must outlive whichever thread that was.
                    manager.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper()))
                }
            }

            fun release() {
                synchronized(lock) {
                    holders -= 1
                    if (holders > 0) return
                    holders = 0
                    val manager = audio ?: return
                    audio = null
                    manager.unregisterAudioDeviceCallback(devices)
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            manager.clearCommunicationDevice()
                        } else {
                            @Suppress("DEPRECATION")
                            manager.isSpeakerphoneOn = previousSpeakerphone
                        }
                        manager.mode = previousMode
                    }
                }
            }

            /**
             * Communication mode on the speaker while no headset is connected; the platform's
             * previous mode, speaker off, while one is.
             *
             * Failures are logged rather than thrown: audio on the wrong speaker is a worse session,
             * not a broken one, and refusing to play over it would be.
             */
            private fun follow() {
                synchronized(lock) {
                    // A callback already queued when the last holder released.
                    val manager = audio ?: return
                    val headset = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSET_TYPES }
                    try {
                        manager.mode = if (headset) previousMode else AudioManager.MODE_IN_COMMUNICATION
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (headset) {
                                manager.clearCommunicationDevice()
                            } else {
                                manager.availableCommunicationDevices
                                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                                    ?.let { manager.setCommunicationDevice(it) }
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            manager.isSpeakerphoneOn = !headset
                        }
                    } catch (refused: SecurityException) {
                        Log.w(TAG, "Could not choose the playout device; leaving the platform's route", refused)
                    }
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
