package inc.reactor.sdk.android.media

import inc.reactor.sdk.AudioFrame
import kotlin.math.roundToInt

/** Stateful linear interpolation and mono/stereo mixing. Not a band-limited studio resampler.
 * Preserve one converter per stream and reset it when the source format changes.
 */
class PcmConverter(
    val outputRate: Int,
    val outputChannels: Int,
) {
    init {
        require(outputRate in setOf(8000, 16000, 24000, 32000, 44100, 48000) && outputChannels in 1..2)
    }

    private var sourceRate = 0
    private var sourceChannels = 0
    private var previous: IntArray? = null
    private var phase = 0

    @Synchronized fun convert(frame: AudioFrame): AudioFrame {
        require(frame.sampleRate in setOf(8000, 16000, 24000, 32000, 44100, 48000) && frame.channels in 1..2)
        require(frame.samplesPerChannel <= frame.sampleRate / 10) { "Convert at most 100 ms per block" }
        if (sourceRate == 0) {
            sourceRate = frame.sampleRate
            sourceChannels = frame.channels
        }
        require(sourceRate == frame.sampleRate && sourceChannels == frame.channels) { "Source PCM format changed; create a new converter" }
        val capacity = ((frame.samplesPerChannel.toLong() * outputRate / sourceRate + outputRate / sourceRate + 2) * outputChannels).toInt()
        val output = ShortArray(capacity)
        var count = 0
        for (index in 0 until frame.samplesPerChannel) {
            val left = frame.samples[index * frame.channels].toInt()
            val right = if (frame.channels == 2) frame.samples[index * 2 + 1].toInt() else left
            val current = if (outputChannels == 1) intArrayOf((left + right) / 2) else intArrayOf(left, right)
            val before = previous
            if (before == null) {
                for (sample in current) output[count++] = sample.toShort()
                phase = sourceRate
            } else {
                while (phase <= outputRate) {
                    val fraction = phase.toDouble() / outputRate
                    for (channel in current.indices) {
                        output[count++] =
                            (before[channel] + (current[channel] - before[channel]) * fraction)
                                .roundToInt()
                                .coerceIn(
                                    -32768,
                                    32767,
                                ).toShort()
                    }
                    phase += sourceRate
                }
                phase -= outputRate
            }
            previous = current
        }
        return AudioFrame(output.copyOf(count), outputRate, outputChannels)
    }
}
