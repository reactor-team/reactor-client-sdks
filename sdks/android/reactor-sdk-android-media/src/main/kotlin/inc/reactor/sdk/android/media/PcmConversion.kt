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
     * Nearest-neighbour resampling that remembers where it was.
     *
     * [resample] is correct for one buffer and wrong for a stream of them: it restarts at source
     * index 0 every call, so at a non-integer ratio — 44.1k to 48k is the common one — the
     * fractional part of the position is thrown away at every buffer boundary. Each buffer is
     * individually plausible and the stream drifts, repeating or dropping a sample every few
     * milliseconds for as long as the capture lasts.
     *
     * This carries the position across calls. Not thread-safe, and does not need to be: one
     * instance belongs to one capture, and a capture delivers its buffers in order on one thread.
     *
     * The position is kept as an integer numerator over [toRate] rather than as a float. A double
     * accumulating `fromRate / toRate` per output sample drifts measurably over an hour of audio,
     * which is exactly the timescale this class exists for.
     */
    public class Resampler(
        private val fromRate: Int,
        private val toRate: Int,
    ) {
        init {
            require(fromRate > 0 && toRate > 0) { "sample rates must be positive" }
        }

        /** Source position for the next output sample, scaled by [toRate]. */
        private var position = 0L

        public fun resample(samples: ShortArray): ShortArray {
            if (fromRate == toRate || samples.isEmpty()) return samples
            val span = samples.size.toLong() * toRate - position
            if (span <= 0) {
                // The carried position is already past this buffer: it contributed no output
                // sample of its own, which is ordinary at a downward ratio with small buffers.
                position -= samples.size.toLong() * toRate
                return ShortArray(0)
            }
            val count = ((span + fromRate - 1) / fromRate).toInt()
            val out = ShortArray(count)
            for (i in 0 until count) {
                out[i] = samples[(position / toRate).toInt()]
                position += fromRate
            }
            // Rebase onto the next buffer, keeping the fraction. This is the line the stateless
            // version does not have.
            position -= samples.size.toLong() * toRate
            return out
        }
    }

    /**
     * Resample by nearest-neighbour, from the beginning.
     *
     * For a caller holding one complete buffer. **A stream of buffers wants [Resampler]** — see
     * its note for why calling this per buffer drifts.
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

    /**
     * Copy [samples] into [into], as little-endian interleaved s16 — what `Track.pushFrame` takes.
     *
     * The bridge between the two halves of this module: [Microphone] hands its caller a
     * `ShortArray` and the ABI takes a **direct** `ByteBuffer`, so without this every consumer
     * writes the same loop, and writes it with the platform's byte order rather than the wire's.
     *
     * [into] is reused across calls on purpose. Allocating a direct buffer per 10ms frame is
     * megabytes a second of garbage, and direct buffers are the kind the collector reclaims
     * last — see [directBufferFor] for making one once.
     *
     * @return [into], positioned at 0 and limited to the bytes written, ready to push.
     */
    public fun toDirectBuffer(
        samples: ShortArray,
        into: java.nio.ByteBuffer,
    ): java.nio.ByteBuffer {
        require(into.isDirect) {
            "pushFrame needs a direct ByteBuffer — use directBufferFor(), not ByteBuffer.allocate()"
        }
        require(into.capacity() >= samples.size * 2) {
            "the buffer holds ${into.capacity()} bytes, which is not enough for " +
                "${samples.size} samples (${samples.size * 2} bytes)"
        }
        into.clear()
        // Little-endian explicitly, not nativeOrder(): the ABI's PCM is little-endian, and every
        // Android device being little-endian today is a fact about the present, not a contract.
        into.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) into.putShort(sample)
        into.flip()
        return into
    }

    /**
     * A direct buffer big enough for [frames] frames at [channels] channels.
     *
     * Allocate one per track and reuse it — see [toDirectBuffer].
     */
    public fun directBufferFor(
        frames: Int,
        channels: Int = 1,
    ): java.nio.ByteBuffer {
        require(frames > 0) { "frames must be positive, got $frames" }
        require(channels > 0) { "channels must be positive, got $channels" }
        return java.nio.ByteBuffer
            .allocateDirect(frames * channels * 2)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
    }
}
