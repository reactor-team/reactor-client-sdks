package inc.reactor.sdk.android.media

/**
 * The arithmetic and the rules behind the audio adapters, with no Android class in sight.
 *
 * Deliberately separated from [AudioRecordCapture] and [AudioTrackRender], for the same reason
 * [YuvToBgra] is separate from the camera: `AudioRecord` and `AudioTrack` cannot be constructed on
 * a JVM, so anything left inside them is untestable without a device. What is actually easy to get
 * wrong here — buffer sizing, channel masks, the refusal messages — is arithmetic and lookup, and
 * all of it lives here where a test can reach it.
 */
public object AudioFormats {
    /** Mono and stereo are what this module supports; the ABI's own frames are interleaved s16. */
    public const val MAX_CHANNELS: Int = 2

    /**
     * How many sample *frames* a buffer of [bytes] holds.
     *
     * Frames, not samples: the ABI counts per channel and so does `AudioRecord`, and conflating
     * the two halves or doubles the duration of every buffer in a stereo stream.
     */
    public fun framesIn(
        bytes: Int,
        channels: Int,
    ): Int = bytes / (2 * channels)

    /**
     * The buffer size to ask the platform for, in bytes.
     *
     * [minimumBytes] is what `AudioRecord.getMinBufferSize` or `AudioTrack.getMinBufferSize`
     * returned. The platform's minimum is the point at which the device *just* keeps up, so
     * handing it back verbatim means every scheduling hiccup is a dropout. Multiplied, then
     * raised to hold at least [millis] of audio, whichever is larger.
     *
     * @throws IllegalArgumentException if the platform reported an error rather than a size —
     *   AudioRecord returns negative constants there, and passing one on produces an object whose
     *   state is UNINITIALIZED and whose failure surfaces much later as silence.
     */
    public fun bufferSizeBytes(
        minimumBytes: Int,
        sampleRate: Int,
        channels: Int,
        millis: Int = 100,
        factor: Int = 4,
    ): Int {
        require(minimumBytes > 0) {
            "The platform reported $minimumBytes for the minimum buffer size, which is its way of " +
                "saying it cannot do ${sampleRate}Hz at $channels channel(s) in 16-bit PCM. Ask " +
                "for a format it supports rather than passing this on."
        }
        val forDuration = sampleRate * channels * 2 * millis / 1000
        return maxOf(minimumBytes * factor, forDuration)
    }

    /** Rejects a format this module cannot carry, naming the limit rather than failing later. */
    public fun requireSupported(
        sampleRate: Int,
        channels: Int,
    ) {
        require(sampleRate > 0) { "sampleRate must be positive, got $sampleRate" }
        require(channels in 1..MAX_CHANNELS) {
            "channels must be 1 or 2, got $channels — the ABI carries interleaved 16-bit PCM and " +
                "this module converts to mono before pushing"
        }
    }

    /**
     * Why a device would not open, in terms a caller can act on.
     *
     * `AudioRecord` reports failure as a state rather than an exception, and the state alone
     * ("1 is INITIALIZED") tells a reader nothing. The two real causes are a missing permission
     * and a format the hardware will not do, and they want different fixes.
     */
    public fun captureFailureMessage(
        sampleRate: Int,
        channels: Int,
        hasPermission: Boolean,
    ): String =
        if (!hasPermission) {
            "The microphone could not be opened because this app does not hold RECORD_AUDIO. The " +
                "SDK declares no permissions of its own — requesting it is the app's call, " +
                "because only the app knows when to ask."
        } else {
            "The microphone could not be opened at ${sampleRate}Hz with $channels channel(s). The " +
                "permission is held, so this is the device refusing the format: 44100Hz mono is " +
                "the one every Android device supports."
        }
}
