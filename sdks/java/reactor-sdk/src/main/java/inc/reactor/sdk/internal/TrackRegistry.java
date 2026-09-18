package inc.reactor.sdk.internal;

import inc.reactor.sdk.AudioFrame;
import inc.reactor.sdk.AudioFrameHandler;
import inc.reactor.sdk.Subscription;
import inc.reactor.sdk.TrackDirection;
import inc.reactor.sdk.TrackKind;
import inc.reactor.sdk.VideoFrame;
import inc.reactor.sdk.VideoFrameHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * What the session declared, and who wants its frames.
 *
 * <p>The declarations are read from the FFI rather than accumulated from events, because that read
 * is the authority. It is cached, and the cache is where this gets interesting: parsing JSON is not
 * something to hold a lock across, so the read happens unlocked — and an event can invalidate the
 * answer while it is in flight. A cache that stored such an answer as "current" would put the older
 * list back with nothing left to invalidate it, and a newly declared track would stay invisible.
 *
 * <p>The generation counter is what prevents that, by being part of the key rather than a check:
 * an entry records the generation its read started at, so one that raced an invalidation is keyed
 * to a generation nothing matches and is never served.
 */
// Public because the facade in inc.reactor.sdk builds Tracks from what this returns. The
// package is not exported, so nothing outside this module can see it either way.
public final class TrackRegistry {

    private static final System.Logger LOG = System.getLogger(TrackRegistry.class.getName());

    /** One declared track, as {@code reactor_tracks} reports it plus the mid an event carried. */
    public record Declaration(
            String name,
            TrackKind kind,
            TrackDirection direction,
            @Nullable String mid) {}

    private record Cached(long generation, List<Declaration> declarations) {}

    /** Frame handlers for one track. */
    private static final class Handlers {
        final Events<VideoFrame> video = new Events<>("video frame");
        final Events<AudioFrame> audio = new Events<>("audio frame");
    }

    private final AtomicLong generation = new AtomicLong();
    private final Map<String, String> midsByName = new ConcurrentHashMap<>();
    private final Map<String, Handlers> handlers = new ConcurrentHashMap<>();
    private volatile @Nullable Cached cached;

    /** Something changed that the declarations may depend on. */
    void invalidate() {
        generation.incrementAndGet();
    }

    /**
     * Remembers the mid an on_track event carried, or forgets the one it no longer has.
     *
     * <p>A null mid is an answer, not an absence. The session sends one when a renegotiation has
     * taken the transceiver away, and keeping the previous value meant the rebuilt declarations
     * carried it: {@code Track.mid()} went on naming a transceiver that no longer existed, which a
     * caller can only find out by using it.
     */
    void noteMid(String name, @Nullable String mid) {
        if (mid != null) {
            midsByName.put(name, mid);
        } else {
            midsByName.remove(name);
        }
        invalidate();
    }

    /**
     * The declared tracks, in declaration order.
     *
     * @param read reads and parses {@code reactor_tracks}; called without any lock held
     * @return the declarations
     */
    List<Declaration> declarations(java.util.function.Supplier<List<Declaration>> read) {
        long taken = generation.get();
        Cached snapshot = cached;
        if (snapshot != null && snapshot.generation() == taken) {
            return snapshot.declarations();
        }
        List<Declaration> fresh = withMids(read.get());
        // Stored under the generation the read *started* at, not the one it finished at. That is
        // the whole guard: if something invalidated while the read was in flight, this entry is
        // keyed to a generation that no longer matches, so the next call misses it and reads again.
        // A stale answer can be returned once — it was current when it was asked for — but it can
        // never be served twice.
        cached = new Cached(taken, fresh);
        return fresh;
    }

    private List<Declaration> withMids(List<Declaration> declarations) {
        List<Declaration> merged = new ArrayList<>(declarations.size());
        for (Declaration declaration : declarations) {
            merged.add(new Declaration(
                    declaration.name(),
                    declaration.kind(),
                    declaration.direction(),
                    midsByName.get(declaration.name())));
        }
        return List.copyOf(merged);
    }

    Subscription onVideoFrame(String track, VideoFrameHandler handler) {
        return handlersFor(track).video.add(handler::onFrame);
    }

    Subscription onAudioFrame(String track, AudioFrameHandler handler) {
        return handlersFor(track).audio.add(handler::onFrame);
    }

    /** Delivers a video frame, or drops it when no track by that name has handlers. */
    void deliverVideo(String track, VideoFrame frame) {
        deliver(track, frame, existing -> existing.video.emit(frame), existing -> existing.video.size());
    }

    /** Delivers an audio frame, or drops it when no track by that name has handlers. */
    void deliverAudio(String track, AudioFrame frame) {
        deliver(track, frame, existing -> existing.audio.emit(frame), existing -> existing.audio.size());
    }

    private void deliver(
            String track, Object frame, Consumer<Handlers> emit, java.util.function.ToIntFunction<Handlers> count) {
        Handlers existing = handlers.get(track);
        if (existing == null || count.applyAsInt(existing) == 0) {
            // A frame arriving for a track nobody is listening to, or one the session never
            // declared. Dropping it is right; saying so is what makes a silent nothing debuggable.
            LOG.log(
                    System.Logger.Level.DEBUG,
                    () -> "dropped a frame for track \"" + track + "\": nothing is listening to it");
            return;
        }
        emit.accept(existing);
    }

    private Handlers handlersFor(String track) {
        return handlers.computeIfAbsent(track, ignored -> new Handlers());
    }

    void clear() {
        handlers.clear();
        midsByName.clear();
        invalidate();
    }
}
