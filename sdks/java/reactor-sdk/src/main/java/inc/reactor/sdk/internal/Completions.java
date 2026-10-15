package inc.reactor.sdk.internal;

import inc.reactor.sdk.ReactorException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    /**
     * Turns an owned native result into what the caller asked for.
     *
     * <p>Copies out whatever it keeps: the result is released as soon as this returns, whether it
     * returned or threw.
     */
    @FunctionalInterface
    public interface OwnedDecoder<T> {
        /**
         * @param result the result the completion handed over, never {@code NULL}
         * @return the decoded value, holding nothing that points into {@code result}
         * @throws RuntimeException when the result cannot be understood
         */
        T decode(MemorySegment result);
    }

    /** Releases an owned native result — {@code reactor_step_result_free}, for steps. */
    @FunctionalInterface
    public interface Release {
        /** @param result the result to release, never {@code NULL} */
        void release(MemorySegment result);
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

    /** Reads a completion's {@code result} pointer, whichever kind of pointer it is. */
    @FunctionalInterface
    private interface RawDecoder<T> {
        T decode(MemorySegment result);
    }

    private record Pending<T>(String operation, RawDecoder<T> decoder, CompletableFuture<T> future) {}

    private static final MethodHandle ON_COMPLETION;
    private static final MethodHandle ON_OWNED_COMPLETION;

    static {
        try {
            MethodType completion = MethodType.methodType(
                    void.class, int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class);
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            ON_COMPLETION = lookup.findVirtual(Completions.class, "onCompletion", completion);
            ON_OWNED_COMPLETION = lookup.findVirtual(Completions.class, "onOwnedCompletion", completion);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final ConcurrentHashMap<Long, Pending<?>> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private final MemorySegment callback;

    /**
     * The completion for operations that hand over an owned result, or {@code NULL} when this
     * registry was built without a way to release one.
     *
     * <p>A stub of its own rather than a flag on the entry, because the case that matters is the
     * one with no entry: a completion arriving after {@link #settleAll} has already answered the
     * caller still carries a result somebody has to free, and by then the map has forgotten what
     * the operation was. Which stub was called is the one thing left that says so.
     */
    private final MemorySegment ownedCallback;

    private final @Nullable Release ownedRelease;

    /**
     * @param arena the lifetime the completion stub belongs to — the client's, so it outlives every
     *     operation the client can still be called back about
     */
    public Completions(Arena arena) {
        this(arena, null);
    }

    /**
     * @param arena the lifetime the completion stubs belong to, as above
     * @param ownedRelease how to release an owned result, or {@code null} for a registry that only
     *     ever carries borrowed JSON — {@link #registerOwned} is refused then
     */
    public Completions(Arena arena, @Nullable Release ownedRelease) {
        this.callback = Upcalls.stub(ON_COMPLETION.bindTo(this), Ffi.Callbacks.COMPLETION, arena);
        this.ownedRelease = ownedRelease;
        this.ownedCallback = ownedRelease == null
                ? MemorySegment.NULL
                : Upcalls.stub(ON_OWNED_COMPLETION.bindTo(this), Ffi.Callbacks.STEP_COMPLETION, arena);
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
        pending.put(id, new Pending<>(operation, result -> decoder.decode(NativeStrings.borrow(result)), future));
        return new Ticket(callback, MemorySegment.ofAddress(id));
    }

    /**
     * Registers an operation whose completion hands over an owned result, and returns what to hand
     * the FFI.
     *
     * <p>The result is released exactly once whatever happens to the operation: after decoding on
     * success, unread on a failure that somehow carried one, and unread when the operation was
     * already settled by {@link #settleAll} — the case that would otherwise leak a whole step's
     * frames on every close that raced one.
     *
     * @param operation which call this is, for the error it may produce
     * @param decoder how to copy a successful result out
     * @param future the future to settle
     * @return the callback and userdata for the native call
     * @throws IllegalStateException when this registry was built without a {@link Release}
     */
    public <T> Ticket registerOwned(String operation, OwnedDecoder<T> decoder, CompletableFuture<T> future) {
        if (ownedRelease == null) {
            throw new IllegalStateException("this registry was built without a way to release an owned result");
        }
        long id = nextId.getAndIncrement();
        pending.put(id, new Pending<>(operation, result -> decodeOwned(operation, decoder, result), future));
        return new Ticket(ownedCallback, MemorySegment.ofAddress(id));
    }

    private static <T> T decodeOwned(String operation, OwnedDecoder<T> decoder, MemorySegment result) {
        if (result.equals(MemorySegment.NULL)) {
            // The header promises a result on success. Its absence is not an empty one.
            throw new IllegalStateException(operation + " succeeded with no result");
        }
        return decoder.decode(result);
    }

    /**
     * Settles one registered operation that never reached the FFI, and forgets it.
     *
     * <p>For the window between {@link #register} and the native call: if that call throws, nothing
     * will ever complete this ticket, and without this the entry sat in the map with its future
     * unsettled until the client closed. The caller who is holding that future waits forever.
     *
     * <p>One operation, not all of them. Settling the whole map was the other half of the same
     * mistake — one failed invocation aborted every command that happened to be in flight.
     *
     * @param ticket what {@link #register} answered
     * @param failure what to tell the caller
     */
    public void abandon(Ticket ticket, Throwable failure) {
        Pending<?> operation = pending.remove(ticket.userdata().address());
        if (operation != null) {
            operation.future().completeExceptionally(failure);
        }
    }

    /** How many operations are still waiting. The endurance suite watches this for a leak. */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * Settles everything pending when called, and stops finding it afterwards.
     *
     * <p>Called when the client closes. Leaving an operation out would leave its caller waiting for
     * the life of the process, which is the other half of the same bug as freeing one too early.
     * Only what it settles is removed: an operation registered while this runs stays findable, so
     * the {@link #abandon} that follows its refused call still settles it.
     *
     * @param why what to fail them with
     */
    public void settleAll(ReactorException why) {
        List<Map.Entry<Long, Pending<?>>> abandoned = new ArrayList<>(pending.entrySet());
        for (Map.Entry<Long, Pending<?>> one : abandoned) {
            if (pending.remove(one.getKey(), one.getValue())) {
                one.getValue().future().completeExceptionally(why);
            }
        }
    }

    private void onCompletion(int ok, MemorySegment resultJson, MemorySegment errorJson, MemorySegment userdata) {
        Pending<?> operation = pending.remove(userdata.address());
        if (operation == null) {
            // Already settled — a close that beat the completion, or a completion arriving twice.
            // Nothing to do, and nothing to complain about.
            return;
        }
        settle(operation, ok != 0, resultJson, NativeStrings.borrow(errorJson));
    }

    private void onOwnedCompletion(int ok, MemorySegment result, MemorySegment errorJson, MemorySegment userdata) {
        try {
            Pending<?> operation = pending.remove(userdata.address());
            if (operation == null) {
                // Already settled — a close or a re-mint answered the caller first. The result is
                // still this binding's to free, and the finally below does.
                return;
            }
            settle(operation, ok != 0, result, NativeStrings.borrow(errorJson));
        } finally {
            if (result != null && !result.equals(MemorySegment.NULL) && ownedRelease != null) {
                // After the decode, never before it: the decoder reads the result in place.
                ownedRelease.release(result);
            }
        }
    }

    private static <T> void settle(Pending<T> operation, boolean ok, MemorySegment result, @Nullable String errorJson) {
        if (!ok) {
            operation.future().completeExceptionally(ErrorPayloads.parse(errorJson, operation.operation()));
            return;
        }
        T value;
        try {
            // Decoded first, claimed second. The other order cannot report a bad payload: the
            // future is already done by the time the conversion throws.
            value = operation.decoder().decode(result);
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
