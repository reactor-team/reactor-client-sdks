package inc.reactor.sdk.internal;

import inc.reactor.sdk.ReactorException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An operation whose completion is not bounded by {@code reactor_destroy}.
 *
 * <p>Two calls in the header sit outside that promise and say so: {@code reactor_fetch_jwt} takes
 * no handle at all, and {@code reactor_download_clip} is documented as outliving the handle it was
 * given. Their completions can arrive after the client is gone.
 *
 * <p>Putting one in the client's arena has two opposite failure modes, and both have been shipped
 * by a binding before. Free it with the client and a late callback writes through a dangling
 * pointer. Leave it out of teardown and the caller's future is never settled, so they wait for the
 * life of the process.
 *
 * <p>So this owns itself. Its arena holds only its own stub, and only the completion closes it —
 * exactly once, after settling — so there is nothing for teardown to free and nothing for a late
 * callback to find missing.
 */
public final class DetachedCompletion<T> {

    private static final MethodHandle ON_COMPLETION;

    static {
        try {
            ON_COMPLETION = MethodHandles.lookup()
                    .findVirtual(
                            DetachedCompletion.class,
                            "onCompletion",
                            MethodType.methodType(
                                    void.class,
                                    int.class,
                                    MemorySegment.class,
                                    MemorySegment.class,
                                    MemorySegment.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final String operation;
    private final Completions.Decoder<T> decoder;
    private final CompletableFuture<T> future;
    private final Arena arena;
    private final MemorySegment callback;
    private final AtomicBoolean settled = new AtomicBoolean();

    private DetachedCompletion(String operation, Completions.Decoder<T> decoder, CompletableFuture<T> future) {
        this.operation = operation;
        this.decoder = decoder;
        this.future = future;
        // Shared, not confined: the completion arrives on a thread this one has never met.
        this.arena = Arena.ofShared();
        this.callback = Upcalls.stub(ON_COMPLETION.bindTo(this), Ffi.Callbacks.COMPLETION, arena);
    }

    /**
     * Starts an operation that outlives the client.
     *
     * @param operation which call this is
     * @param decoder how to read a successful payload
     * @param invoke makes the native call, given the callback and the userdata to pass it
     * @return the caller's future
     */
    public static <T> CompletableFuture<T> start(String operation, Completions.Decoder<T> decoder, Invoker invoke) {
        CompletableFuture<T> future = new CompletableFuture<>();
        DetachedCompletion<T> ticket = new DetachedCompletion<>(operation, decoder, future);
        try {
            invoke.call(ticket.callback, MemorySegment.NULL);
        } catch (RuntimeException notStarted) {
            // The FFI never took the callback, so nothing will ever call it: settle here and give
            // the arena back rather than leaking a stub nobody can reach.
            ticket.settleOnce(() -> future.completeExceptionally(notStarted));
            throw notStarted;
        }
        return future;
    }

    /** Makes the native call for a detached operation. */
    @FunctionalInterface
    public interface Invoker {
        /**
         * @param callback the completion function pointer
         * @param userdata what to pass through; this ticket needs none, so it is {@code NULL}
         */
        void call(MemorySegment callback, MemorySegment userdata);
    }

    private void onCompletion(int ok, MemorySegment resultJson, MemorySegment errorJson, MemorySegment userdata) {
        String result = NativeStrings.borrow(resultJson);
        String error = NativeStrings.borrow(errorJson);
        settleOnce(() -> {
            if (ok == 0) {
                future.completeExceptionally(ErrorPayloads.parse(error, operation));
                return;
            }
            T value;
            try {
                value = decoder.decode(result);
            } catch (RuntimeException decodeFailed) {
                future.completeExceptionally(ReactorException.of(
                        inc.reactor.sdk.ErrorCode.DECODE_FAILED.code(),
                        "the reply to " + operation + " could not be understood: " + decodeFailed.getMessage(),
                        null,
                        operation,
                        null));
                return;
            }
            future.complete(value);
        });
    }

    private void settleOnce(Runnable settle) {
        if (!settled.compareAndSet(false, true)) {
            return;
        }
        try {
            settle.run();
        } finally {
            // The FFI promises this fires exactly once, so this is the moment the stub stops being
            // reachable from native code — and the only moment it is safe to release.
            arena.close();
        }
    }

    /** Whether this ticket has settled. Exposed for the tests that pin the once-only rule. */
    boolean isSettled() {
        return settled.get();
    }

    /** What a caller is told when their client closed while this was still running. */
    public static ReactorException abandoned(String operation) {
        return ReactorException.of(
                inc.reactor.sdk.ErrorCode.ABORTED.code(),
                "the client closed while " + operation + " was still running. The native operation was"
                        + " not cancelled and may still be writing — it is not bounded by the client's"
                        + " lifetime.",
                null,
                operation,
                null);
    }
}
