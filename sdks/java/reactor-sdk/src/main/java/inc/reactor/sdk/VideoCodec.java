package inc.reactor.sdk;

/**
 * How an {@link EncodedFrame} is encoded.
 *
 * <p>A step frame's encoding on the wire. The runtime decodes an encoded frame before the step
 * runs, so the model reads the same {@code (height, width, 3)} RGB it would for a raw push.
 */
public enum VideoCodec {
    /** H.264: one access unit per frame, in Annex B byte-stream format. */
    H264(inc.reactor.sdk.internal.Ffi.FRAME_ENCODING_H264);

    private final int wireValue;

    VideoCodec(int wireValue) {
        this.wireValue = wireValue;
    }

    /** @return the {@code REACTOR_FRAME_ENCODING_*} value the native library takes for it */
    int wireValue() {
        return wireValue;
    }
}
