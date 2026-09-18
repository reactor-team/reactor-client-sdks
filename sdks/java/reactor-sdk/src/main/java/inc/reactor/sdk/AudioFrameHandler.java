package inc.reactor.sdk;

/**
 * Receives audio frames.
 *
 * <p>Called inline on the FFI's delivery thread. Whatever this does, the frame is gone when it
 * returns — see {@link AudioFrame}.
 */
@FunctionalInterface
public interface AudioFrameHandler {

    /**
     * @param frame the frame, valid only until this returns
     */
    void onFrame(AudioFrame frame);
}
