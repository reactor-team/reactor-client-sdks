package inc.reactor.sdk.internal;

import inc.reactor.sdk.ConnectionStatus;
import inc.reactor.sdk.ErrorCode;
import inc.reactor.sdk.ReactorException;
import inc.reactor.sdk.ReactorOptions;
import inc.reactor.sdk.ReactorSdk;
import inc.reactor.sdk.Subscription;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;
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

    private final AtomicBoolean closed = new AtomicBoolean();

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
        return create(options, NativeLibrary::load);
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
    static ClientPeer create(ReactorOptions options, java.util.function.Function<Arena, Ffi> loader) {
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
        // on_frame and on_audio stay NULL until tracks exist. userdata is unused: each stub is
        // already bound to this peer, so there is nothing to look up.
        setCallback(callbacks, "on_frame", MemorySegment.NULL);
        setCallback(callbacks, "on_audio", MemorySegment.NULL);
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
        requireOpen("status");
        try {
            MemorySegment text = (MemorySegment) ffi.handle(Ffi.Symbol.STATUS).invokeExact(handle);
            return ConnectionStatus.of(NativeStrings.staticRef(text)).orElse(ConnectionStatus.DISCONNECTED);
        } catch (Throwable t) {
            throw new IllegalStateException("reactor_status could not be called", t);
        }
    }

    /** @return the session id, or empty when there is no session */
    public Optional<String> sessionId() {
        requireOpen("sessionId");
        try {
            MemorySegment owned =
                    (MemorySegment) ffi.handle(Ffi.Symbol.SESSION_ID).invokeExact(handle);
            return Optional.ofNullable(NativeStrings.takeOwned(owned, ffi.handle(Ffi.Symbol.FREE_STRING)));
        } catch (Throwable t) {
            throw new IllegalStateException("reactor_session_id could not be called", t);
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
        int quiesced = destroyOffVirtualThread();
        dispatch.shutdown();
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
            TrackAnnouncement announcement = new TrackAnnouncement(trackName, trackMid);
            dispatch.run(() -> trackEvents.emit(announcement));
        }
    }

    private void onCapabilities(MemorySegment capsJson, MemorySegment userdata) {
        String copy = NativeStrings.borrow(capsJson);
        if (copy != null) {
            dispatch.run(() -> capabilitiesEvents.emit(copy));
        }
    }

    private void onSessionId(MemorySegment sessionId, MemorySegment userdata) {
        Optional<String> id = Optional.ofNullable(NativeStrings.borrow(sessionId));
        dispatch.run(() -> sessionIdEvents.emit(id));
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
        try {
            ffi.handle(symbol).invokeWithArguments((Object[]) arguments);
        } catch (Throwable t) {
            throw new IllegalStateException(symbol.cName() + " could not be called", t);
        }
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
