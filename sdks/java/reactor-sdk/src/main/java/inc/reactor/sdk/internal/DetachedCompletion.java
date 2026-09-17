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

    /**
     * Tickets the FFI may still call back about.
     *
     * <p>Nothing else reaches one: the arena holds the stub, the stub binds the ticket, and the
     * pointer the FFI holds is not something a garbage collector can see. Without this the whole
     * graph is collectable while native code is still about to call into it. Every entry is removed
     * by its own completion, which fires exactly once.
     */
    private static final java.util.Set<DetachedCompletion<?>> IN_FLIGHT =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final String operation;
    private final Completions.Decoder<T> decoder;
    private final CompletableFuture<T> future;
    private final Arena arena;
    private final AtomicBoolean released = new AtomicBoolean();
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
        IN_FLIGHT.add(ticket);
        try {
            invoke.call(ticket.callback, MemorySegment.NULL);
        } catch (RuntimeException notStarted) {
            // The FFI never took the callback, so nothing will ever call it: settle here and give
            // the arena back rather than leaking a stub nobody can reach.
            ticket.settleOnce(() -> future.completeExceptionally(notStarted));
            throw notStarted;
        } finally {
            // The FFI may complete before this call returns — fetch_jwt does exactly that when the
            // options it is given are invalid. The completion then runs inside the downcall, and
            // the downcall holds this arena's scope for its whole duration, because the callback
            // it was passed was allocated from it. So the close attempted from the completion
            // could not succeed, and the only thing that noticed was a log line: the upcall guard
            // swallows whatever escapes it, the caller's future completed, and the stub stayed
            // allocated for the life of the process.
            //
            // Here the downcall has returned and the scope is free.
            ticket.releaseIfSettled();
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
            // reachable from native code — and the first moment it is safe to release. Not always
            // a possible one: see releaseIfSettled.
            releaseIfSettled();
        }
    }

    /**
     * Closes the arena, once, if there is nothing left to use it.
     *
     * <p>Called from two places that cannot both be right on their own: the completion, which knows
     * the stub is finished with, and {@link #start}, which knows the native call has returned.
     * Whichever runs second is the one that succeeds when the first could not — and when the
     * completion arrives later, on an FFI thread, the first is also the only one.
     */
    private void releaseIfSettled() {
        if (!settled.get() || !released.compareAndSet(false, true)) {
            return;
        }
        try {
            arena.close();
        } catch (IllegalStateException stillInUse) {
            // A native call still holds this scope, which means the completion fired inside the
            // downcall that started it. Put the flag back so whoever returns from that call closes
            // it instead; there is exactly one such caller and it is already on its way here.
            released.set(false);
            return;
        }
        // Only now, and not at settlement: until the arena is actually gone this ticket still owns
        // memory native code was given, and teardown has to be able to find it. Leaving the set on
        // the failed attempt above would have hidden it for exactly that window.
        IN_FLIGHT.remove(this);
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
