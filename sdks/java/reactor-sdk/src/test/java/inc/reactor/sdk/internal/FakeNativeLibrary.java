package inc.reactor.sdk.internal;

import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A native library, written in Java.
 *
 * <p>{@link Linker#upcallStub} turns a Java method into a real C function pointer, and a {@link
 * SymbolLookup} is an interface. Between them, a fake library needs no C compiler and no fixture
 * binary: the binding's own downcall path — descriptors, marshalling, string reading, the free path
 * — runs against this unchanged, which is the point. A test that called the binding's helpers
 * directly would prove the helpers work, not that anything is wired to them.
 *
 * <p>Every symbol gets a do-nothing stub by default, because {@link Ffi#open} binds all of them;
 * the handful a test cares about are replaced with ones that behave.
 *
 * <p>This calls {@code upcallStub} directly rather than going through {@link Upcalls}, which
 * refuses anything that returns a value. That refusal is right for callbacks — every callback in
 * the header returns void — and wrong here, where the fake has to imitate functions that return
 * one.
 */
public final class FakeNativeLibrary implements AutoCloseable {

    private final Arena arena = Arena.ofShared();
    private final Linker linker = Linker.nativeLinker();
    private final Map<String, MemorySegment> symbols = new HashMap<>();

    /** Addresses handed out as owned strings — the caller's to free. */
    private final Set<Long> ownedOut = new HashSet<>();

    /** Addresses handed out as static literals — freeing one corrupts the heap. */
    private final Set<Long> staticsOut = new HashSet<>();

    /** Every address passed to {@code reactor_free_string}, in order, duplicates included. */
    private final List<Long> freed = new ArrayList<>();

    private int abiVersion = Ffi.ABI_VERSION;

    /** What the next owned string this library hands out will say. */
    public String nextOwnedString = "session-0000";

    /** What {@code reactor_destroy} answers: 0 = quiesced, -1 = a callback is still running. */
    public int destroyResult = 0;

    /** How many times {@code reactor_destroy} was called. */
    public int destroyCalls;

    /** The audio device mode {@code reactor_create_with_adm} was asked for. */
    public int admMode = -1;

    /** What the client reported about itself. */
    @Nullable
    String sdkVersion;

    /** Which binding the client said it was. */
    @Nullable
    String sdkType;

    /** The ReactorCallbacks struct the client handed over, so a test can call back through it. */
    @Nullable
    MemorySegment callbacks;

    /** The completion and userdata of the last async call, so a test can settle it. */
    @Nullable
    MemorySegment lastCompletion;

    @Nullable
    MemorySegment lastUserdata;

    /** What {@code reactor_status} answers. */
    public String status = "ready";

    public FakeNativeLibrary() {
        for (Ffi.Symbol symbol : Ffi.Symbol.values()) {
            symbols.put(symbol.cName(), doNothing(symbol.descriptor()));
        }
        bind("reactor_abi_version", FunctionDescriptor.of(JAVA_INT), "abiVersion");
        bind("reactor_free_string", Ffi.Symbol.FREE_STRING.descriptor(), "freeString");
        bind("reactor_session_id", Ffi.Symbol.SESSION_ID.descriptor(), "sessionId");
        bind("reactor_status", Ffi.Symbol.STATUS.descriptor(), "status");
        bind("reactor_create_with_adm", Ffi.Symbol.CREATE_WITH_ADM.descriptor(), "createWithAdm");
        bind("reactor_destroy", Ffi.Symbol.DESTROY.descriptor(), "destroy");
        bind("reactor_connect", Ffi.Symbol.CONNECT.descriptor(), "connect");
        bind("reactor_disconnect", Ffi.Symbol.DISCONNECT.descriptor(), "disconnect");
        bind("reactor_tracks", Ffi.Symbol.TRACKS.descriptor(), "tracks");
        bind("reactor_paused_tracks", Ffi.Symbol.PAUSED_TRACKS.descriptor(), "pausedTracks");
        bind("reactor_publish_track", Ffi.Symbol.PUBLISH_TRACK.descriptor(), "publishTrack");
        bind("reactor_unpublish_track", Ffi.Symbol.UNPUBLISH_TRACK.descriptor(), "unpublishTrack");
        bind("reactor_push_video_frame", Ffi.Symbol.PUSH_VIDEO_FRAME.descriptor(), "pushVideoFrame");
    }

    /** Makes this library report an ABI version other than the one the binding expects. */
    public void setAbiVersion(int version) {
        this.abiVersion = version;
    }

    /** Drops a symbol, the way a library older than the crates is missing a newly added one. */
    public void removeSymbol(String cName) {
        symbols.remove(cName);
    }

    public SymbolLookup lookup() {
        return name -> Optional.ofNullable(symbols.get(name));
    }

    /** Whether every owned string handed out came back to {@code reactor_free_string} exactly once. */
    public boolean ownedStringsFreedExactlyOnce() {
        return new HashSet<>(freed).equals(ownedOut) && freed.size() == ownedOut.size();
    }

    /** Whether a static string was ever passed to {@code reactor_free_string}. */
    public boolean aStaticStringWasFreed() {
        return freed.stream().anyMatch(staticsOut::contains);
    }

    public int freeCallCount() {
        return freed.size();
    }

    // ── The functions themselves ────────────────────────────────────────────

    private int abiVersion() {
        return abiVersion;
    }

    private void freeString(MemorySegment pointer) {
        freed.add(pointer.address());
    }

    private MemorySegment sessionId(MemorySegment handle) {
        MemorySegment segment = arena.allocateFrom(nextOwnedString);
        ownedOut.add(segment.address());
        return segment;
    }

    @SuppressWarnings("restricted") // reinterpret: the callbacks struct, to call back through it
    private MemorySegment createWithAdm(
            MemorySegment apiUrl,
            MemorySegment modelName,
            MemorySegment jwt,
            int local,
            MemorySegment callbacksStruct,
            int adm,
            MemorySegment version,
            MemorySegment type) {
        this.admMode = adm;
        this.sdkVersion = readString(version);
        this.sdkType = readString(type);
        // Kept so a test can call back through the struct the way the FFI would.
        this.callbacks = callbacksStruct.reinterpret(Ffi.Callbacks.LAYOUT.byteSize());
        // Any non-null address will do: nothing here dereferences it, and the binding only ever
        // passes it straight back.
        return arena.allocate(8);
    }

    private int destroy(MemorySegment handle) {
        destroyCalls++;
        return destroyResult;
    }

    private void connect(
            MemorySegment handle,
            MemorySegment sessionId,
            MemorySegment connectionId,
            MemorySegment completion,
            MemorySegment userdata) {
        lastCompletion = completion;
        lastUserdata = userdata;
    }

    private void disconnect(MemorySegment handle, MemorySegment completion, MemorySegment userdata) {
        lastCompletion = completion;
        lastUserdata = userdata;
    }

    @SuppressWarnings("restricted") // reinterpret: reading a string the binding just allocated
    private static @Nullable String readString(MemorySegment segment) {
        return segment.equals(MemorySegment.NULL)
                ? null
                : segment.reinterpret(4096).getString(0);
    }

    /** Calls one of the client's callbacks, the way the FFI would. */
    @SuppressWarnings("restricted") // downcallHandle: calling the client's own stub
    public void fireCallback(String field, FunctionDescriptor descriptor, Object... arguments) {
        MemorySegment struct = java.util.Objects.requireNonNull(callbacks, "the client registered no callbacks");
        long offset = Ffi.Callbacks.LAYOUT.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement(field));
        MemorySegment pointer = struct.get(java.lang.foreign.ValueLayout.ADDRESS, offset);
        MethodHandle call = linker.downcallHandle(pointer, descriptor);
        try {
            call.invokeWithArguments(arguments);
        } catch (Throwable t) {
            throw new AssertionError("calling " + field + " threw out of the stub", t);
        }
    }

    /** Allocates zeroed memory that lives as long as this fake. */
    public MemorySegment allocate(long bytes) {
        return arena.allocate(bytes);
    }

    /** Allocates a C string that lives as long as this fake. */
    public MemorySegment cString(String text) {
        return arena.allocateFrom(text);
    }

    /** What {@code reactor_tracks} answers, as the FFI's JSON array. */
    public String tracksJson = "[]";

    /** What {@code reactor_paused_tracks} answers. */
    public String pausedJson = "[]";

    /** Runs on every {@code reactor_tracks} read, before it answers — for racing the cache. */
    @Nullable
    Runnable duringTracksRead;

    private MemorySegment tracks(MemorySegment handle) {
        if (duringTracksRead != null) {
            duringTracksRead.run();
        }
        MemorySegment segment = arena.allocateFrom(tracksJson);
        ownedOut.add(segment.address());
        return segment;
    }

    private MemorySegment pausedTracks(MemorySegment handle) {
        MemorySegment segment = arena.allocateFrom(pausedJson);
        ownedOut.add(segment.address());
        return segment;
    }

    /** The error {@code reactor_unpublish_track} answers with, or null for success. */
    public @Nullable String unpublishError;

    /** How many frames reached {@code reactor_push_video_frame}. */
    public int videoFramesPushed;

    private void publishTrack(
            MemorySegment handle, MemorySegment name, MemorySegment completion, MemorySegment userdata) {
        lastCompletion = completion;
        lastUserdata = userdata;
    }

    private MemorySegment unpublishTrack(MemorySegment handle, MemorySegment name) {
        if (unpublishError == null) {
            return MemorySegment.NULL;
        }
        MemorySegment segment = arena.allocateFrom(unpublishError);
        ownedOut.add(segment.address());
        return segment;
    }

    private void pushVideoFrame(MemorySegment handle, MemorySegment name, MemorySegment data, int width, int height) {
        videoFramesPushed++;
    }

    /** Settles the completion of the last async call the client made. */
    @SuppressWarnings("restricted") // downcallHandle: calling the client's own completion stub
    public void settleLastCall(boolean ok, @Nullable String resultJson, @Nullable String errorJson) {
        MethodHandle call = linker.downcallHandle(
                java.util.Objects.requireNonNull(lastCompletion, "no call is outstanding"), Ffi.Callbacks.COMPLETION);
        try {
            call.invokeWithArguments(
                    ok ? 1 : 0,
                    resultJson == null ? MemorySegment.NULL : arena.allocateFrom(resultJson),
                    errorJson == null ? MemorySegment.NULL : arena.allocateFrom(errorJson),
                    java.util.Objects.requireNonNull(lastUserdata));
        } catch (Throwable t) {
            throw new AssertionError("settling the completion threw out of the stub", t);
        }
    }

    /**
     * Held open while a test wants a native call to be in flight.
     *
     * <p>A closed latch is what "the FFI is still running this call" looks like from Java, and the
     * only way to exercise a teardown that races one.
     */
    public final java.util.concurrent.CountDownLatch blockStatus = new java.util.concurrent.CountDownLatch(1);

    /** Runs inside reactor_status, for a test that closes from within a native call. */
    @Nullable
    public Runnable duringStatus;

    /** Set to make reactor_status wait on {@link #blockStatus} before answering. */
    public volatile boolean blockInStatus;

    /** Counted up the moment reactor_status is entered, so a test knows the call is inside. */
    public final java.util.concurrent.CountDownLatch enteredStatus = new java.util.concurrent.CountDownLatch(1);

    private MemorySegment status(MemorySegment handle) {
        Runnable during = duringStatus;
        if (during != null) {
            during.run();
        }
        if (blockInStatus) {
            enteredStatus.countDown();
            try {
                blockStatus.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        MemorySegment segment = arena.allocateFrom("ready");
        staticsOut.add(segment.address());
        return segment;
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    @SuppressWarnings("restricted") // upcallStub: a fake library is exactly what this is for
    private void bind(String cName, FunctionDescriptor descriptor, String method) {
        try {
            MethodType type = descriptor.toMethodType();
            MethodHandle handle = MethodHandles.lookup().findVirtual(FakeNativeLibrary.class, method, type);
            symbols.put(cName, linker.upcallStub(handle.bindTo(this), descriptor, arena));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the fake cannot bind " + cName, e);
        }
    }

    @SuppressWarnings("restricted") // upcallStub: as above
    private MemorySegment doNothing(FunctionDescriptor descriptor) {
        return linker.upcallStub(MethodHandles.empty(descriptor.toMethodType()), descriptor, arena);
    }

    @Override
    public void close() {
        arena.close();
    }
}
