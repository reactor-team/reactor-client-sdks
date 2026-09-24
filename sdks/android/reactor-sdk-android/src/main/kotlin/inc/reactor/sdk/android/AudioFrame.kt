package inc.reactor.sdk.android

import java.nio.ShortBuffer

/**
 * One decoded audio frame: interleaved signed 16-bit PCM.
 *
 * **[samples] is borrowed**, exactly as [VideoFrame.pixels] is — valid only until the handler
 * returns. Roughly 10 ms of audio arrives per frame.
 */
public class AudioFrame internal constructor(
    public val trackName: String,
    /** Borrowed interleaved int16 PCM. Valid only for this call. */
    public val samples: ShortBuffer,
    /** Total samples across all channels — not frames-per-channel. */
    public val sampleCount: Int,
    public val sampleRate: Int,
    public val channels: Int,
) {
    /** A copy of [samples] that outlives the callback. */
    public fun copySamples(): ShortArray {
        val duplicate = samples.duplicate()
        return ShortArray(duplicate.remaining()).also { duplicate.get(it) }
    }

    override fun toString(): String = "AudioFrame($trackName, $sampleCount samples, ${sampleRate}Hz, ${channels}ch)"
}
