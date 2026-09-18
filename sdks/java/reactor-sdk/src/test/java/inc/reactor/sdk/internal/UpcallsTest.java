package inc.reactor.sdk.internal;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The guard that keeps a handler's bug from being a process's death.
 *
 * <p>These tests are worth more than they look. An exception escaping an upcall is not a failed
 * assertion — the JVM prints {@code Uncaught exception in upcall} and aborts, so without the
 * catch-all the first test here does not fail, it takes the whole test run with it. Stash the guard
 * and watch: that crash is the only evidence the fix is the fix.
 */
final class UpcallsTest {

    private static final AtomicInteger CALLS = new AtomicInteger();

    @Test
    @DisplayName("an exception thrown inside an upcall does not reach the FFI, or end the JVM")
    void anExceptionInAnUpcallIsSwallowed() throws Throwable {
        CALLS.set(0);
        FunctionDescriptor descriptor = FunctionDescriptor.ofVoid(JAVA_INT);

        try (Arena arena = Arena.ofShared()) {
            MemorySegment stub = Upcalls.stub(throwingTarget(), descriptor, arena);
            MethodHandle asIfFromC = downcall(stub, descriptor);

            // Called through a real function pointer, the way the FFI would call it.
            asIfFromC.invokeExact(1);
            asIfFromC.invokeExact(2);

            assertEquals(2, CALLS.get(), "the target ran both times; only what it threw was swallowed");
        }
    }

    @Test
    @DisplayName("a failure is reported once, not at the rate the events arrive")
    void repeatedFailuresAreReportedOnce() throws Throwable {
        FunctionDescriptor descriptor = FunctionDescriptor.ofVoid(JAVA_INT);
        try (Arena arena = Arena.ofShared()) {
            MethodHandle asIfFromC = downcall(Upcalls.stub(throwingTarget(), descriptor, arena), descriptor);
            asIfFromC.invokeExact(1);

            assertTrue(
                    Upcalls.alreadyReported(IllegalStateException.class.getName() + ": a handler with a bug in it"),
                    "the first occurrence is remembered, so a handler that throws on every frame"
                            + " does not log at the frame rate");
        }
    }

    @Test
    @DisplayName("a target that returns a value is refused — there would be nowhere to put it")
    void refusesANonVoidTarget() {
        try (Arena arena = Arena.ofShared()) {
            MethodHandle returnsAnInt = MethodHandles.constant(int.class, 7);
            IllegalArgumentException thrown = assertThrows(
                    IllegalArgumentException.class,
                    () -> Upcalls.stub(returnsAnInt, FunctionDescriptor.of(JAVA_INT), arena));
            assertTrue(thrown.getMessage().contains("void"), thrown.getMessage());
        }
    }

    private static MethodHandle throwingTarget() throws ReflectiveOperationException {
        return MethodHandles.lookup()
                .findStatic(UpcallsTest.class, "alwaysThrows", MethodType.methodType(void.class, int.class));
    }

    @SuppressWarnings("restricted") // downcallHandle: calling our own stub the way C would
    private static MethodHandle downcall(MemorySegment stub, FunctionDescriptor descriptor) {
        return Linker.nativeLinker().downcallHandle(stub, descriptor);
    }

    private static void alwaysThrows(int ignored) {
        CALLS.incrementAndGet();
        throw new IllegalStateException("a handler with a bug in it");
    }
}
