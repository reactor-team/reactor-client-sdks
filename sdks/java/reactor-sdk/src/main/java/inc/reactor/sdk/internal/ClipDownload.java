package inc.reactor.sdk.internal;

import inc.reactor.sdk.DownloadedClip;
import inc.reactor.sdk.ErrorCode;
import inc.reactor.sdk.ReactorException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;
import org.jspecify.annotations.Nullable;

/**
 * A clip download, which outlives the client that started it.
 *
 * <p>The header is explicit: {@code reactor_download_clip} is not bounded by {@code
 * reactor_destroy}. Its progress and completion callbacks can arrive after the client is gone, and
 * there are two opposite ways to get that wrong — both of which a binding has shipped before:
 *
 * <ul>
 *   <li>Free the callbacks with the client, and a late progress callback writes through a pointer
 *       into memory that has been released. AddressSanitizer named exactly this on the C++ SDK.
 *   <li>Leave the operation out of teardown, and the caller's future is never settled, so they wait
 *       for the life of the process. That was the same review's other finding.
 * </ul>
 *
 * <p>So both halves are handled separately. Closing the client <b>settles the caller</b> and
 * nothing else; only the native completion, which the FFI promises fires exactly once, <b>closes
 * the arena</b>. Between the two, a progress callback arrives, finds the operation already settled,
 * and returns having touched nothing.
 *
 * <p>The download is also held in a static set until that completion. Nothing else references it:
 * the arena holds the stubs, the stubs bind this object, and the FFI's pointer to them is not
 * something a garbage collector can see. Without that reference the whole graph is unreachable
 * while native code is still about to call into it.
 */
// Public because the facade builds downloads through it; the package is never exported.
public final class ClipDownload {

    /**
     * Downloads the FFI may still call back about.
     *
     * <p>Not a leak: every entry is removed by its own completion, which fires exactly once. It is
     * what keeps the object graph the FFI points into reachable in the meantime.
     */
    private static final Set<ClipDownload> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private static final MethodHandle ON_COMPLETION;
    private static final MethodHandle ON_PROGRESS;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            ON_COMPLETION = lookup.findVirtual(
                    ClipDownload.class,
                    "onCompletion",
                    MethodType.methodType(
                            void.class, int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class));
            ON_PROGRESS = lookup.findVirtual(
                    ClipDownload.class,
                    "onProgress",
                    MethodType.methodType(void.class, int.class, int.class, MemorySegment.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Told how far a download has got, in segments written out of segments known. */
    @FunctionalInterface
    public interface Progress {
        /**
         * @param done segments written so far
         * @param total segments the clip holds
         */
        void report(int done, int total);
    }

    private final CompletableFuture<DownloadedClip> future;
    private final @Nullable Progress progress;
    private final Arena arena;
    private final MemorySegment completionStub;
    private final MemorySegment progressStub;
    private final AtomicBoolean settled = new AtomicBoolean();

    private static final System.Logger LOG = System.getLogger(ClipDownload.class.getName());

    private final java.util.concurrent.atomic.AtomicBoolean progressFailed =
            new java.util.concurrent.atomic.AtomicBoolean();

    private ClipDownload(CompletableFuture<DownloadedClip> future, @Nullable Progress progress) {
        this.future = future;
        this.progress = progress;
        // Shared: progress arrives on the download's own thread, which this one has never met.
        this.arena = Arena.ofShared();
        this.completionStub = Upcalls.stub(ON_COMPLETION.bindTo(this), Ffi.Callbacks.COMPLETION, arena);
        this.progressStub = Upcalls.stub(ON_PROGRESS.bindTo(this), Ffi.Callbacks.PROGRESS, arena);
    }

    /**
     * Starts a download.
     *
     * @param future the caller's future
     * @param progress what to report progress to, or {@code null}
     * @param invoke makes the native call, given the progress and completion pointers
     * @return the download, which the client keeps until it settles
     */
    static ClipDownload start(CompletableFuture<DownloadedClip> future, @Nullable Progress progress, Invoker invoke) {
        ClipDownload download = register(future, progress);
        download.begin(invoke);
        return download;
    }

    /**
     * Creates a download and makes it visible, without starting it.
     *
     * <p>Split from {@link #begin} so a caller can register it somewhere of its own before the
     * native call — a teardown that takes its snapshot while this is mid-flight has to be able to
     * find it, and a download that becomes visible only afterwards can fall into exactly that gap
     * and leave its caller waiting for the life of the process.
     */
    static ClipDownload register(CompletableFuture<DownloadedClip> future, @Nullable Progress progress) {
        ClipDownload download = new ClipDownload(future, progress);
        IN_FLIGHT.add(download);
        return download;
    }

    /**
     * Hands the stubs to the FFI.
     *
     * @param invoke makes the native call
     */
    void begin(Invoker invoke) {
        try {
            invoke.call(progressStub, completionStub);
        } catch (RuntimeException notStarted) {
            // The FFI never took the pointers, so nothing will ever call them: this is the one path
            // where releasing the arena here is correct rather than a use-after-free.
            IN_FLIGHT.remove(this);
            settled.set(true);
            arena.close();
            future.completeExceptionally(notStarted);
            throw notStarted;
        }
    }

    /** Makes the native call for a download. */
    @FunctionalInterface
    interface Invoker {
        /**
         * @param progressStub where to report progress
         * @param completionStub where to report the result
         */
        void call(MemorySegment progressStub, MemorySegment completionStub);
    }

    /**
     * Settles the caller because their client is closing, and releases nothing.
     *
     * <p>The download is still running: it was never bounded by the handle. Telling the caller it
     * was aborted would be wrong, and closing the arena here would unbind stubs the FFI is about to
     * call.
     */
    void abandon() {
        if (!settled.compareAndSet(false, true)) {
            return;
        }
        future.completeExceptionally(ReactorException.of(
                ErrorCode.ABORTED.code(),
                "the client closed while this clip was downloading. The download was not cancelled —"
                        + " it is not bounded by the client's lifetime — so the file may still arrive.",
                null,
                "download_clip",
                null));
    }

    /** How many downloads the FFI may still call back about. The endurance suite watches this. */
    public static int inFlight() {
        return IN_FLIGHT.size();
    }

    /** Visible for the test that a throwing listener must not take the process down with it. */
    void onProgress(int done, int total, MemorySegment userdata) {
        if (settled.get()) {
            // The client closed and the caller has already been told. Reporting progress to a
            // settled future would do nothing; reading anything the client owned would be worse.
            return;
        }
        Progress report = progressFailed.get() ? null : progress;
        if (report == null) {
            return;
        }
        try {
            report.report(done, total);
        } catch (RuntimeException | Error thrown) {
            // This method is the target of an upcall stub, and an exception cannot cross native
            // code: it does not propagate to any caller, and the JVM terminates rather than guess.
            // A progress listener is ordinary application code and is entitled to have a bug in
            // it; killing the process over one is not a trade this SDK gets to make.
            //
            // Reported once and then dropped. A listener that throws on every chunk throws at the
            // rate the chunks arrive, and a log that repeats that is a log nobody reads.
            if (progressFailed.compareAndSet(false, true)) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        "a clip progress listener threw; no further progress will be reported for this download",
                        thrown);
            }
        }
    }

    private void onCompletion(int ok, MemorySegment resultJson, MemorySegment errorJson, MemorySegment userdata) {
        String result = NativeStrings.borrow(resultJson);
        String error = NativeStrings.borrow(errorJson);
        try {
            if (settled.compareAndSet(false, true)) {
                if (ok == 0) {
                    future.completeExceptionally(ErrorPayloads.parse(error, "download_clip"));
                } else {
                    settleWith(result);
                }
            }
        } finally {
            // The FFI promises this fires exactly once, so this is the moment nothing native can
            // reach these stubs again — and the only moment the arena may be released. It runs even
            // when the caller was settled by a close, because the arena still has to go.
            IN_FLIGHT.remove(this);
            arena.close();
        }
    }

    private void settleWith(@Nullable String result) {
        try {
            future.complete(DownloadedClip.from(JsonBridge.parse(result == null || result.isBlank() ? "{}" : result)));
        } catch (RuntimeException decodeFailed) {
            future.completeExceptionally(ReactorException.of(
                    ErrorCode.DECODE_FAILED.code(),
                    "the download finished and its result could not be read: " + decodeFailed.getMessage(),
                    null,
                    "download_clip",
                    null));
        }
    }

    /** Reports progress to a consumer that only wants the count done. */
    static Progress of(@Nullable LongConsumer done) {
        return done == null ? (ignored, total) -> {} : (written, total) -> done.accept(written);
    }
}
