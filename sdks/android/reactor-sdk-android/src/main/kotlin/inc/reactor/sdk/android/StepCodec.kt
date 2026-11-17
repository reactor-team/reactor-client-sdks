package inc.reactor.sdk.android

/**
 * A codec a stepped session's video step frames can travel in between this client and the runtime.
 *
 * Chosen with [Reactor.setCodecPreference], read back per track from
 * [Reactor.stepFrameEncodings]. The encoding is the transport's business only: [Reactor.step]
 * still answers with RGB frames, and [Track.pushFrame] still takes BGRA pixels, whatever the codec.
 *
 * Declared in the default order of preference: H.264, VP9, AV1, VP8.
 */
public enum class StepCodec(
    internal val wire: String,
) {
    H264("h264"),
    VP9("vp9"),
    AV1("av1"),
    VP8("vp8"),
}
