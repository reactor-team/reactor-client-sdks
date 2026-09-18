package inc.reactor.sdk;

/**
 * Receives video frames.
 *
 * <p>Called inline on the FFI's delivery thread. Whatever this does, the frame is gone when it
 * returns — see {@link VideoFrame}.
 */
@FunctionalInterface
public interface VideoFrameHandler {

    /**
     * @param frame the frame, valid only until this returns
     */
    void onFrame(VideoFrame frame);
}
