package inc.reactor.sdk.audio;

/**
 * The PCM the Reactor core speaks: signed 16-bit, little-endian, interleaved.
 *
 * <p>Only the rate and the channel count vary. Everything else is fixed by the FFI's own contract,
 * so this type exists to stop a caller quietly handing a device a format the core cannot carry.
 *
 * @param sampleRate samples per second, per channel
 * @param channels how many channels the samples are interleaved across
 */
public record PcmFormat(int sampleRate, int channels) {

    /** What a Reactor model usually declares. */
    public static final PcmFormat DEFAULT = new PcmFormat(48_000, 1);

    /** Bits per sample. Fixed: the core carries 16-bit PCM and nothing else. */
    public static final int BITS_PER_SAMPLE = 16;

    public PcmFormat {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive; got " + sampleRate);
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive; got " + channels);
        }
    }

    /** @return how many bytes one sample of every channel takes */
    public int frameBytes() {
        return channels * (BITS_PER_SAMPLE / 8);
    }
}
