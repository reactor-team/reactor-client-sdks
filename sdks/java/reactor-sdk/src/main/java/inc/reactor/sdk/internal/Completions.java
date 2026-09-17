package inc.reactor.sdk.internal;

import inc.reactor.sdk.ReactorException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * Async operations, from {@code reactor_completion_fn} to {@link CompletableFuture}.
 *
 * <p>One registry per client. Each operation gets an id, the id travels to the FFI as {@code
 * userdata}, and the completion finds its future by it — so nothing here holds a strong reference
 * to the public client, and a pending operation cannot keep a session alive.
 *
 * <p>Two rules this class exists to keep:
 *
 * <ul>
 *   <li><b>Decode before claiming the future.</b> A future settles once. Marking it done and then
 *       converting the payload means a field of the wrong type throws where nothing can be settled
 *       any more, and the caller gets a hang instead of the typed error they were promised.
 *   <li><b>A successful completion that will not parse is a decode failure.</b> Substituting an
 *       empty object would make a schema declaring nothing indistinguishable from a model declaring
 *       nothing. An <i>absent</i> payload is a different answer and still means "nothing to report".
 * </ul>
 */
public final class Completions {

    /** Turns a completion's {@code result_json} into what the caller asked for. */
    @FunctionalInterface
    public interface Decoder<T> {
        /**
         * @param resultJson the payload, or {@code null} when the operation reports none
         * @return the decoded value
         * @throws RuntimeException when the payload cannot be understood
         */
        T decode(@Nullable String resultJson);
    }

    /** Makes the native call for an operation, given what to hand it. */
    @FunctionalInterface
    public interface Invoker {
        /**
         * @param callback the completion function pointer
         * @param userdata what the completion finds this operation by
         */
        void call(MemorySegment callback, MemorySegment userdata);
    }

    /** What an operation needs to hand the FFI: the callback and the userdata that finds it again. */
    public record Ticket(MemorySegment callback, MemorySegment userdata) {}

    private record Pending<T>(String operation, Decoder<T> decoder, CompletableFuture<T> future) {}

    private static final MethodHandle ON_COMPLETION;

    static {
        try {
            ON_COMPLETION = MethodHandles.lookup()
                    .findVirtual(
                            Completions.class,
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

    private final ConcurrentHashMap<Long, Pending<?>> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private final MemorySegment callback;

    /**
     * @param arena the lifetime the completion stub belongs to — the client's, so it outlives every
     *     operation the client can still be called back about
     */
    public Completions(Arena arena) {
        this.callback = Upcalls.stub(ON_COMPLETION.bindTo(this), Ffi.Callbacks.COMPLETION, arena);
    }

    /**
     * Registers an operation and returns what to hand the FFI.
     *
     * @param operation which call this is, for the error it may produce
     * @param decoder how to read a successful payload
     * @param future the future to settle
     * @return the callback and userdata for the native call
     */
    public <T> Ticket register(String operation, Decoder<T> decoder, CompletableFuture<T> future) {
        long id = nextId.getAndIncrement();
        pending.put(id, new Pending<>(operation, decoder, future));
        return new Ticket(callback, MemorySegment.ofAddress(id));
    }

    /** How many operations are still waiting. The endurance suite watches this for a leak. */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * Settles everything still pending, and stops finding anything afterwards.
     *
     * <p>Called when the client closes. Leaving an operation out would leave its caller waiting for
     * the life of the process, which is the other half of the same bug as freeing one too early.
     *
     * @param why what to fail them with
     */
    public void settleAll(ReactorException why) {
        List<Pending<?>> abandoned = new ArrayList<>(pending.values());
        pending.clear();
        for (Pending<?> one : abandoned) {
            one.future().completeExceptionally(why);
        }
    }

    private void onCompletion(int ok, MemorySegment resultJson, MemorySegment errorJson, MemorySegment userdata) {
        Pending<?> operation = pending.remove(userdata.address());
        if (operation == null) {
            // Already settled — a close that beat the completion, or a completion arriving twice.
            // Nothing to do, and nothing to complain about.
            return;
        }
        settle(operation, ok != 0, NativeStrings.borrow(resultJson), NativeStrings.borrow(errorJson));
    }

    private static <T> void settle(
            Pending<T> operation, boolean ok, @Nullable String resultJson, @Nullable String errorJson) {
        if (!ok) {
            operation.future().completeExceptionally(ErrorPayloads.parse(errorJson, operation.operation()));
            return;
        }
        T value;
        try {
            // Decoded first, claimed second. The other order cannot report a bad payload: the
            // future is already done by the time the conversion throws.
            value = operation.decoder().decode(resultJson);
        } catch (RuntimeException decodeFailed) {
            operation
                    .future()
                    .completeExceptionally(ReactorException.of(
                            inc.reactor.sdk.ErrorCode.DECODE_FAILED.code(),
                            "the reply to " + operation.operation() + " could not be understood: "
                                    + decodeFailed.getMessage(),
                            null,
                            operation.operation(),
                            null));
            return;
        }
        operation.future().complete(value);
    }
}
