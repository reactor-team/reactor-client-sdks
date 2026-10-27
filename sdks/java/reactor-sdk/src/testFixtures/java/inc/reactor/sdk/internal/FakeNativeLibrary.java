package inc.reactor.sdk.internal;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
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

    /** The {@code auto_resume_tracks} flag {@code reactor_create_with_adm} was called with. */
    public int autoResumeTracks = -1;

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
        bind("reactor_push_video_frame_timed", Ffi.Symbol.PUSH_VIDEO_FRAME_TIMED.descriptor(), "pushVideoFrameTimed");
        bind("reactor_push_audio_frame", Ffi.Symbol.PUSH_AUDIO_FRAME.descriptor(), "pushAudioFrame");
        bind("reactor_frame_delivered", Ffi.Symbol.FRAME_DELIVERED.descriptor(), "frameDelivered");
        bind("reactor_push_begin", Ffi.Symbol.PUSH_BEGIN.descriptor(), "pushBegin");
        bind("reactor_send_command", Ffi.Symbol.SEND_COMMAND.descriptor(), "sendCommand");
        bind("reactor_request_schema", Ffi.Symbol.REQUEST_SCHEMA.descriptor(), "requestSchema");
        bind("reactor_get_stats", Ffi.Symbol.GET_STATS.descriptor(), "getStats");
        bind("reactor_upload_file", Ffi.Symbol.UPLOAD_FILE.descriptor(), "uploadFile");
        bind("reactor_upload_bytes", Ffi.Symbol.UPLOAD_BYTES.descriptor(), "uploadBytes");
        bind("reactor_request_clip", Ffi.Symbol.REQUEST_CLIP.descriptor(), "requestClip");
        bind("reactor_download_clip", Ffi.Symbol.DOWNLOAD_CLIP.descriptor(), "downloadClip");
        bind("reactor_fetch_jwt", Ffi.Symbol.FETCH_JWT.descriptor(), "fetchJwt");
        bind("reactor_create_with_options", Ffi.Symbol.CREATE_WITH_OPTIONS.descriptor(), "createWithOptions");
        bind("reactor_step", Ffi.Symbol.STEP.descriptor(), "step");
        bind("reactor_clear_step_inputs", Ffi.Symbol.CLEAR_STEP_INPUTS.descriptor(), "clearStepInputs");
        bind(
                "reactor_push_step_encoded_video_frame",
                Ffi.Symbol.PUSH_STEP_ENCODED_VIDEO_FRAME.descriptor(),
                "pushStepEncodedVideoFrame");
        bind("reactor_step_result_step", Ffi.Symbol.STEP_RESULT_STEP.descriptor(), "stepResultStep");
        bind("reactor_step_result_elapsed", Ffi.Symbol.STEP_RESULT_ELAPSED.descriptor(), "stepResultElapsed");
        bind("reactor_step_result_error", Ffi.Symbol.STEP_RESULT_ERROR.descriptor(), "stepResultError");
        bind("reactor_step_result_file_count", Ffi.Symbol.STEP_RESULT_FILE_COUNT.descriptor(), "stepResultFileCount");
        bind("reactor_step_result_file_name", Ffi.Symbol.STEP_RESULT_FILE_NAME.descriptor(), "stepResultFileName");
        bind(
                "reactor_step_result_file_content_type",
                Ffi.Symbol.STEP_RESULT_FILE_CONTENT_TYPE.descriptor(),
                "stepResultFileContentType");
        bind("reactor_step_result_file_size", Ffi.Symbol.STEP_RESULT_FILE_SIZE.descriptor(), "stepResultFileSize");
        bind(
                "reactor_step_result_track_count",
                Ffi.Symbol.STEP_RESULT_TRACK_COUNT.descriptor(),
                "stepResultTrackCount");
        bind("reactor_step_result_track_name", Ffi.Symbol.STEP_RESULT_TRACK_NAME.descriptor(), "stepResultTrackName");
        bind("reactor_step_result_track_kind", Ffi.Symbol.STEP_RESULT_TRACK_KIND.descriptor(), "stepResultTrackKind");
        bind("reactor_step_result_track_rate", Ffi.Symbol.STEP_RESULT_TRACK_RATE.descriptor(), "stepResultTrackRate");
        bind(
                "reactor_step_result_frame_count",
                Ffi.Symbol.STEP_RESULT_FRAME_COUNT.descriptor(),
                "stepResultFrameCount");
        bind(
                "reactor_step_result_frame_encoding",
                Ffi.Symbol.STEP_RESULT_FRAME_ENCODING.descriptor(),
                "stepResultFrameEncoding");
        bind(
                "reactor_step_result_frame_dtype",
                Ffi.Symbol.STEP_RESULT_FRAME_DTYPE.descriptor(),
                "stepResultFrameDtype");
        bind(
                "reactor_step_result_frame_shape",
                Ffi.Symbol.STEP_RESULT_FRAME_SHAPE.descriptor(),
                "stepResultFrameShape");
        bind("reactor_step_result_frame_data", Ffi.Symbol.STEP_RESULT_FRAME_DATA.descriptor(), "stepResultFrameData");
        bind(
                "reactor_step_result_frame_metadata",
                Ffi.Symbol.STEP_RESULT_FRAME_METADATA.descriptor(),
                "stepResultFrameMetadata");
        bind(
                "reactor_step_result_frame_capture_time_us",
                Ffi.Symbol.STEP_RESULT_FRAME_CAPTURE_TIME_US.descriptor(),
                "stepResultFrameCaptureTimeUs");
        bind("reactor_step_result_free", Ffi.Symbol.STEP_RESULT_FREE.descriptor(), "stepResultFree");
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
            int autoResumeTracks,
            MemorySegment callbacksStruct,
            int adm,
            MemorySegment version,
            MemorySegment type) {
        this.autoResumeTracks = autoResumeTracks;
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

    /** How many frames reached {@code reactor_push_video_frame} or {@code reactor_push_video_frame_timed}. */
    public int videoFramesPushed;

    /** The BGRA bytes of the last video frame pushed, {@code width * height * 4} of them. */
    public volatile byte @Nullable [] lastVideoBgra;

    /** The time the binding reported spending on the last frame it pushed, in milliseconds. */
    public volatile double lastBindingMs = -1;

    /** How many frames the binding marked as handed to the app. */
    public volatile int framesDelivered;

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

    private void pushAudioFrame(
            MemorySegment handle,
            MemorySegment name,
            MemorySegment data,
            int samplesPerChannel,
            int sampleRate,
            int channels) {
        calls.add("pcm:" + readString(name));
    }

    @SuppressWarnings("restricted") // reinterpret: reading the frame the binding handed over
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
        lastVideoBgra = data.reinterpret((long) width * height * 4).toArray(JAVA_BYTE);
    }

    private void pushVideoFrameTimed(
            MemorySegment handle,
            MemorySegment name,
            MemorySegment data,
            int width,
            int height,
            MemorySegment userData,
            int userDataLength,
            int hasCaptureTime,
            long captureTimeUs,
            double bindingMs,
            long generation) {
        lastBindingMs = bindingMs;
        lastGeneration = generation;
        pushOrder.add(readString(name));
        calls.add("raw:" + readString(name));
        Runnable during = duringFirstPush;
        duringFirstPush = null;
        if (during != null) {
            during.run();
        }
        pushVideoFrame(handle, name, data, width, height);
    }

    /** The connection the binding read as the app handed over the last frame it pushed. */
    public volatile long lastGeneration = -1;

    private long pushBegin(MemorySegment handle) {
        return 7;
    }

    private void frameDelivered() {
        framesDelivered++;
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
        MemorySegment segment = arena.allocateFrom(status);
        staticsOut.add(segment.address());
        return segment;
    }

    // ── Stepped generation ──────────────────────────────────────────────────

    /** The options JSON of each {@code reactor_create_with_options}, oldest first. */
    public final List<@Nullable String> createdWithOptions = new ArrayList<>();

    /**
     * What the next {@code reactor_create_with_options} refuses with, as the error object it
     * stores in {@code error_out}, or {@code null} to create normally.
     */
    public @Nullable String createOptionsError;

    /** The state and commands JSON of the last {@code reactor_step}. */
    public @Nullable String lastStepState;

    public @Nullable String lastStepCommands;

    /** How many {@code reactor_step} calls arrived. */
    public int stepCalls;

    /** Raw video and audio pushes ({@code "raw:<track>"}, {@code "pcm:<track>"}) and steps ({@code
     * "step"}), in the order they came. */
    public final List<String> calls = java.util.Collections.synchronizedList(new ArrayList<>());

    /** Run inside the next raw video push, once, on the pushing thread. */
    public volatile @Nullable Runnable duringFirstPush;

    /** How many times {@code reactor_clear_step_inputs} was called. */
    public int clearStepInputsCalls;

    /** The completion and userdata of the last {@code reactor_step}, kept apart from the JSON ones. */
    private volatile @Nullable MemorySegment lastStepCompletion;

    private volatile @Nullable MemorySegment lastStepUserdata;

    /** Results handed out and not yet freed, by address. */
    private final Map<Long, NativeStepResult> liveResults = new java.util.concurrent.ConcurrentHashMap<>();

    /** Every address passed to {@code reactor_step_result_free}, in order, duplicates included. */
    public final List<Long> freedResults = java.util.Collections.synchronizedList(new ArrayList<>());

    /** Every result address handed to a step completion, in order. */
    public final List<Long> handedOutResults = java.util.Collections.synchronizedList(new ArrayList<>());

    /** One frame of a fake step result, as a test describes it. */
    public record StepFrameSpec(
            String encoding,
            String dtype,
            int[] shape,
            byte[] data,
            byte @Nullable [] metadata,
            @Nullable Long captureTimeUs) {}

    /**
     * One output track of a fake step result.
     *
     * @param rate what {@code reactor_step_result_track_rate} answers: frames or samples per second,
     *     0 for unspecified
     */
    public record StepTrackSpec(String name, String kind, double rate, List<StepFrameSpec> frames) {

        /** A track whose rate the runtime did not report. */
        public StepTrackSpec(String name, String kind, List<StepFrameSpec> frames) {
            this(name, kind, 0.0, frames);
        }
    }

    /** The native side of a result: everything its accessors hand out, allocated once. */
    private record NativeStepResult(
            long step, List<NativeTrack> tracks, double elapsed, List<NativeFile> files, MemorySegment error) {}

    /** One file a fake step result names, as a test describes it. */
    public record StepFileSpec(String name, String contentType, long size) {}

    private record NativeFile(MemorySegment name, MemorySegment contentType, long size) {}

    private record NativeTrack(MemorySegment name, MemorySegment kind, double rate, List<NativeFrame> frames) {}

    private record NativeFrame(
            MemorySegment encoding,
            MemorySegment dtype,
            MemorySegment shape,
            int ndim,
            MemorySegment data,
            long length,
            MemorySegment metadata,
            long metadataLength,
            @Nullable Long captureTimeUs) {}

    @SuppressWarnings("restricted") // reinterpret: the callbacks struct, to call back through it
    private MemorySegment createWithOptions(
            MemorySegment apiUrl,
            MemorySegment modelName,
            MemorySegment jwt,
            MemorySegment callbacksStruct,
            MemorySegment optionsJson,
            MemorySegment errorOut) {
        createdWithOptions.add(readString(optionsJson));
        if (createOptionsError != null) {
            MemorySegment error = arena.allocateFrom(createOptionsError);
            ownedOut.add(error.address());
            if (!errorOut.equals(MemorySegment.NULL)) {
                errorOut.reinterpret(java.lang.foreign.ValueLayout.ADDRESS.byteSize())
                        .set(java.lang.foreign.ValueLayout.ADDRESS, 0, error);
            }
            return MemorySegment.NULL;
        }
        this.createdWithJwt.add(readString(jwt));
        this.callbacks = callbacksStruct.equals(MemorySegment.NULL)
                ? null
                : callbacksStruct.reinterpret(Ffi.Callbacks.LAYOUT.byteSize());
        return arena.allocate(8);
    }

    private void step(
            MemorySegment handle,
            MemorySegment stateJson,
            MemorySegment commandsJson,
            MemorySegment completion,
            MemorySegment userdata) {
        stepCalls++;
        calls.add("step");
        lastStepState = readString(stateJson);
        lastStepCommands = readString(commandsJson);
        lastStepCompletion = completion;
        lastStepUserdata = userdata;
    }

    private void clearStepInputs(MemorySegment handle) {
        clearStepInputsCalls++;
    }

    /** One {@code reactor_push_step_encoded_video_frame} call, its buffers copied out. */
    public record EncodedPush(
            String track,
            int encoding,
            byte[] data,
            int width,
            int height,
            byte @Nullable [] userData,
            boolean hasCaptureTime,
            long captureTimeUs) {}

    /**
     * Every frame push that reached the library since the client was opened, in order: an {@link
     * EncodedPush}, or the track name of a raw video push.
     */
    public final List<Object> pushOrder = java.util.Collections.synchronizedList(new ArrayList<>());

    @SuppressWarnings("restricted") // reinterpret: the lengths are the ones the binding passed
    private void pushStepEncodedVideoFrame(
            MemorySegment handle,
            MemorySegment name,
            int encoding,
            MemorySegment data,
            long dataLength,
            int width,
            int height,
            MemorySegment userData,
            int userDataLength,
            int hasCaptureTime,
            long captureTimeUs) {
        pushOrder.add(new EncodedPush(
                readString(name),
                encoding,
                data.reinterpret(dataLength).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
                width,
                height,
                userData.equals(MemorySegment.NULL)
                        ? null
                        : userData.reinterpret(userDataLength).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
                hasCaptureTime != 0,
                captureTimeUs));
    }

    /** Whether a step is waiting to be settled. */
    public boolean hasPendingStep() {
        return lastStepCompletion != null;
    }

    /**
     * Settles the last step with a result built from {@code tracks}, the way the FFI hands one
     * over: an owned pointer the binding must read and then free.
     *
     * @return the address handed over, so a test can check that exactly that one came back
     */
    @SuppressWarnings("restricted") // downcallHandle: calling the client's own completion stub
    public long settleLastStep(long index, List<StepTrackSpec> tracks) {
        return settleLastStep(index, tracks, 0.0);
    }

    /** As {@link #settleLastStep(long, List)}, with the seconds the step's {@code generate()} took. */
    @SuppressWarnings("restricted") // downcallHandle: calling the client's own completion stub
    public long settleLastStep(long index, List<StepTrackSpec> tracks, double elapsed) {
        return settleLastStep(index, tracks, elapsed, List.of(), null);
    }

    /**
     * As {@link #settleLastStep(long, List, double)}, with the files the model kept and, for a step
     * that ran but failed, its error — still an {@code ok} completion, as the header says.
     */
    @SuppressWarnings("restricted") // downcallHandle: calling the client's own completion stub
    public long settleLastStep(
            long index, List<StepTrackSpec> tracks, double elapsed, List<StepFileSpec> files, @Nullable String error) {
        MemorySegment result = buildResult(index, tracks, elapsed, files, error);
        callStepCompletion(1, result, MemorySegment.NULL);
        return result.address();
    }

    /** Fails the last step with an error object, and no result. */
    public void failLastStep(String errorJson) {
        callStepCompletion(0, MemorySegment.NULL, arena.allocateFrom(errorJson));
    }

    /** @return whether every result handed out came back to {@code reactor_step_result_free} exactly once */
    public boolean stepResultsFreedExactlyOnce() {
        synchronized (freedResults) {
            return new HashSet<>(freedResults).equals(new HashSet<>(handedOutResults))
                    && freedResults.size() == handedOutResults.size();
        }
    }

    @SuppressWarnings("restricted") // downcallHandle: calling the client's own completion stub
    private void callStepCompletion(int ok, MemorySegment result, MemorySegment errorJson) {
        MemorySegment completion = java.util.Objects.requireNonNull(lastStepCompletion, "no step is outstanding");
        MemorySegment userdata = java.util.Objects.requireNonNull(lastStepUserdata);
        lastStepCompletion = null;
        lastStepUserdata = null;
        if (!result.equals(MemorySegment.NULL)) {
            handedOutResults.add(result.address());
        }
        MethodHandle call = linker.downcallHandle(completion, Ffi.Callbacks.STEP_COMPLETION);
        try {
            call.invokeWithArguments(ok, result, errorJson, userdata);
        } catch (Throwable t) {
            throw new AssertionError("settling the step threw out of the stub", t);
        }
    }

    private MemorySegment buildResult(
            long index,
            List<StepTrackSpec> specs,
            double elapsed,
            List<StepFileSpec> fileSpecs,
            @Nullable String error) {
        List<NativeTrack> tracks = new ArrayList<>();
        for (StepTrackSpec spec : specs) {
            List<NativeFrame> frames = new ArrayList<>();
            for (StepFrameSpec frame : spec.frames()) {
                MemorySegment shape =
                        frame.shape().length == 0 ? MemorySegment.NULL : arena.allocateFrom(JAVA_INT, frame.shape());
                MemorySegment data = frame.data().length == 0
                        ? MemorySegment.NULL
                        : arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, frame.data());
                byte[] tag = frame.metadata();
                // An attached but empty tag must still be a non-NULL pointer: that is how the header
                // tells it apart from no tag at all.
                MemorySegment metadata = tag == null
                        ? MemorySegment.NULL
                        : tag.length == 0
                                ? arena.allocate(1)
                                : arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, tag);
                frames.add(new NativeFrame(
                        arena.allocateFrom(frame.encoding()),
                        arena.allocateFrom(frame.dtype()),
                        shape,
                        frame.shape().length,
                        data,
                        frame.data().length,
                        metadata,
                        tag == null ? 0 : tag.length,
                        frame.captureTimeUs()));
            }
            tracks.add(new NativeTrack(
                    arena.allocateFrom(spec.name()), arena.allocateFrom(spec.kind()), spec.rate(), frames));
        }
        MemorySegment address = arena.allocate(8);
        List<NativeFile> files = new ArrayList<>();
        for (StepFileSpec file : fileSpecs) {
            files.add(new NativeFile(
                    arena.allocateFrom(file.name()), arena.allocateFrom(file.contentType()), file.size()));
        }
        MemorySegment errorText = error == null ? MemorySegment.NULL : arena.allocateFrom(error);
        liveResults.put(address.address(), new NativeStepResult(index, tracks, elapsed, files, errorText));
        return address;
    }

    private @Nullable NativeStepResult live(MemorySegment result) {
        if (result.equals(MemorySegment.NULL)) {
            return null;
        }
        NativeStepResult found = liveResults.get(result.address());
        if (found == null) {
            // A real library would be reading freed memory here. Failing loudly is the fake's job.
            throw new AssertionError("a step result was read after it was freed, or was never handed out");
        }
        return found;
    }

    private @Nullable NativeTrack track(MemorySegment result, int t) {
        NativeStepResult found = live(result);
        return found == null || t < 0 || t >= found.tracks().size()
                ? null
                : found.tracks().get(t);
    }

    private @Nullable NativeFrame frame(MemorySegment result, int t, int f) {
        NativeTrack found = track(result, t);
        return found == null || f < 0 || f >= found.frames().size()
                ? null
                : found.frames().get(f);
    }

    private long stepResultStep(MemorySegment result) {
        NativeStepResult found = live(result);
        return found == null ? 0 : found.step();
    }

    private double stepResultElapsed(MemorySegment result) {
        NativeStepResult found = live(result);
        return found == null ? 0.0 : found.elapsed();
    }

    private MemorySegment stepResultError(MemorySegment result) {
        NativeStepResult found = live(result);
        return found == null ? MemorySegment.NULL : found.error();
    }

    private @Nullable NativeFile file(MemorySegment result, int f) {
        NativeStepResult found = live(result);
        return found == null || f < 0 || f >= found.files().size()
                ? null
                : found.files().get(f);
    }

    private int stepResultFileCount(MemorySegment result) {
        NativeStepResult found = live(result);
        return found == null ? 0 : found.files().size();
    }

    private MemorySegment stepResultFileName(MemorySegment result, int f) {
        NativeFile found = file(result, f);
        return found == null ? MemorySegment.NULL : found.name();
    }

    private MemorySegment stepResultFileContentType(MemorySegment result, int f) {
        NativeFile found = file(result, f);
        return found == null ? MemorySegment.NULL : found.contentType();
    }

    private long stepResultFileSize(MemorySegment result, int f) {
        NativeFile found = file(result, f);
        return found == null ? 0 : found.size();
    }

    private int stepResultTrackCount(MemorySegment result) {
        NativeStepResult found = live(result);
        return found == null ? 0 : found.tracks().size();
    }

    private MemorySegment stepResultTrackName(MemorySegment result, int t) {
        NativeTrack found = track(result, t);
        return found == null ? MemorySegment.NULL : found.name();
    }

    private MemorySegment stepResultTrackKind(MemorySegment result, int t) {
        NativeTrack found = track(result, t);
        return found == null ? MemorySegment.NULL : found.kind();
    }

    private double stepResultTrackRate(MemorySegment result, int t) {
        NativeTrack found = track(result, t);
        return found == null ? 0.0 : found.rate();
    }

    private int stepResultFrameCount(MemorySegment result, int t) {
        NativeTrack found = track(result, t);
        return found == null ? 0 : found.frames().size();
    }

    private MemorySegment stepResultFrameEncoding(MemorySegment result, int t, int f) {
        NativeFrame found = frame(result, t, f);
        return found == null ? MemorySegment.NULL : found.encoding();
    }

    private MemorySegment stepResultFrameDtype(MemorySegment result, int t, int f) {
        NativeFrame found = frame(result, t, f);
        return found == null ? MemorySegment.NULL : found.dtype();
    }

    @SuppressWarnings("restricted") // reinterpret: writing the binding's own out-parameter
    private MemorySegment stepResultFrameShape(MemorySegment result, int t, int f, MemorySegment ndim) {
        NativeFrame found = frame(result, t, f);
        ndim.reinterpret(Integer.BYTES).set(JAVA_INT, 0, found == null ? 0 : found.ndim());
        return found == null ? MemorySegment.NULL : found.shape();
    }

    @SuppressWarnings("restricted") // reinterpret: writing the binding's own out-parameter
    private MemorySegment stepResultFrameData(MemorySegment result, int t, int f, MemorySegment length) {
        NativeFrame found = frame(result, t, f);
        length.reinterpret(Long.BYTES)
                .set(java.lang.foreign.ValueLayout.JAVA_LONG, 0, found == null ? 0 : found.length());
        return found == null ? MemorySegment.NULL : found.data();
    }

    @SuppressWarnings("restricted") // reinterpret: writing the binding's own out-parameter
    private MemorySegment stepResultFrameMetadata(MemorySegment result, int t, int f, MemorySegment length) {
        NativeFrame found = frame(result, t, f);
        length.reinterpret(Long.BYTES)
                .set(java.lang.foreign.ValueLayout.JAVA_LONG, 0, found == null ? 0 : found.metadataLength());
        return found == null ? MemorySegment.NULL : found.metadata();
    }

    @SuppressWarnings("restricted") // reinterpret: writing the binding's own out-parameter
    private int stepResultFrameCaptureTimeUs(MemorySegment result, int t, int f, MemorySegment captured) {
        NativeFrame found = frame(result, t, f);
        if (found == null || found.captureTimeUs() == null) {
            return 0;
        }
        captured.reinterpret(Long.BYTES).set(java.lang.foreign.ValueLayout.JAVA_LONG, 0, found.captureTimeUs());
        return 1;
    }

    private void stepResultFree(MemorySegment result) {
        if (result.equals(MemorySegment.NULL)) {
            return;
        }
        freedResults.add(result.address());
        liveResults.remove(result.address());
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
