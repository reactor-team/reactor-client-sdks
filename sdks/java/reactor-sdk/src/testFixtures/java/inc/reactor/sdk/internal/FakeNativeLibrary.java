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

    /** Set to make reactor_download_clip block, so a test can close while it is initiating. */
    public volatile boolean blockInDownload;

    /** Released to let a blocked download initiation return. */
    public final java.util.concurrent.CountDownLatch blockDownload = new java.util.concurrent.CountDownLatch(1);

    /** Counted up the moment the download is entered. */
    public final java.util.concurrent.CountDownLatch enteredDownload = new java.util.concurrent.CountDownLatch(1);

    /** What {@code reactor_status} answers. */
    public String status = "ready";

    /**
     * The token handed to each {@code reactor_create_with_adm}, oldest first.
     *
     * <p>A list rather than a field: a client that re-mints replaces its handle, and what a test
     * has to be able to see is that the second one was created with the second token.
     */
    public final List<@Nullable String> createdWithJwt = new ArrayList<>();

    /** The options JSON of each {@code reactor_fetch_jwt}, oldest first. */
    public final List<@Nullable String> jwtRequests = new ArrayList<>();

    /** What the next {@code reactor_fetch_jwt} answers with, or {@code null} to fail it. */
    @Nullable
    public String nextMintedJwt = "minted-jwt";

    /**
     * Holds each {@code reactor_fetch_jwt} instead of answering it, for a test that needs a mint
     * to still be in flight while it does something else.
     */
    public boolean deferFetchJwt;

    /** The completion and userdata of each deferred exchange, oldest first. */
    private final List<MemorySegment[]> heldJwtCompletions = new ArrayList<>();

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
        bind("reactor_send_command", Ffi.Symbol.SEND_COMMAND.descriptor(), "sendCommand");
        bind("reactor_request_schema", Ffi.Symbol.REQUEST_SCHEMA.descriptor(), "requestSchema");
        bind("reactor_get_stats", Ffi.Symbol.GET_STATS.descriptor(), "getStats");
        bind("reactor_upload_file", Ffi.Symbol.UPLOAD_FILE.descriptor(), "uploadFile");
        bind("reactor_upload_bytes", Ffi.Symbol.UPLOAD_BYTES.descriptor(), "uploadBytes");
        bind("reactor_request_clip", Ffi.Symbol.REQUEST_CLIP.descriptor(), "requestClip");
        bind("reactor_download_clip", Ffi.Symbol.DOWNLOAD_CLIP.descriptor(), "downloadClip");
        bind("reactor_fetch_jwt", Ffi.Symbol.FETCH_JWT.descriptor(), "fetchJwt");
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
        this.createdWithJwt.add(readString(jwt));
        // Kept so a test can call back through the struct the way the FFI would.
        this.callbacks = callbacksStruct.reinterpret(Ffi.Callbacks.LAYOUT.byteSize());
        // Any non-null address will do: nothing here dereferences it, and the binding only ever
        // passes it straight back.
        return arena.allocate(8);
    }

    private int destroy(MemorySegment handle) {
        // The header says null is accepted and answers 0, before anything this fake could be asked
        // to imitate. A client that never created a handle must not be able to reach destroyResult.
        if (handle.equals(MemorySegment.NULL)) {
            return 0;
        }
        destroyCalls++;
        return destroyResult;
    }

    /**
     * Answers a key exchange, synchronously, on the calling thread.
     *
     * <p>{@code reactor_fetch_jwt} takes no handle and its completion is not bounded by any
     * client, so the binding routes it through {@code DetachedCompletion} rather than the ordinary
     * pending map — which is exactly the path this exercises. Calling the completion before
     * returning is what a real exchange is free to do and the harder ordering for the binding: the
     * future settles while the caller is still inside the downcall.
     */
    @SuppressWarnings("restricted") // downcallHandle: calling the binding's own completion stub
    private void fetchJwt(
            MemorySegment apiUrl,
            MemorySegment apiKey,
            MemorySegment optionsJson,
            int local,
            MemorySegment completion,
            MemorySegment userdata) {
        jwtRequests.add(readString(optionsJson));
        if (deferFetchJwt) {
            heldJwtCompletions.add(new MemorySegment[] {completion, userdata});
            return;
        }
        answerJwt(completion, userdata, nextMintedJwt);
    }

    /**
     * Answers the oldest exchange this library is holding.
     *
     * @param minted the token to answer with, or {@code null} to refuse the key
     */
    public void settleHeldJwt(@Nullable String minted) {
        if (heldJwtCompletions.isEmpty()) {
            throw new IllegalStateException("no key exchange is outstanding");
        }
        MemorySegment[] held = heldJwtCompletions.remove(0);
        answerJwt(held[0], held[1], minted);
    }

    /** @return how many key exchanges this library is holding unanswered */
    public int heldJwtCount() {
        return heldJwtCompletions.size();
    }

    @SuppressWarnings("restricted") // downcallHandle: calling the binding's own completion stub
    private void answerJwt(MemorySegment completion, MemorySegment userdata, @Nullable String minted) {
        MethodHandle call = linker.downcallHandle(completion, Ffi.Callbacks.COMPLETION);
        try (Arena reply = Arena.ofConfined()) {
            MemorySegment result =
                    minted == null ? MemorySegment.NULL : reply.allocateFrom("{\"jwt\":" + quote(minted) + "}");
            MemorySegment error = minted == null
                    ? reply.allocateFrom("{\"code\":\"UNAUTHORIZED\",\"message\":\"the key was refused\"}")
                    : MemorySegment.NULL;
            call.invokeWithArguments(minted == null ? 0 : 1, result, error, userdata);
        } catch (Throwable t) {
            throw new AssertionError("calling the fetch_jwt completion threw out of the stub", t);
        }
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
        if (blockInPushVideo) {
            enteredPush.countDown();
            try {
                blockPush.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        videoFramesPushed++;
    }

    /**
     * Whether an async call is waiting to be settled.
     *
     * <p>A test on another thread cannot settle a call that has not arrived yet, and
     * {@link #settleLastCall} throws rather than waiting for one. This is what a test polls.
     *
     * @return whether there is a completion outstanding
     */
    public boolean hasPendingCall() {
        return lastCompletion != null;
    }

    /**
     * Forgets the outstanding call, without settling it.
     *
     * <p>For a fixture whose setup makes a call of its own — a connect, to bring the native client
     * into being — so that {@link #hasPendingCall} still answers about the call the test is
     * actually waiting for rather than about the setup's.
     */
    public void clearPendingCall() {
        lastCompletion = null;
        lastUserdata = null;
    }

    /** The completion stub of the last async call, so a test can settle calls out of order. */
    public MemorySegment lastCompletionStub() {
        return java.util.Objects.requireNonNull(lastCompletion, "no call is outstanding");
    }

    /** The userdata that went with it. */
    public MemorySegment lastUserdataFor() {
        return java.util.Objects.requireNonNull(lastUserdata, "no call is outstanding");
    }

    /**
     * Settles a specific completion, not the newest one.
     *
     * <p>Two calls on one track can answer in either order, and the SDK has to survive both. That
     * is not expressible while the only handle a test has is "the last one".
     */
    @SuppressWarnings("restricted") // downcallHandle: calling the client's own completion stub
    public void settle(
            MemorySegment completion,
            MemorySegment userdata,
            boolean ok,
            @Nullable String resultJson,
            @Nullable String errorJson) {
        MethodHandle call = linker.downcallHandle(completion, Ffi.Callbacks.COMPLETION);
        try {
            call.invokeWithArguments(
                    ok ? 1 : 0,
                    resultJson == null ? MemorySegment.NULL : arena.allocateFrom(resultJson),
                    errorJson == null ? MemorySegment.NULL : arena.allocateFrom(errorJson),
                    userdata);
        } catch (Throwable t) {
            throw new IllegalStateException("the completion could not be called", t);
        }
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

    private void sendCommand(
            MemorySegment handle,
            MemorySegment name,
            MemorySegment args,
            MemorySegment uploads,
            MemorySegment completion,
            MemorySegment userdata) {
        lastCommandArgs = readString(args);
        lastUploadsJson = readString(uploads);
        lastCompletion = completion;
        lastUserdata = userdata;
    }

    private void requestSchema(MemorySegment handle, MemorySegment completion, MemorySegment userdata) {
        lastCompletion = completion;
        lastUserdata = userdata;
    }

    private void getStats(MemorySegment handle, MemorySegment completion, MemorySegment userdata) {
        lastCompletion = completion;
        lastUserdata = userdata;
    }

    /** The JSON the client serialised the last command's arguments to. */
    public @Nullable String lastCommandArgs;

    /** The path the last file upload was given. */
    public @Nullable String lastUploadPath;

    /** A copy of the bytes the last byte upload was given, taken while the call was in flight. */
    public byte @Nullable [] lastUploadBytes;

    /** The uploads JSON the last command carried. */
    public @Nullable String lastUploadsJson;

    private void uploadFile(
            MemorySegment handle, MemorySegment path, MemorySegment completion, MemorySegment userdata) {
        lastUploadPath = readString(path);
        lastCompletion = completion;
        lastUserdata = userdata;
    }

    @SuppressWarnings("restricted") // reinterpret: reading the borrowed buffer, during the call
    private void uploadBytes(
            MemorySegment handle,
            MemorySegment data,
            long length,
            MemorySegment name,
            MemorySegment mimeType,
            MemorySegment completion,
            MemorySegment userdata) {
        // Read here, inside the call, because that is the whole contract: the header says these
        // bytes are borrowed for the call only, so a real library copies them before returning and
        // so does this one. Reading them after the call would be the bug this pins.
        lastUploadBytes = data.reinterpret(length).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
        lastCompletion = completion;
        lastUserdata = userdata;
    }

    /** The completion the last download was given — it outlives the client, so it is kept apart. */
    public @Nullable MemorySegment downloadCompletion;

    /** The progress callback the last download was given. */
    public @Nullable MemorySegment downloadProgress;

    /** The timeout the last download was given. */
    public double downloadTimeout;

    private void requestClip(MemorySegment handle, double duration, MemorySegment completion, MemorySegment userdata) {
        lastCompletion = completion;
        lastUserdata = userdata;
    }

    private void downloadClip(
            MemorySegment handle,
            MemorySegment playlistUrl,
            MemorySegment jwt,
            MemorySegment outPath,
            double predictedReadyAtMs,
            double readyTimeoutSeconds,
            int local,
            MemorySegment progress,
            MemorySegment completion,
            MemorySegment userdata) {
        if (blockInDownload) {
            enteredDownload.countDown();
            try {
                blockDownload.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        downloadProgress = progress;
        downloadCompletion = completion;
        downloadTimeout = readyTimeoutSeconds;
    }

    /** Calls the download's progress callback, the way the downloader's own thread would. */
    @SuppressWarnings("restricted") // downcallHandle: calling the client's own stub
    public void reportDownloadProgress(int done, int total) {
        MethodHandle call = linker.downcallHandle(
                java.util.Objects.requireNonNull(downloadProgress, "no download is outstanding"),
                Ffi.Callbacks.PROGRESS);
        try {
            call.invokeWithArguments(done, total, MemorySegment.NULL);
        } catch (Throwable t) {
            throw new AssertionError("the progress callback threw out of the stub", t);
        }
    }

    /** Settles the download's completion, which the FFI promises fires exactly once. */
    @SuppressWarnings("restricted") // downcallHandle: calling the client's own stub
    public void settleDownload(boolean ok, @Nullable String resultJson, @Nullable String errorJson) {
        MethodHandle call = linker.downcallHandle(
                java.util.Objects.requireNonNull(downloadCompletion, "no download is outstanding"),
                Ffi.Callbacks.COMPLETION);
        try {
            call.invokeWithArguments(
                    ok ? 1 : 0,
                    resultJson == null ? MemorySegment.NULL : arena.allocateFrom(resultJson),
                    errorJson == null ? MemorySegment.NULL : arena.allocateFrom(errorJson),
                    MemorySegment.NULL);
        } catch (Throwable t) {
            throw new AssertionError("settling the download threw out of the stub", t);
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

    /** The same, for a frame push — which reaches the FFI through a different funnel. */
    public volatile boolean blockInPushVideo;

    /** Released to let a blocked push return. */
    public final java.util.concurrent.CountDownLatch blockPush = new java.util.concurrent.CountDownLatch(1);

    /** Counted up the moment the push is entered. */
    public final java.util.concurrent.CountDownLatch enteredPush = new java.util.concurrent.CountDownLatch(1);

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
