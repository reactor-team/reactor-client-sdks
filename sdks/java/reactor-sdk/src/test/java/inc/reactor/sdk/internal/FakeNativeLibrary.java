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
final class FakeNativeLibrary implements AutoCloseable {

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
    String nextOwnedString = "session-0000";

    /** What {@code reactor_destroy} answers: 0 = quiesced, -1 = a callback is still running. */
    int destroyResult = 0;

    /** How many times {@code reactor_destroy} was called. */
    int destroyCalls;

    /** The audio device mode {@code reactor_create_with_adm} was asked for. */
    int admMode = -1;

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
    String status = "ready";

    FakeNativeLibrary() {
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
    }

    /** Makes this library report an ABI version other than the one the binding expects. */
    void setAbiVersion(int version) {
        this.abiVersion = version;
    }

    /** Drops a symbol, the way a library older than the crates is missing a newly added one. */
    void removeSymbol(String cName) {
        symbols.remove(cName);
    }

    SymbolLookup lookup() {
        return name -> Optional.ofNullable(symbols.get(name));
    }

    /** Whether every owned string handed out came back to {@code reactor_free_string} exactly once. */
    boolean ownedStringsFreedExactlyOnce() {
        return new HashSet<>(freed).equals(ownedOut) && freed.size() == ownedOut.size();
    }

    /** Whether a static string was ever passed to {@code reactor_free_string}. */
    boolean aStaticStringWasFreed() {
        return freed.stream().anyMatch(staticsOut::contains);
    }

    int freeCallCount() {
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
    void fireCallback(String field, FunctionDescriptor descriptor, Object... arguments) {
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

    /** Allocates a C string that lives as long as this fake. */
    MemorySegment cString(String text) {
        return arena.allocateFrom(text);
    }

    private MemorySegment status(MemorySegment handle) {
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
