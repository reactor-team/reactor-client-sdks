package inc.reactor.sdk;

import inc.reactor.sdk.internal.ClientPeer;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * A named media slot the model declared.
 *
 * <p>One type for all four combinations of {@link TrackKind} and {@link TrackDirection}, because
 * the operations are the same operations. What a track will not do is whatever its declaration
 * rules out, and it says so rather than doing nothing: the native layer is permissive, so
 * registering a frame handler on a sendonly track would simply never fire, and the caller would see
 * a session that looks healthy and produces nothing.
 *
 * <p>Ask for tracks by name — {@code reactor.track("main_video")} — the way an application that
 * knows its model does. Listing and filtering is for discovery.
 *
 * <p><b>Frame handlers need an explicitly typed lambda.</b> {@code onFrame} is overloaded for video
 * and audio, and Java cannot choose between two functional interfaces from an untyped lambda:
 *
 * <pre>{@code
 * track.onFrame((VideoFrame frame) -> render(frame));   // compiles
 * track.onFrame(frame -> render(frame));                // ambiguous
 * }</pre>
 */
public final class Track {

    /**
     * What the core's audio path accepts, from the FFI header.
     *
     * <p>Anything else is logged and dropped down there, which from here looks like a successful
     * push producing silence.
     */
    private static final java.util.Set<Integer> SUPPORTED_SAMPLE_RATES =
            java.util.Set.of(8_000, 16_000, 24_000, 32_000, 44_100, 48_000);

    private final ClientPeer peer;
    private final String name;
    private final TrackKind kind;
    private final TrackDirection direction;
    private final @Nullable String mid;

    Track(ClientPeer peer, String name, TrackKind kind, TrackDirection direction, @Nullable String mid) {
        this.peer = peer;
        this.name = name;
        this.kind = kind;
        this.direction = direction;
        this.mid = mid;
    }

    /** @return the name the session declared this track under */
    public String name() {
        return name;
    }

    /** @return whether this track carries video or audio */
    public TrackKind kind() {
        return kind;
    }

    /** @return which way media flows */
    public TrackDirection direction() {
        return direction;
    }

    /** @return the transceiver's mid, or empty while it is unresolved */
    public Optional<String> mid() {
        return Optional.ofNullable(mid);
    }

    /** @return whether this track is currently paused */
    public boolean isPaused() {
        return peer.pausedTracks().contains(name);
    }

    /**
     * Receives this track's video frames.
     *
     * @param handler called inline on the FFI's delivery thread, with a frame valid only for the
     *     duration of the call
     * @return a subscription that removes the handler
     * @throws ReactorException when this track is sendonly, carries audio rather than video, or
     *     belongs to a stepped session, whose output arrives in {@link StepResult} instead
     */
    public Subscription onFrame(VideoFrameHandler handler) {
        requireReceivable();
        requireStreamed();
        requireKind(TrackKind.VIDEO, "VideoFrameHandler", "AudioFrameHandler");
        return peer.onVideoFrame(name, handler);
    }

    /**
     * Receives this track's audio frames.
     *
     * @param handler called inline on the FFI's delivery thread, with a frame valid only for the
     *     duration of the call
     * @return a subscription that removes the handler
     * @throws ReactorException when this track is sendonly, carries video rather than audio, or
     *     belongs to a stepped session, whose output arrives in {@link StepResult} instead
     */
    // Overloaded on two functional interfaces, so an implicitly-typed lambda cannot pick between
    // them: `onFrame(frame -> ...)` is ambiguous and `onFrame((AudioFrame frame) -> ...)` is not.
    // Kept anyway. Every Reactor SDK exposes one frame API for both kinds and lets Track.kind
    // decide which arrives; splitting this into onVideoFrame/onAudioFrame would make Java the one
    // binding with a different object model, to save callers one type name.
    @SuppressWarnings("overloads")
    public Subscription onFrame(AudioFrameHandler handler) {
        requireReceivable();
        requireStreamed();
        requireKind(TrackKind.AUDIO, "AudioFrameHandler", "VideoFrameHandler");
        return peer.onAudioFrame(name, handler);
    }

    // ── Sending ─────────────────────────────────────────────────────────────

    /**
     * Asks for a sender behind this track.
     *
     * <p>Nothing can be pushed until this settles. Publishing is what puts a sender behind the
     * slot, and a publish does not survive the session leaving {@code ready}.
     *
     * @return settles when the track is publishing
     * @throws ReactorException when this track is recvonly
     */
    public CompletableFuture<Void> publish() {
        requireSendable("publish");
        return peer.publish(name);
    }

    /**
     * Tells the session this track is finished.
     *
     * @throws ReactorException when this track is recvonly, or when the session refused — in which
     *     case the track stays published, so retrying means something
     */
    public void unpublish() {
        requireSendable("unpublish");
        peer.unpublish(name);
    }

    /** @return whether this track has a sender, is getting one, or has none */
    public PublishState publishState() {
        return peer.publishState(name);
    }

    /** @return whether this track has a sender behind it right now */
    public boolean isPublished() {
        return publishState() == PublishState.PUBLISHED;
    }

    /**
     * Stops this track producing.
     *
     * @return settles when it is paused
     */
    public CompletableFuture<Void> pause() {
        return peer.pause(name);
    }

    /**
     * Starts it producing again.
     *
     * @return settles when it is producing
     */
    public CompletableFuture<Void> resume() {
        return peer.resume(name);
    }

    /**
     * Pushes one BGRA video frame.
     *
     * <p><b>In a stepped session this sends nothing.</b> The frame is buffered, with its tag and
     * capture time, and the next {@link Reactor#step} carries the frames pushed since the one
     * before — per track, in push order, converted to an {@code (height, width, 3)} {@code uint8}
     * RGB array. The buffer holds at most one data-channel message's worth of frame bytes: pushing
     * faster than steps run drops the oldest frames, across tracks. Publish the track first, as for
     * a streamed session.
     *
     * @param bgra exactly {@code width * height * 4} bytes, in B, G, R, A order
     * @param width frame width in pixels
     * @param height frame height in pixels
     * @throws ReactorException when this track is recvonly, carries audio, has no sender yet, or
     *     the buffer is not the size the dimensions require
     */
    public void pushFrame(byte[] bgra, int width, int height) {
        pushFrame(bgra, width, height, null, null);
    }

    /**
     * Pushes one BGRA video frame with a tag and an explicit capture time.
     *
     * @param bgra exactly {@code width * height * 4} bytes
     * @param width frame width in pixels
     * @param height frame height in pixels
     * @param userData a tag for the far end, which drops it unless it declared that it reads tags
     * @param captureTimeUs when this was captured, on the engine clock — see {@link
     *     Reactor#timeMicros()}. {@code null} means now
     * @throws ReactorException for any of the reasons {@link #pushFrame(byte[], int, int)} gives
     */
    public void pushFrame(
            byte[] bgra, int width, int height, byte @Nullable [] userData, @Nullable Long captureTimeUs) {
        prepareFrame(bgra, PixelFormat.BGRA, width, height, userData, captureTimeUs)
                .run();
    }

    /**
     * Pushes one video frame laid out as {@code format}. {@link PixelFormat#RGB} is converted to
     * BGRA, opaque, before the push.
     *
     * @param pixels exactly {@code width * height * format.bytesPerPixel()} bytes
     * @param format how the pixels are laid out
     * @param width frame width in pixels
     * @param height frame height in pixels
     * @throws ReactorException for any of the reasons {@link #pushFrame(byte[], int, int)} gives
     */
    public void pushFrame(byte[] pixels, PixelFormat format, int width, int height) {
        pushFrame(pixels, format, width, height, null, null);
    }

    /**
     * Pushes one video frame laid out as {@code format}, with a tag and an explicit capture time.
     *
     * @param pixels exactly {@code width * height * format.bytesPerPixel()} bytes
     * @param format how the pixels are laid out
     * @param width frame width in pixels
     * @param height frame height in pixels
     * @param userData a tag for the far end, which drops it unless it declared that it reads tags
     * @param captureTimeUs when this was captured, on the engine clock, or {@code null} for now
     * @throws ReactorException for any of the reasons {@link #pushFrame(byte[], int, int)} gives
     */
    public void pushFrame(
            byte[] pixels,
            PixelFormat format,
            int width,
            int height,
            byte @Nullable [] userData,
            @Nullable Long captureTimeUs) {
        prepareFrame(pixels, format, width, height, userData, captureTimeUs).run();
    }

    private Runnable prepareFrame(
            byte[] pixels,
            PixelFormat format,
            int width,
            int height,
            byte @Nullable [] userData,
            @Nullable Long captureTimeUs) {
        Objects.requireNonNull(format, "format");
        requireSendable("pushFrame");
        requireKind(TrackKind.VIDEO, "a BGRA or RGB byte[]", "a short[] of PCM");
        requirePublished("pushFrame");
        requireExactFrameSize(pixels, format, width, height);
        // The native library takes BGRA only, so RGB is converted here, checked first, before the
        // push is handed back.
        byte[] bgra = format == PixelFormat.RGB ? rgbToBgra(pixels) : pixels;
        return () -> peer.pushVideoFrame(name, bgra, width, height, userData, captureTimeUs);
    }

    /** RGB to BGRA, the alpha the wire format needs added opaque, as the Python SDK does. */
    private static byte[] rgbToBgra(byte[] rgb) {
        int pixels = rgb.length / 3;
        byte[] bgra = new byte[pixels * 4];
        for (int i = 0; i < pixels; i++) {
            bgra[i * 4] = rgb[i * 3 + 2];
            bgra[i * 4 + 1] = rgb[i * 3 + 1];
            bgra[i * 4 + 2] = rgb[i * 3];
            bgra[i * 4 + 3] = (byte) 0xFF;
        }
        return bgra;
    }

    /**
     * Pushes interleaved 16-bit PCM.
     *
     * <p><b>In a stepped session this sends nothing.</b> The samples are buffered for the next
     * {@link Reactor#step} as one planar {@code (channels, samples)} {@code int16} frame per push. A
     * step frame carries no sample rate, so push at the rate the model's track declares.
     *
     * @param pcm the samples, across every channel
     * @param sampleRate samples per second
     * @param channels how many channels the samples are interleaved across
     * @throws ReactorException when this track is recvonly, carries video, has no sender yet, or
     *     the arguments do not describe the buffer
     */
    public void pushFrame(short[] pcm, int sampleRate, int channels) {
        prepareFrame(pcm, sampleRate, channels).run();
    }

    private Runnable prepareFrame(short[] pcm, int sampleRate, int channels) {
        requireSendable("pushFrame");
        requireKind(TrackKind.AUDIO, "a short[] of PCM", "a BGRA byte[]");
        requirePublished("pushFrame");
        if (!SUPPORTED_SAMPLE_RATES.contains(sampleRate) || channels < 1 || channels > 2) {
            // Positivity was not the contract. The FFI takes 8/16/24/32/44.1/48 kHz in mono or
            // stereo and logs-and-drops anything else, so pushFrame(pcm, 12345, 3) returned
            // normally and produced no audio — a caller pushing at rate and hearing silence, which
            // is the failure this table exists to turn into a sentence.
            throw refusal(
                    "pushFrame",
                    "the FFI takes " + SUPPORTED_SAMPLE_RATES + " Hz in mono or stereo, and drops anything else; got"
                            + " sampleRate=" + sampleRate + ", channels=" + channels + ".");
        }
        if (pcm.length % channels != 0) {
            throw refusal(
                    "pushFrame",
                    "interleaved PCM must hold whole frames: " + pcm.length + " samples do not divide into " + channels
                            + " channels.");
        }
        return () -> peer.pushAudioFrame(name, pcm, sampleRate, channels);
    }

    /**
     * Buffers one H.264 access unit, its bytes as they are, for the next step.
     *
     * @param data one access unit, Annex B
     * @param width the width of the frame it decodes to, in pixels
     * @param height the height of the frame it decodes to, in pixels
     * @throws IllegalArgumentException when {@code data} is empty or a dimension is not positive
     * @throws ReactorException for any of the reasons {@link #pushEncodedFrame(EncodedFrame)} gives
     */
    public void pushEncodedFrame(byte[] data, int width, int height) {
        pushEncodedFrame(EncodedFrame.h264(data, width, height));
    }

    /**
     * Buffers one encoded video frame, its bytes as they are, for the next step.
     *
     * <p>Stepped sessions only: like {@link #pushFrame(byte[], int, int)} there, this sends
     * nothing, and the next {@link Reactor#step} carries the frame.
     *
     * <pre>{@code
     * for (byte[] accessUnit : window) {             // keyframe first
     *     camera.pushEncodedFrame(accessUnit, 1280, 720);
     * }
     * StepResult result = reactor.step().join();
     * }</pre>
     *
     * <p>The runtime decodes a track's encoded frames of one step as one stream, before the step
     * runs, and the model reads RGB frames as if they had been pushed raw. So push them in decode
     * order, starting at a keyframe; a step whose frames do not decode fails with {@link
     * StepRefusedException}. Encoded and raw pushes to one track keep their push order, and share
     * the buffer's byte budget.
     *
     * @param frame the frame, with the size it decodes to and, optionally, a tag and capture time
     * @throws ReactorException when this track is recvonly, carries audio, belongs to a streamed
     *     session, has no sender yet, or the native library predates encoded step frames
     */
    public void pushEncodedFrame(EncodedFrame frame) {
        prepareEncodedFrame(frame).run();
    }

    private Runnable prepareEncodedFrame(EncodedFrame frame) {
        java.util.Objects.requireNonNull(frame, "frame");
        requireSendable("pushEncodedFrame");
        if (kind != TrackKind.VIDEO) {
            throw refusal(
                    "pushEncodedFrame",
                    "track \"" + name + "\" carries audio; only a video track takes encoded frames.");
        }
        if (peer.generation() != Generation.STEPPED) {
            // A streamed session has no step to carry the frame, and no encoded path to send it on.
            throw refusal(
                    "pushEncodedFrame",
                    "pushEncodedFrame() needs a stepped session: create the client with"
                            + " ReactorOptions.builder(...).generation(Generation.STEPPED)");
        }
        requirePublished("pushEncodedFrame");
        peer.requireEncodedVideoFrames();
        return () -> peer.pushEncodedVideoFrame(
                name,
                frame.codec().wireValue(),
                frame.dataUnsafe(),
                frame.width(),
                frame.height(),
                frame.userDataUnsafe(),
                frame.captureTimeUsOrNull());
    }

    /**
     * Checks one frame of a step's media as the method that takes its kind would, and returns its
     * push. The step checks every frame before it pushes any: the native buffer can only be
     * cleared whole, so a frame refused halfway through would leave the ones before it to ride the
     * next step.
     */
    Runnable prepare(StepInput input) {
        return switch (input) {
            case EncodedFrame encoded -> prepareEncodedFrame(encoded);
            case StepInput.Video video ->
                prepareFrame(
                        video.pixels(),
                        video.format(),
                        video.width(),
                        video.height(),
                        video.userData(),
                        video.captureTimeUs());
            case StepInput.Audio audio -> prepareFrame(audio.pcm(), audio.sampleRate(), audio.channels());
        };
    }

    /**
     * Bounds this track's bitrate.
     *
     * @param minBps lower bound, or a negative value for none
     * @param maxBps upper bound, or a negative value for none
     * @return settles when the bounds are applied
     */
    public CompletableFuture<Void> setBitrate(int minBps, int maxBps) {
        return peer.setTrackBitrate(name, minBps, maxBps);
    }

    private void requireSendable(String operation) {
        if (direction != TrackDirection.SENDONLY) {
            throw refusal(
                    operation,
                    "track \"" + name + "\" is recvonly — the model sends on it and this client receives."
                            + " Push frames into a sendonly track instead.");
        }
    }

    private void requirePublished(String operation) {
        PublishState state = publishState();
        if (state == PublishState.PUBLISHED) {
            return;
        }
        // The FFI would take this frame and drop it: there is no sender behind the slot. A caller
        // pushing at 30fps and seeing nothing arrive is the failure this refusal exists to prevent.
        throw refusal(
                operation,
                state == PublishState.PUBLISHING
                        ? "track \"" + name + "\" is still publishing. Await the future publish() returned"
                                + " before pushing; until it settles there is no sender behind the slot."
                        : "track \"" + name + "\" is not published. Call publish() and await it first —"
                                + " publishing is what puts a sender behind the slot, and it does not survive"
                                + " the session leaving ready.");
    }

    private void requireExactFrameSize(byte[] pixels, PixelFormat format, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw refusal("pushFrame", "width and height must both be positive; got " + width + "x" + height + ".");
        }
        // Computed in long, because width * height * 4 overflows int at about 23000x23000 and an
        // overflowed comparison would accept a buffer far too small and read past its end.
        long required = (long) width * height * format.bytesPerPixel();
        if (pixels.length != required) {
            throw refusal(
                    "pushFrame",
                    "a " + width + "x" + height + " " + format + " frame needs exactly " + required + " bytes; got "
                            + pixels.length + ".");
        }
    }

    private static ReactorException refusal(String operation, String message) {
        return ReactorException.of(ErrorCode.INVALID_STATE.code(), message, null, operation, null);
    }

    private void requireStreamed() {
        if (peer.generation() == Generation.STEPPED) {
            // Nothing streams in a stepped session, so a handler here would never fire — a client
            // that looks healthy and receives nothing, which is what every refusal here prevents.
            throw ReactorException.of(
                    ErrorCode.INVALID_STATE.code(),
                    "track \"" + name + "\" belongs to a stepped session, which streams nothing: what the"
                            + " model produces on it arrives in the StepResult that Reactor.step() answers"
                            + " with. Read result.track(\"" + name + "\").frames() instead.",
                    null,
                    "onFrame",
                    null);
        }
    }

    private void requireReceivable() {
        if (direction != TrackDirection.RECVONLY) {
            throw ReactorException.of(
                    ErrorCode.INVALID_STATE.code(),
                    "track \"" + name + "\" is " + direction.name().toLowerCase(java.util.Locale.ROOT)
                            + ", so it never delivers frames — this client sends on it. Register a"
                            + " frame handler on a recvonly track instead.",
                    null,
                    "onFrame",
                    null);
        }
    }

    private void requireKind(TrackKind required, String expectedHandler, String wrongHandler) {
        if (kind != required) {
            throw ReactorException.of(
                    ErrorCode.INVALID_STATE.code(),
                    "track \"" + name + "\" carries " + kind.name().toLowerCase(java.util.Locale.ROOT)
                            + ", so it needs " + expectedHandler + " rather than " + wrongHandler + ".",
                    null,
                    "onFrame",
                    null);
        }
    }

    @Override
    public String toString() {
        return "Track[" + name + " " + kind.name().toLowerCase(java.util.Locale.ROOT) + " "
                + direction.name().toLowerCase(java.util.Locale.ROOT) + "]";
    }
}
