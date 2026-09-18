package inc.reactor.sdk.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Function pointers the FFI can call back into, that cannot take the process with them.
 *
 * <p>An exception escaping an upcall stub is not catchable by the caller and not survivable by the
 * JVM: the runtime prints {@code Uncaught exception in upcall} and aborts. Handler code is host
 * code and host code has bugs, so one typo in one status handler would end a healthy process.
 *
 * <p>The guard is structural rather than a rule to remember. Every stub is built here, and this is
 * the only place that calls {@link Linker#upcallStub}; the target is wrapped in a catch-all before
 * the stub exists, so there is no way to ask for an unguarded one.
 */
public final class Upcalls {

    private static final Linker LINKER = linker();
    private static final System.Logger LOG = System.getLogger(Upcalls.class.getName());

    /**
     * Messages already reported, so a handler that throws on every frame reports once rather than
     * at the frame rate. Never cleared: the set is bounded by the number of distinct failures, and
     * a binding that produces an unbounded number of distinct ones has a worse problem than this
     * map.
     */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private static final MethodHandle SWALLOW;

    static {
        try {
            SWALLOW = MethodHandles.lookup()
                    .findStatic(Upcalls.class, "swallow", MethodType.methodType(void.class, Throwable.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Upcalls() {}

    /**
     * Wraps {@code target} so nothing it throws can escape, and hands the FFI a pointer to it.
     *
     * <p>The stub lives as long as {@code arena} does. That arena is the binding's whole teardown
     * discipline: the FFI holds a raw pointer to this stub, which no garbage collector can see, so
     * the arena must not be closed until {@code reactor_destroy} has returned 0 — and must never be
     * closed on a -1, where a callback is still running.
     *
     * @param target what to run; must return {@code void}, as every callback in the header does
     * @param descriptor the C signature, from {@link Ffi}
     * @param arena the lifetime the stub belongs to
     * @return a function pointer for the FFI
     */
    @SuppressWarnings("restricted") // upcallStub: the arena's lifetime is the contract, see above
    public static MemorySegment stub(MethodHandle target, FunctionDescriptor descriptor, Arena arena) {
        if (target.type().returnType() != void.class) {
            throw new IllegalArgumentException(
                    "an upcall target must return void — every callback in reactor_ffi.h does, and a"
                            + " return value would have nowhere to go once an exception is swallowed;"
                            + " got "
                            + target.type());
        }
        MethodHandle handler =
                MethodHandles.dropArguments(SWALLOW, 1, target.type().parameterList());
        MethodHandle guarded = MethodHandles.catchException(target, Throwable.class, handler);
        return LINKER.upcallStub(guarded, descriptor, arena);
    }

    /** Whether this message has been reported before. Exposed for the tests that pin the guard. */
    static boolean alreadyReported(String message) {
        return REPORTED.contains(message);
    }

    private static void swallow(Throwable thrown) {
        // Nothing in here may throw. Formatting a user object could, so the message is built from
        // the throwable's own class name and message and nothing else.
        String message = thrown.getClass().getName() + ": " + thrown.getMessage();
        if (REPORTED.add(message)) {
            LOG.log(
                    System.Logger.Level.ERROR,
                    "A Reactor callback threw. It was swallowed: an exception escaping a native"
                            + " upcall aborts the JVM. Further occurrences of this same failure are"
                            + " not reported. "
                            + message);
        }
    }

    @SuppressWarnings("restricted") // nativeLinker: this class exists to call it exactly once
    private static Linker linker() {
        return Linker.nativeLinker();
    }
}
