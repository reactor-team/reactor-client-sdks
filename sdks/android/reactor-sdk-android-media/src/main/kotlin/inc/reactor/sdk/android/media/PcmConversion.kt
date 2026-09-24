package inc.reactor.sdk.android.media

/**
 * PCM between what a device gives and what the wire takes.
 *
 * Both directions are lossy in ways worth being explicit about, because the alternative is an SDK
 * that silently halves someone's sample rate and leaves them wondering why the model sounds
 * wrong.
 */
public object PcmConversion {
    /**
     * Mix interleaved [channels] down to mono by averaging.
     *
     * Averaging rather than taking the first channel: on a device whose second microphone is
     * noise-reference, channel 0 alone is the raw signal the noise suppressor was meant to
     * subtract from. Averaging is not correct either, but it is wrong in a way that sounds like
     * quiet audio rather than like a different recording.
     */
    public fun toMono(
        interleaved: ShortArray,
        channels: Int,
    ): ShortArray {
        require(channels > 0) { "channels must be positive, got $channels" }
        if (channels == 1) return interleaved
        val frames = interleaved.size / channels
        val mono = ShortArray(frames)
        for (frame in 0 until frames) {
            var sum = 0
            for (channel in 0 until channels) sum += interleaved[frame * channels + channel]
            mono[frame] = (sum / channels).toShort()
        }
        return mono
    }

    /**
     * Resample by nearest-neighbour.
     *
     * Deliberately the cheap algorithm, and deliberately documented as such. This runs on the
     * capture thread on a phone; a windowed-sinc resampler there costs battery the caller did not
     * ask to spend. A caller who needs better should resample upstream and pass the rate through
     * unchanged — which is why [resample] is a no-op when the rates already match.
     */
    public fun resample(
        samples: ShortArray,
        fromRate: Int,
        toRate: Int,
    ): ShortArray {
        require(fromRate > 0 && toRate > 0) { "sample rates must be positive" }
        if (fromRate == toRate || samples.isEmpty()) return samples
        val outSize = (samples.size.toLong() * toRate / fromRate).toInt()
        if (outSize <= 0) return ShortArray(0)
        val out = ShortArray(outSize)
        for (i in out.indices) {
            val source = (i.toLong() * fromRate / toRate).toInt()
            out[i] = samples[if (source < samples.size) source else samples.size - 1]
        }
        return out
    }
}
