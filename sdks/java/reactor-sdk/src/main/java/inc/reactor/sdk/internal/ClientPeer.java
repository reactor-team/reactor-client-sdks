package inc.reactor.sdk.internal;

import inc.reactor.sdk.ConnectionStatus;
import inc.reactor.sdk.ErrorCode;
import inc.reactor.sdk.Media;
import inc.reactor.sdk.PublishState;
import inc.reactor.sdk.ReactorException;
import inc.reactor.sdk.ReactorOptions;
import inc.reactor.sdk.ReactorSdk;
import inc.reactor.sdk.Subscription;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Everything a client owns that is not its public surface.
 *
 * <p>Deliberately holds no reference to the {@code Reactor} facade. The FFI keeps a raw pointer to
 * an upcall stub, the stub's method handle binds this object, and the arena keeps the stub alive —
 * so anything reachable from here is reachable from native code and cannot be collected while the
 * arena lives. A path from here to the facade would mean the facade is never collected, its
 * {@code Cleaner} never runs, and the native handle is never destroyed: the exact leak the cleaner
 * exists to prevent.
 */
public final class ClientPeer implements Runnable {

    private static final MethodHandle ON_STATUS;
    private static final MethodHandle ON_ERROR;
    private static final MethodHandle ON_MESSAGE;
    private static final MethodHandle ON_RUNTIME_MESSAGE;
    private static final MethodHandle ON_TRACK;
    private static final MethodHandle ON_CAPABILITIES;
    private static final MethodHandle ON_SESSION_ID;
    private static final MethodHandle ON_FRAME;
    private static final MethodHandle ON_AUDIO;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodType oneString = MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class);
            MethodType twoStrings =
                    MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class);
            ON_STATUS = lookup.findVirtual(ClientPeer.class, "onStatus", oneString);
            ON_ERROR = lookup.findVirtual(ClientPeer.class, "onError", oneString);
            ON_MESSAGE = lookup.findVirtual(ClientPeer.class, "onMessage", oneString);
            ON_RUNTIME_MESSAGE = lookup.findVirtual(ClientPeer.class, "onRuntimeMessage", oneString);
            ON_TRACK = lookup.findVirtual(ClientPeer.class, "onTrack", twoStrings);
            ON_CAPABILITIES = lookup.findVirtual(ClientPeer.class, "onCapabilities", oneString);
            ON_SESSION_ID = lookup.findVirtual(ClientPeer.class, "onSessionId", oneString);
            ON_FRAME = lookup.findVirtual(
                    ClientPeer.class,
                    "onFrame",
                    MethodType.methodType(
                            void.class,
                            MemorySegment.class,
                            MemorySegment.class,
                            int.class,
                            int.class,
                            long.class,
                            long.class,
                            MemorySegment.class,
                            int.class,
                            MemorySegment.class));
            ON_AUDIO = lookup.findVirtual(
                    ClientPeer.class,
                    "onAudio",
                    MethodType.methodType(
                            void.class,
                            MemorySegment.class,
                            MemorySegment.class,
                            int.class,
                            int.class,
                            int.class,
                            MemorySegment.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** A declared track, as the FFI announces it. The object model for these arrives with tracks. */
    public record TrackAnnouncement(String name, @Nullable String mid) {}

    private final Ffi ffi;
    private final Arena arena;
    private final MemorySegment handle;
    private final Completions completions;
    private final Dispatch dispatch;

    final Events<ConnectionStatus> statusEvents = new Events<>("status");
    final Events<ReactorException> errorEvents = new Events<>("error");
    final Events<String> messageEvents = new Events<>("message");
    final Events<String> runtimeMessageEvents = new Events<>("runtimeMessage");
    final Events<TrackAnnouncement> trackEvents = new Events<>("track");
    final Events<String> capabilitiesEvents = new Events<>("capabilities");
    final Events<Optional<String>> sessionIdEvents = new Events<>("sessionId");

    private final TrackRegistry tracks = new TrackRegistry();
    private final java.util.Map<String, PublishState> publishStates = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The attempt each track's publish state belongs to.
     *
     * <p>Per track, and replaced on every new attempt — not one counter for the client. A single
     * generation moved only when the session went away, which left two orderings wrong: a
     * successful unpublish does not move it, so an older publish's success restored PUBLISHED
     * afterwards; and two attempts on one track share a generation, so a slow failure could
     * overwrite a newer success. Both end the same way — a slot the SDK calls published with
     * nothing behind it, and frames the FFI drops in silence.
     *
     * <p>Guarded by {@link #publishLock} together with the state itself, so that deciding an
     * answer is still current and acting on it cannot be split. Native calls stay outside that
     * lock.
     */
    private final java.util.Map<String, Long> publishTokens = new java.util.HashMap<>();

    private final java.util.concurrent.atomic.AtomicLong nextPublishToken =
            new java.util.concurrent.atomic.AtomicLong();

    private final Object publishLock = new Object();

    private static final System.Logger LOG = System.getLogger(ClientPeer.class.getName());

    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
    private final Object inFlightIdle = new Object();

    /**
     * How many native calls this thread is inside.
     *
     * <p>For the one case the counter above cannot answer: a callback that closes the client. The
     * FFI can run a completion inside the call that started it, so `close()` from there would be a
     * thread waiting for a call it is itself making. It has to defer instead.
     */
    private final ThreadLocal<int[]> leasesHere = ThreadLocal.withInitial(() -> new int[1]);

    /** Set when close could not wait, so the last call to return owes the destroy. */
    private final AtomicBoolean destroyWhenQuiet = new AtomicBoolean();

    private final AtomicBoolean teardownStarted = new AtomicBoolean();

    private ClientPeer(Ffi ffi, Arena arena, ReactorOptions options) {
        this.ffi = ffi;
        this.arena = arena;
        this.completions = new Completions(arena);
        this.dispatch = Dispatch.of(options.dispatcher());
        this.handle = createHandle(options);
    }

    /**
     * Creates the native client.
     *
     * @param options what to connect to
     * @return the peer
     */
    public static ClientPeer create(ReactorOptions options) {
        // The process-wide library, not one bound to this client's arena. Closing a client would
        // otherwise unload it, and `reactor_destroy` does not stop the core's shared runtime — nor
        // does it reach a detached download, which the header documents as outliving the handle.
        // Both would have been left executing code that is no longer mapped.
        return create(options, arena -> NativeLibrary.shared());
    }

    /**
     * Creates the native client against a given library.
     *
     * <p>The loader is a parameter so the tests can hand over a fake one. Everything below it — the
     * arena, the callbacks struct, teardown — is the same code either way, which is the point: a
     * test that swapped out the peer instead would prove nothing about the peer.
     *
     * @param options what to connect to
     * @param loader binds the ABI within a lifetime
     * @return the peer
     */
    public static ClientPeer create(ReactorOptions options, java.util.function.Function<Arena, Ffi> loader) {
        // Shared, not confined: every callback arrives on a thread this one has never met, and a
        // confined arena throws WrongThreadException at the first access from any other.
        Arena arena = Arena.ofShared();
        try {
            return new ClientPeer(loader.apply(arena), arena, options);
        } catch (RuntimeException | Error failed) {
            // Nothing was handed to the FFI yet, so nothing can call back: the arena is safe to
            // release, and leaking it here would be a leak on every failed creation.
            arena.close();
            throw failed;
        }
    }

    private MemorySegment createHandle(ReactorOptions options) {
        MemorySegment callbacks = arena.allocate(Ffi.Callbacks.LAYOUT);
        setCallback(callbacks, "on_status", stub(ON_STATUS, Ffi.Callbacks.ON_STATUS));
        setCallback(callbacks, "on_error", stub(ON_ERROR, Ffi.Callbacks.ON_ERROR));
        setCallback(callbacks, "on_message", stub(ON_MESSAGE, Ffi.Callbacks.ON_MESSAGE));
        setCallback(callbacks, "on_runtime_message", stub(ON_RUNTIME_MESSAGE, Ffi.Callbacks.ON_RUNTIME_MESSAGE));
        setCallback(callbacks, "on_track", stub(ON_TRACK, Ffi.Callbacks.ON_TRACK));
        setCallback(callbacks, "on_capabilities", stub(ON_CAPABILITIES, Ffi.Callbacks.ON_CAPABILITIES));
        setCallback(callbacks, "on_session_id", stub(ON_SESSION_ID, Ffi.Callbacks.ON_SESSION_ID));
        setCallback(callbacks, "on_frame", stub(ON_FRAME, Ffi.Callbacks.ON_FRAME));
        setCallback(callbacks, "on_audio", stub(ON_AUDIO, Ffi.Callbacks.ON_AUDIO));
        // userdata is unused: each stub is already bound to this peer, so there is nothing to
        // look up when one fires.
        setCallback(callbacks, "userdata", MemorySegment.NULL);

        // Every argument goes into a typed local first. invokeExact is signature-polymorphic: it
        // builds the call descriptor from the *static* types at the call site, and a conditional
        // expression in that position infers as Object — which does not match, and fails at the
        // call with a WrongMethodTypeException rather than at compile time.
        MemorySegment apiUrl = arena.allocateFrom(options.apiUrl());
        MemorySegment modelName = arena.allocateFrom(options.modelName());
        String token = options.jwt();
        MemorySegment jwt = token == null ? MemorySegment.NULL : arena.allocateFrom(token);
        int local = options.local() ? 1 : 0;
        // 0 is the synthetic audio device module. Nothing opens a microphone because a model
        // happened to declare a sendonly audio track.
        int admMode = 0;
        MemorySegment sdkVersion = arena.allocateFrom(ReactorSdk.version());
        MemorySegment sdkType = arena.allocateFrom("java");

        MemorySegment created;
        try {
            created = (MemorySegment) ffi.handle(Ffi.Symbol.CREATE_WITH_ADM)
                    .invokeExact(apiUrl, modelName, jwt, local, callbacks, admMode, sdkVersion, sdkType);
        } catch (Throwable t) {
            throw new IllegalStateException("reactor_create_with_adm could not be called", t);
        }
        if (created.equals(MemorySegment.NULL)) {
            throw ReactorException.of(
                    ReactorException.INTERNAL_ERROR, "the native client could not be allocated", null, "create", null);
        }
        return created;
    }

    private MemorySegment stub(MethodHandle target, java.lang.foreign.FunctionDescriptor descriptor) {
        return Upcalls.stub(target.bindTo(this), descriptor, arena);
    }

    /**
     * Writes one field of the callbacks struct, by name.
     *
     * <p>By name rather than by offset: these are positional slots, and a field written to the
     * wrong one hands the FFI one callback where it expects another — which nothing complains about
     * until it is called.
     */
    private static void setCallback(MemorySegment callbacks, String field, MemorySegment value) {
        long offset = Ffi.Callbacks.LAYOUT.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement(field));
        callbacks.set(java.lang.foreign.ValueLayout.ADDRESS, offset, value);
    }

    // ── Operations ──────────────────────────────────────────────────────────

    /**
     * @param sessionId a session to adopt, or {@code null} to create one
     * @param connectionId a connection id a backend registered, or {@code null} to register a new one
     * @return settles when the transport is up
     */
    public CompletableFuture<Void> connect(@Nullable String sessionId, @Nullable Integer connectionId) {
        return call("connect", (completion, userdata) -> {
            // Confined and closed at the end of the call: these arguments are read during the
            // call and never kept, so the narrowest possible lifetime is the right one.
            try (Arena call = Arena.ofConfined()) {
                MemorySegment id = sessionId == null ? MemorySegment.NULL : call.allocateFrom(sessionId);
                MemorySegment connection = connectionId == null
                        ? MemorySegment.NULL
                        : call.allocateFrom(java.lang.foreign.ValueLayout.JAVA_INT, connectionId);
                invoke(Ffi.Symbol.CONNECT, handle, id, connection, completion, userdata);
            }
        });
    }

    /** @return settles when the session has been left */
    public CompletableFuture<Void> disconnect() {
        return call(
                "disconnect", (completion, userdata) -> invoke(Ffi.Symbol.DISCONNECT, handle, completion, userdata));
    }

    /** @return settles when the transport has been re-established on the same session */
    public CompletableFuture<Void> reconnect() {
        return call("reconnect", (completion, userdata) -> invoke(Ffi.Symbol.RECONNECT, handle, completion, userdata));
    }

    /** @return the current status */
    public ConnectionStatus status() {
        acquireHandle("status");
        try {
            MemorySegment text = (MemorySegment) ffi.handle(Ffi.Symbol.STATUS).invokeExact(handle);
            return ConnectionStatus.of(NativeStrings.staticRef(text)).orElse(ConnectionStatus.DISCONNECTED);
        } catch (Throwable t) {
            throw new IllegalStateException("reactor_status could not be called", t);
        } finally {
            releaseHandle();
        }
    }

    /** @return the session id, or empty when there is no session */
    public Optional<String> sessionId() {
        acquireHandle("sessionId");
        try {
            MemorySegment owned =
                    (MemorySegment) ffi.handle(Ffi.Symbol.SESSION_ID).invokeExact(handle);
            return Optional.ofNullable(NativeStrings.takeOwned(owned, ffi.handle(Ffi.Symbol.FREE_STRING)));
        } catch (Throwable t) {
            throw new IllegalStateException("reactor_session_id could not be called", t);
        } finally {
            releaseHandle();
        }
    }

    /** @return how many operations are still waiting for a completion */
    public int pendingOperations() {
        return completions.pendingCount();
    }

    // ── Teardown ────────────────────────────────────────────────────────────

    /**
     * Destroys the native client and releases — or deliberately leaks — what it was called back
     * through.
     *
     * <p>Idempotent. Runs the blocking part on a platform thread: {@code reactor_destroy} blocks
     * until callbacks quiesce, and a blocking downcall pins a virtual thread's carrier for the
     * whole wait.
     */
    @Override
    public void run() {
        close();
    }

    /** Destroys the native client. Safe to call twice, and from an event handler. */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        completions.settleAll(
                ReactorException.of(ErrorCode.ABORTED.code(), "the client was closed", null, "close", null));
        tracks.clear();
        synchronized (publishLock) {
            publishTokens.clear();
            publishStates.clear();
        }

        dispatch.shutdown();
        // Between the flag above and the destroy, a call that was already past its own check may
        // still be inside the FFI holding this handle, and freeing it under that call is the
        // use-after-free this guard exists to prevent. So the destroy waits — and when it cannot
        // wait, it is scheduled rather than skipped.
        //
        // Skipping it outright was the previous answer and it was half of one: an interrupted or
        // reentrant close left reactor_destroy never called at all, so the session stayed alive on
        // the platform, its tasks kept running and the handle leaked for the life of the process.
        // Deferring has to mean later, not never.
        if (awaitNoCallsInFlight()) {
            finishTeardown();
            return;
        }
        destroyWhenQuiet.set(true);
        // The last call may have returned between the wait giving up and the flag going up, in
        // which case nobody is left to notice it. Checked here so that ordering cannot strand it.
        if (inFlight.get() == 0) {
            finishTeardown();
        }
    }

    /**
     * Destroys the handle and disposes of the arena. Runs once, whoever gets here first.
     *
     * <p>On its own thread when the last call releases, because that release happens on whatever
     * thread was making the call — an FFI callback thread, or the one a user closed from inside a
     * handler — and reactor_destroy blocks.
     */
    private void finishTeardown() {
        if (!teardownStarted.compareAndSet(false, true)) {
            return;
        }
        int quiesced = destroyOffVirtualThread();
        if (quiesced == 0) {
            arena.close();
        } else {
            // -1: a callback is still executing. The handle is gone either way, but the library
            // still holds pointers into this arena, so closing it would be a jump into freed code.
            OrphanedArenas.keepForever(arena);
        }
    }

    private int destroyOffVirtualThread() {
        if (!Thread.currentThread().isVirtual()) {
            return destroy();
        }
        // Joining a platform thread parks this virtual thread properly, where calling the blocking
        // downcall directly would pin its carrier for the length of the wait.
        try {
            var result = new int[1];
            Thread worker = Thread.ofPlatform().name("reactor-destroy").start(() -> result[0] = destroy());
            worker.join();
            return result[0];
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            // Nothing is known about whether callbacks quiesced, so assume the worse answer.
            return -1;
        }
    }

    private int destroy() {
        try {
            return (int) ffi.handle(Ffi.Symbol.DESTROY).invokeExact(handle);
        } catch (Throwable t) {
            // Nothing useful is left to do, and throwing out of close() would hide whatever the
            // caller was closing because of.
            return -1;
        }
    }

    /** Whether this peer has been closed. */
    public boolean isClosed() {
        return closed.get();
    }

    // ── Event registration ──────────────────────────────────────────────────

    /** @return a subscription that removes the handler */
    public Subscription onStatus(Consumer<ConnectionStatus> handler) {
        return statusEvents.add(handler);
    }

    /** @return a subscription that removes the handler */
    public Subscription onError(Consumer<ReactorException> handler) {
        return errorEvents.add(handler);
    }

    /** @return a subscription that removes the handler */
    public Subscription onMessage(Consumer<String> handler) {
        return messageEvents.add(handler);
    }

    /** @return a subscription that removes the handler */
    public Subscription onRuntimeMessage(Consumer<String> handler) {
        return runtimeMessageEvents.add(handler);
    }

    /** @return a subscription that removes the handler */
    public Subscription onTrack(Consumer<TrackAnnouncement> handler) {
        return trackEvents.add(handler);
    }

    /** @return a subscription that removes the handler */
    public Subscription onCapabilities(Consumer<String> handler) {
        return capabilitiesEvents.add(handler);
    }

    /** @return a subscription that removes the handler */
    public Subscription onSessionId(Consumer<Optional<String>> handler) {
        return sessionIdEvents.add(handler);
    }

    // ── Callbacks, on the FFI's own threads ─────────────────────────────────

    private void onStatus(MemorySegment status, MemorySegment userdata) {
        String text = NativeStrings.borrow(status);
        ConnectionStatus parsed = text == null
                ? ConnectionStatus.DISCONNECTED
                : ConnectionStatus.of(text).orElse(ConnectionStatus.DISCONNECTED);
        // Leaving `ready` can change what is declared, so the cached list stops being current.
        tracks.invalidate();
        if (parsed != ConnectionStatus.READY) {
            // A reconnect resumes recvonly tracks and nothing else, so a slot published before one
            // is not published after it. Remembering otherwise would let a caller push into a slot
            // with no sender behind it and see nothing arrive.
            synchronized (publishLock) {
                publishTokens.clear();
                publishStates.clear();
            }
        }
        dispatch.run(() -> statusEvents.emit(parsed));
    }

    private void onError(MemorySegment errorJson, MemorySegment userdata) {
        ReactorException error = ErrorPayloads.parse(NativeStrings.borrow(errorJson), null);
        dispatch.run(() -> errorEvents.emit(error));
    }

    private void onMessage(MemorySegment msgJson, MemorySegment userdata) {
        String copy = NativeStrings.borrow(msgJson);
        if (copy != null) {
            dispatch.run(() -> messageEvents.emit(copy));
        }
    }

    private void onRuntimeMessage(MemorySegment msgJson, MemorySegment userdata) {
        String copy = NativeStrings.borrow(msgJson);
        if (copy != null) {
            dispatch.run(() -> runtimeMessageEvents.emit(copy));
        }
    }

    private void onTrack(MemorySegment name, MemorySegment mid, MemorySegment userdata) {
        String trackName = NativeStrings.borrow(name);
        String trackMid = NativeStrings.borrow(mid);
        if (trackName != null) {
            tracks.noteMid(trackName, trackMid);
            TrackAnnouncement announcement = new TrackAnnouncement(trackName, trackMid);
            dispatch.run(() -> trackEvents.emit(announcement));
        }
    }

    private void onCapabilities(MemorySegment capsJson, MemorySegment userdata) {
        String copy = NativeStrings.borrow(capsJson);
        if (copy != null) {
            tracks.invalidate();
            dispatch.run(() -> capabilitiesEvents.emit(copy));
        }
    }

    private void onSessionId(MemorySegment sessionId, MemorySegment userdata) {
        Optional<String> id = Optional.ofNullable(NativeStrings.borrow(sessionId));
        dispatch.run(() -> sessionIdEvents.emit(id));
    }

    // ── Media, inline on the FFI's own delivery threads ─────────────────────

    /**
     * A video frame, delivered on the thread the FFI decoded it on.
     *
     * <p>Not marshalled to the dispatcher, on purpose. The FFI keeps only the newest frame while
     * this runs, so blocking here drops frames — a bounded cost. Handing them to a queue instead
     * would trade that for unbounded latency and memory.
     */
    @SuppressWarnings("restricted") // reinterpret: bounded to the size the FFI documents
    private void onFrame(
            MemorySegment trackName,
            MemorySegment data,
            int width,
            int height,
            long frameId,
            long timestampUs,
            MemorySegment userData,
            int userDataLength,
            MemorySegment userdata) {
        String track = NativeStrings.borrow(trackName);
        if (track == null || track.isEmpty()) {
            // Empty means the transceiver could not be matched to a declared track. There is
            // nothing to deliver it to.
            return;
        }
        long bytes = (long) width * height * 4;
        byte[] tag = userDataLength > 0 && !userData.equals(MemorySegment.NULL)
                ? userData.reinterpret(userDataLength).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)
                : null;
        // Confined to this thread and closed when the handler returns. That is what makes a frame
        // kept past the callback throw IllegalStateException instead of reading memory the FFI has
        // already reused.
        try (Arena frame = Arena.ofConfined()) {
            MemorySegment pixels = data.reinterpret(bytes, frame, null).asReadOnly();
            tracks.deliverVideo(track, Media.videoFrame(pixels, width, height, frameId, timestampUs, tag));
        }
    }

    /** An audio frame, on the FFI's own thread, for the same reason as {@link #onFrame}. */
    @SuppressWarnings("restricted") // reinterpret: bounded to the sample count the FFI reports
    private void onAudio(
            MemorySegment trackName,
            MemorySegment samples,
            int sampleCount,
            int sampleRate,
            int channels,
            MemorySegment userdata) {
        String track = NativeStrings.borrow(trackName);
        if (track == null || track.isEmpty()) {
            return;
        }
        try (Arena frame = Arena.ofConfined()) {
            MemorySegment pcm = samples.reinterpret((long) sampleCount * Short.BYTES, frame, null)
                    .asReadOnly();
            tracks.deliverAudio(track, Media.audioFrame(pcm, sampleCount, sampleRate, channels));
        }
    }

    // ── Tracks ──────────────────────────────────────────────────────────────

    /**
     * The tracks the session declared, in declaration order.
     *
     * @return the declarations
     */
    public List<TrackRegistry.Declaration> trackDeclarations() {
        requireOpen("tracks");
        return tracks.declarations(this::readTracks);
    }

    /**
     * @param track the declared name
     * @param handler what to call with its video frames
     * @return a subscription that removes the handler
     */
    public Subscription onVideoFrame(String track, inc.reactor.sdk.VideoFrameHandler handler) {
        return tracks.onVideoFrame(track, handler);
    }

    /**
     * @param track the declared name
     * @param handler what to call with its audio frames
     * @return a subscription that removes the handler
     */
    public Subscription onAudioFrame(String track, inc.reactor.sdk.AudioFrameHandler handler) {
        return tracks.onAudioFrame(track, handler);
    }

    /** @return the names of the tracks that are currently paused */
    public Set<String> pausedTracks() {
        requireOpen("pausedTracks");
        String json = readOwnedString(Ffi.Symbol.PAUSED_TRACKS, "reactor_paused_tracks");
        if (json == null) {
            return Set.of();
        }
        Object parsed = Json.parse(json);
        if (!(parsed instanceof List<?> names)) {
            return Set.of();
        }
        Set<String> paused = new java.util.LinkedHashSet<>();
        for (Object name : names) {
            if (name instanceof String text) {
                paused.add(text);
            }
        }
        return java.util.Collections.unmodifiableSet(paused);
    }

    private List<TrackRegistry.Declaration> readTracks() {
        String json = readOwnedString(Ffi.Symbol.TRACKS, "reactor_tracks");
        if (json == null) {
            return List.of();
        }
        Object parsed;
        try {
            parsed = Json.parse(json);
        } catch (RuntimeException malformed) {
            throw ReactorException.of(
                    ErrorCode.DECODE_FAILED.code(),
                    "the track list could not be read: " + malformed.getMessage(),
                    null,
                    "tracks",
                    null);
        }
        if (!(parsed instanceof List<?> entries)) {
            return List.of();
        }
        // Built as a sequence, never a map. A name-keyed collection would sort these and silently
        // renumber what tracks().get(0) means for every caller.
        List<TrackRegistry.Declaration> declarations = new java.util.ArrayList<>(entries.size());
        for (Object entry : entries) {
            if (!(entry instanceof java.util.Map<?, ?> fields)) {
                continue;
            }
            Object name = fields.get("name");
            Object kind = fields.get("kind");
            Object direction = fields.get("direction");
            if (!(name instanceof String trackName)
                    || !(kind instanceof String kindText)
                    || !(direction instanceof String directionText)) {
                continue;
            }
            inc.reactor.sdk.TrackKind.of(kindText)
                    .ifPresent(parsedKind -> inc.reactor.sdk.TrackDirection.of(directionText)
                            .ifPresent(parsedDirection -> declarations.add(
                                    new TrackRegistry.Declaration(trackName, parsedKind, parsedDirection, null))));
        }
        return List.copyOf(declarations);
    }

    /**
     * Reads a string the FFI hands over ownership of, and frees it.
     *
     * <p>Under the same lease every other native call takes. Two calls happen here — the read and
     * the free — and both name the handle's library, so a `close()` landing between them frees the
     * string through a handle that is already gone. The copy is made before the lease is given up,
     * which is what makes the returned String safe to hold afterwards.
     */
    private @Nullable String readOwnedString(Ffi.Symbol symbol, String what) {
        acquireHandle(what);
        try {
            MemorySegment owned = (MemorySegment) ffi.handle(symbol).invokeExact(handle);
            return NativeStrings.takeOwned(owned, ffi.handle(Ffi.Symbol.FREE_STRING));
        } catch (Throwable t) {
            throw new IllegalStateException(what + " could not be called", t);
        } finally {
            releaseHandle();
        }
    }

    // ── Sending ─────────────────────────────────────────────────────────────

    /**
     * Asks for a sender behind a sendonly track.
     *
     * @param name the declared track
     * @return settles when the track is publishing
     */
    public CompletableFuture<Void> publish(String name) {
        long attempt = nextPublishToken.incrementAndGet();
        synchronized (publishLock) {
            // The token and the state together: a completion that reads one and acts on the other
            // must never see them disagree.
            publishTokens.put(name, attempt);
            publishStates.put(name, PublishState.PUBLISHING);
        }
        CompletableFuture<Void> published = call("publish_track", (completion, userdata) -> {
            try (Arena call = Arena.ofConfined()) {
                invoke(Ffi.Symbol.PUBLISH_TRACK, handle, call.allocateFrom(name), completion, userdata);
            }
        });
        return published.whenComplete((ignored, failure) -> {
            synchronized (publishLock) {
                Long current = publishTokens.get(name);
                if (current == null || current != attempt) {
                    // Not the attempt this track is on any more: the session went away, the track
                    // was unpublished, or a newer publish replaced it. Whichever, this answer
                    // describes a state that no longer exists, and writing it back would re-arm a
                    // slot with nothing behind it.
                    return;
                }
                // Only a success means there is a sender. A failed publish goes back to
                // unpublished so the caller can retry, rather than leaving a slot that reports
                // itself ready to push.
                publishStates.put(name, failure == null ? PublishState.PUBLISHED : PublishState.UNPUBLISHED);
            }
        });
    }

    /**
     * Tells the session a sendonly track is finished. Synchronous: no round trip, only a local
     * status check and a fire-and-forget notification.
     *
     * @param name the declared track
     * @throws ReactorException when the FFI refused, leaving the track publishable so a retry means
     *     something
     */
    public void unpublish(String name) {
        acquireHandle("unpublish");
        String errorJson;
        try (Arena call = Arena.ofConfined()) {
            MemorySegment track = call.allocateFrom(name);
            MemorySegment returned =
                    (MemorySegment) ffi.handle(Ffi.Symbol.UNPUBLISH_TRACK).invokeExact(handle, track);
            errorJson = NativeStrings.takeOwned(returned, ffi.handle(Ffi.Symbol.FREE_STRING));
        } catch (Throwable t) {
            throw new IllegalStateException("reactor_unpublish_track could not be called", t);
        } finally {
            // Two native calls here — the unpublish and the free of the string it returns — and a
            // close between them would free that string through a handle that is already gone.
            releaseHandle();
        }
        if (errorJson != null) {
            // The state is deliberately left alone. Clearing it on a failure would make the
            // unpublish unretryable: the next call would refuse because nothing looks published.
            throw ErrorPayloads.parse(errorJson, "unpublish_track");
        }
        synchronized (publishLock) {
            // The token goes with it. Without this, a publish still in flight from before could
            // settle afterwards and put the track back to PUBLISHED, with nothing behind it.
            publishTokens.remove(name);
            publishStates.remove(name);
        }
    }

    /**
     * @param name the declared track
     * @return settles when the track is paused
     */
    public CompletableFuture<Void> pause(String name) {
        return call("pause_track", (completion, userdata) -> {
            try (Arena call = Arena.ofConfined()) {
                invoke(Ffi.Symbol.PAUSE_TRACK, handle, call.allocateFrom(name), completion, userdata);
            }
        });
    }

    /**
     * @param name the declared track
     * @return settles when the track is producing again
     */
    public CompletableFuture<Void> resume(String name) {
        return call("resume_track", (completion, userdata) -> {
            try (Arena call = Arena.ofConfined()) {
                invoke(Ffi.Symbol.RESUME_TRACK, handle, call.allocateFrom(name), completion, userdata);
            }
        });
    }

    /**
     * @param name the declared track
     * @return whether it has a sender, is getting one, or has none
     */
    public PublishState publishState(String name) {
        return publishStates.getOrDefault(name, PublishState.UNPUBLISHED);
    }

    /**
     * Pushes one BGRA video frame.
     *
     * @param name the declared track
     * @param bgra {@code width * height * 4} bytes
     * @param width frame width
     * @param height frame height
     * @param userData a tag for the far end, or {@code null}
     * @param captureTimeUs when this was captured on the engine clock, or {@code null} for now
     */
    public void pushVideoFrame(
            String name, byte[] bgra, int width, int height, byte @Nullable [] userData, @Nullable Long captureTimeUs) {
        requireOpen("pushFrame");
        // Copied into native memory for the call and released at the end of it. A copy per frame is
        // 8 MB at 1080p; Linker.Option.critical would let the heap array be passed directly, and is
        // the obvious thing to measure before reaching for it — it forbids blocking and calling
        // back, which this call would have to be shown not to do.
        try (Arena call = Arena.ofConfined()) {
            MemorySegment track = call.allocateFrom(name);
            MemorySegment pixels = call.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, bgra);
            if (userData == null && captureTimeUs == null) {
                invokeMixed(Ffi.Symbol.PUSH_VIDEO_FRAME, handle, track, pixels, width, height);
                return;
            }
            MemorySegment tag = userData == null
                    ? MemorySegment.NULL
                    : call.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, userData);
            int tagLength = userData == null ? 0 : userData.length;
            if (captureTimeUs == null) {
                invokeMixed(
                        Ffi.Symbol.PUSH_VIDEO_FRAME_WITH_METADATA,
                        handle,
                        track,
                        pixels,
                        width,
                        height,
                        tag,
                        tagLength);
                return;
            }
            invokeMixed(
                    Ffi.Symbol.PUSH_VIDEO_FRAME_WITH_METADATA_AT,
                    handle,
                    track,
                    pixels,
                    width,
                    height,
                    tag,
                    tagLength,
                    captureTimeUs.longValue());
        }
    }

    /**
     * Pushes interleaved 16-bit PCM.
     *
     * @param name the declared track
     * @param pcm the samples, across every channel
     * @param sampleRate samples per second
     * @param channels how many channels the samples are interleaved across
     */
    public void pushAudioFrame(String name, short[] pcm, int sampleRate, int channels) {
        requireOpen("pushFrame");
        try (Arena call = Arena.ofConfined()) {
            invokeMixed(
                    Ffi.Symbol.PUSH_AUDIO_FRAME,
                    handle,
                    call.allocateFrom(name),
                    call.allocateFrom(java.lang.foreign.ValueLayout.JAVA_SHORT, pcm),
                    pcm.length / Math.max(channels, 1),
                    sampleRate,
                    channels);
        }
    }

    /**
     * The engine's own clock, for timestamping pushed frames.
     *
     * @return microseconds on the clock the FFI compares capture times against
     */
    public long timeMicros() {
        try {
            return (long) ffi.handle(Ffi.Symbol.TIME_MICROS).invokeExact();
        } catch (Throwable t) {
            throw new IllegalStateException("reactor_time_micros could not be called", t);
        }
    }

    /**
     * @param minBps lower bound, or a negative value for none
     * @param startBps where to start, or a negative value for none
     * @param maxBps upper bound, or a negative value for none
     * @return settles when the bounds are applied
     */
    public CompletableFuture<Void> setBitrate(int minBps, int startBps, int maxBps) {
        return call(
                "set_bitrate",
                (completion, userdata) ->
                        invokeMixed(Ffi.Symbol.SET_BITRATE, handle, minBps, startBps, maxBps, completion, userdata));
    }

    /**
     * @param name the declared track
     * @param minBps lower bound, or a negative value for none
     * @param maxBps upper bound, or a negative value for none
     * @return settles when the bounds are applied
     */
    public CompletableFuture<Void> setTrackBitrate(String name, int minBps, int maxBps) {
        return call("set_track_bitrate", (completion, userdata) -> {
            try (Arena call = Arena.ofConfined()) {
                invokeMixed(
                        Ffi.Symbol.SET_TRACK_BITRATE,
                        handle,
                        call.allocateFrom(name),
                        minBps,
                        maxBps,
                        completion,
                        userdata);
            }
        });
    }

    /**
     * The same as {@link #invoke}, for calls whose arguments are not all segments.
     *
     * <p>And it takes the same lease, for the same reason. It was left out of that change and every
     * frame push, every bitrate request and every download went through here — past the guard,
     * straight at a handle `close()` was free to destroy underneath them.
     */
    private void invokeMixed(Ffi.Symbol symbol, Object... arguments) {
        acquireHandle(symbol.cName());
        try {
            ffi.handle(symbol).invokeWithArguments(arguments);
        } catch (Throwable t) {
            throw new IllegalStateException(symbol.cName() + " could not be called", t);
        } finally {
            releaseHandle();
        }
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private CompletableFuture<Void> call(String operation, Completions.Invoker invoke) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (closed.get()) {
            future.completeExceptionally(ReactorException.of(
                    ErrorCode.INVALID_STATE.code(),
                    operation + " was called on a closed client",
                    null,
                    operation,
                    null));
            return future;
        }
        Completions.Ticket ticket = completions.register(operation, json -> null, future);
        try {
            invoke.call(ticket.callback(), ticket.userdata());
        } catch (RuntimeException notStarted) {
            completions.settleAll(ReactorException.of(
                    ReactorException.INTERNAL_ERROR, "the native call failed to start", null, operation, null));
            throw notStarted;
        }
        return future;
    }

    private void invoke(Ffi.Symbol symbol, MemorySegment... arguments) {
        acquireHandle(symbol.cName());
        try {
            ffi.handle(symbol).invokeWithArguments((Object[]) arguments);
        } catch (Throwable t) {
            throw new IllegalStateException(symbol.cName() + " could not be called", t);
        } finally {
            releaseHandle();
        }
    }

    /**
     * Holds the native handle open for the duration of one call.
     *
     * <p>`closed` on its own answered a question that had already stopped being true by the time
     * the answer was used: a thread could pass the check, and `close()` on another thread could
     * free the handle through `reactor_destroy` before that thread reached the FFI. The call then
     * ran against freed memory — a use-after-free in a client documented as thread-safe.
     *
     * <p>So a call announces itself before it looks. Incrementing first and checking after is what
     * makes the two orderings both safe: a call that got in before `close()` read the counter is
     * one `close()` waits for, and a call that arrives after the flag is set backs out without
     * touching anything.
     *
     * @param what names the operation, for the refusal
     * @return a lease the caller must release
     */
    private void acquireHandle(String what) {
        inFlight.incrementAndGet();
        leasesHere.get()[0]++;
        if (closed.get()) {
            releaseHandle();
            throw ReactorException.of(
                    ErrorCode.INVALID_STATE.code(), what + " was called on a closed client", null, what, null);
        }
    }

    private void releaseHandle() {
        leasesHere.get()[0]--;
        if (inFlight.decrementAndGet() == 0) {
            synchronized (inFlightIdle) {
                inFlightIdle.notifyAll();
            }
            if (destroyWhenQuiet.get()) {
                // A close gave up waiting for this call. It is the last one out, so it owes the
                // teardown — on a thread of its own, because this one is returning from a native
                // call and reactor_destroy blocks.
                Thread.ofPlatform().name("reactor-deferred-destroy").start(this::finishTeardown);
            }
        }
    }

    /**
     * Waits for every in-flight native call to return.
     *
     * @return whether they all did — and so whether the handle may be destroyed at all
     */
    private boolean awaitNoCallsInFlight() {
        if (leasesHere.get()[0] > 0) {
            // This thread is itself inside a native call, which means a callback is closing its own
            // client. Waiting would be waiting for itself.
            LOG.log(
                    System.Logger.Level.WARNING,
                    "close() was called from inside a native call; the handle is left"
                            + " for the process rather than destroyed underneath the call making it");
            return false;
        }
        synchronized (inFlightIdle) {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while (inFlight.get() > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    LOG.log(
                            System.Logger.Level.WARNING,
                            "closing with {0} native call(s) still in flight; the handle is left for the process",
                            inFlight.get());
                    return false;
                }
                try {
                    inFlightIdle.wait(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining) + 1);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    private void requireOpen(String what) {
        if (closed.get()) {
            throw ReactorException.of(
                    ErrorCode.INVALID_STATE.code(), what + " was read from a closed client", null, what, null);
        }
    }

    /**
     * Where control events go, and how it shuts down without joining itself.
     *
     * <p>A handler that closes its own client runs on this thread, and an executor that waits for
     * its own thread to finish waits forever. So shutdown from inside never awaits.
     */
    private static final class Dispatch {

        private static final String EVENT_THREAD = "reactor-events";

        private final inc.reactor.sdk.Dispatcher dispatcher;
        private final @Nullable ExecutorService owned;

        private Dispatch(inc.reactor.sdk.Dispatcher dispatcher, @Nullable ExecutorService owned) {
            this.dispatcher = dispatcher;
            this.owned = owned;
        }

        static Dispatch of(inc.reactor.sdk.@Nullable Dispatcher configured) {
            if (configured != null) {
                return new Dispatch(configured, null);
            }
            ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, EVENT_THREAD);
                thread.setDaemon(true);
                return thread;
            });
            return new Dispatch(executor::execute, executor);
        }

        void run(Runnable event) {
            try {
                dispatcher.execute(event);
            } catch (RuntimeException rejected) {
                // The dispatcher is gone — a shut-down executor, a closed toolkit. Dropping the
                // event is right: there is nobody left who could act on it.
                LOG.log(System.Logger.Level.DEBUG, "a control event was dropped: " + rejected.getMessage());
            }
        }

        void shutdown() {
            if (owned == null) {
                return;
            }
            owned.shutdown();
            if (Thread.currentThread().getName().equals(EVENT_THREAD)) {
                // Closing from inside a handler: this thread is the one being waited for, so the
                // wait below can only end in its timeout. Bounded, so it is a stall rather than the
                // permanent deadlock an unbounded join would be — which is exactly why it is worth
                // skipping rather than tolerating. ClientPeerTest pins the difference by timing it.
                return;
            }
            try {
                if (!owned.awaitTermination(2, TimeUnit.SECONDS)) {
                    owned.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                owned.shutdownNow();
            }
        }

        private static final System.Logger LOG = System.getLogger(Dispatch.class.getName());
    }
}
