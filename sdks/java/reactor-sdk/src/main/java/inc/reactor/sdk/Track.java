package inc.reactor.sdk;

import inc.reactor.sdk.internal.ClientPeer;
import java.util.Optional;
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
     * @throws ReactorException when this track is sendonly, or carries audio rather than video
     */
    public Subscription onFrame(VideoFrameHandler handler) {
        requireReceivable();
        requireKind(TrackKind.VIDEO, "VideoFrameHandler", "AudioFrameHandler");
        return peer.onVideoFrame(name, handler);
    }

    /**
     * Receives this track's audio frames.
     *
     * @param handler called inline on the FFI's delivery thread, with a frame valid only for the
     *     duration of the call
     * @return a subscription that removes the handler
     * @throws ReactorException when this track is sendonly, or carries video rather than audio
     */
    // Overloaded on two functional interfaces, so an implicitly-typed lambda cannot pick between
    // them: `onFrame(frame -> ...)` is ambiguous and `onFrame((AudioFrame frame) -> ...)` is not.
    // Kept anyway. Every Reactor SDK exposes one frame API for both kinds and lets Track.kind
    // decide which arrives; splitting this into onVideoFrame/onAudioFrame would make Java the one
    // binding with a different object model, to save callers one type name.
    @SuppressWarnings("overloads")
    public Subscription onFrame(AudioFrameHandler handler) {
        requireReceivable();
        requireKind(TrackKind.AUDIO, "AudioFrameHandler", "VideoFrameHandler");
        return peer.onAudioFrame(name, handler);
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
