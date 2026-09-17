package inc.reactor.sdk.internal;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import inc.reactor.sdk.AbortedException;
import inc.reactor.sdk.DecodeFailedException;
import inc.reactor.sdk.RateLimitedException;
import inc.reactor.sdk.ReactorException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The completion bridge, driven through a real function pointer rather than by calling the
 * dispatch method directly — a test that calls the helper proves the helper works.
 */
final class CompletionsTest {

    @Test
    @DisplayName("a successful completion decodes, then settles")
    void successDecodesThenSettles() throws Exception {
        try (Arena arena = Arena.ofShared()) {
            Completions completions = new Completions(arena);
            CompletableFuture<String> future = new CompletableFuture<>();
            Completions.Ticket ticket = completions.register("get_stats", json -> json + "!", future);

            fire(arena, ticket, 1, "{\"a\":1}", null);

            assertEquals("{\"a\":1}!", future.get());
            assertEquals(0, completions.pendingCount(), "a settled operation must not stay registered");
        }
    }

    @Test
    @DisplayName("a successful payload that will not decode is a decode failure, not an empty answer")
    void aPayloadThatWillNotDecodeIsADecodeFailure() {
        try (Arena arena = Arena.ofShared()) {
            Completions completions = new Completions(arena);
            CompletableFuture<String> future = new CompletableFuture<>();
            Completions.Ticket ticket = completions.register(
                    "request_schema",
                    json -> {
                        throw new IllegalArgumentException("no 'commands' field");
                    },
                    future);

            fire(arena, ticket, 1, "{}", null);

            ExecutionException thrown = assertThrows(ExecutionException.class, future::get);
            DecodeFailedException cause = assertInstanceOf(DecodeFailedException.class, thrown.getCause());
            assertEquals("DECODE_FAILED", cause.code());
            assertTrue(cause.getMessage().contains("request_schema"), cause.getMessage());
            // The point of the whole rule: the caller does not get an empty schema they cannot
            // tell apart from a model that declares nothing.
            assertFalse(future.isCompletedExceptionally() && future.isCancelled());
        }
    }

    @Test
    @DisplayName("a failed completion becomes the typed exception its code names")
    void failureBecomesATypedException() {
        try (Arena arena = Arena.ofShared()) {
            Completions completions = new Completions(arena);
            CompletableFuture<String> future = new CompletableFuture<>();
            Completions.Ticket ticket = completions.register("connect", json -> json, future);

            fire(
                    arena,
                    ticket,
                    0,
                    null,
                    "{\"code\":\"RATE_LIMITED\",\"message\":\"slow down\",\"recoverable\":true,"
                            + "\"status\":429,\"operation\":\"connect\",\"retry_after_ms\":2500}");

            ExecutionException thrown = assertThrows(ExecutionException.class, future::get);
            RateLimitedException cause = assertInstanceOf(RateLimitedException.class, thrown.getCause());
            assertEquals(429, cause.status());
            assertEquals("connect", cause.operation());
            assertEquals(2500L, cause.retryAfterMs());
            assertTrue(cause.isRecoverable());
        }
    }

    @Test
    @DisplayName("a completion arriving after close finds nothing, and settles nothing twice")
    void aLateCompletionFindsNothing() {
        try (Arena arena = Arena.ofShared()) {
            Completions completions = new Completions(arena);
            CompletableFuture<String> future = new CompletableFuture<>();
            Completions.Ticket ticket = completions.register("send_command", json -> json, future);

            completions.settleAll(DetachedCompletion.abandoned("send_command"));
            ExecutionException thrown = assertThrows(ExecutionException.class, future::get);
            assertInstanceOf(AbortedException.class, thrown.getCause());

            // The FFI does not know the client closed. This is the callback arriving anyway.
            fire(arena, ticket, 1, "{}", null);

            assertInstanceOf(
                    AbortedException.class,
                    assertThrows(ExecutionException.class, future::get).getCause(),
                    "the late completion must not overwrite what the caller was already told");
        }
    }

    @Test
    @DisplayName("close settles every pending operation, rather than leaving callers waiting forever")
    void closeSettlesEverythingPending() {
        try (Arena arena = Arena.ofShared()) {
            Completions completions = new Completions(arena);
            CompletableFuture<String> one = new CompletableFuture<>();
            CompletableFuture<String> two = new CompletableFuture<>();
            completions.register("connect", json -> json, one);
            completions.register("get_stats", json -> json, two);
            assertEquals(2, completions.pendingCount());

            completions.settleAll(DetachedCompletion.abandoned("close"));

            assertTrue(one.isCompletedExceptionally());
            assertTrue(two.isCompletedExceptionally());
            assertEquals(0, completions.pendingCount());
        }
    }

    @Test
    @DisplayName("an error payload that is not JSON still becomes an error, not a lost failure")
    void anUnparseableErrorIsStillAnError() {
        try (Arena arena = Arena.ofShared()) {
            Completions completions = new Completions(arena);
            CompletableFuture<String> future = new CompletableFuture<>();
            Completions.Ticket ticket = completions.register("connect", json -> json, future);

            fire(arena, ticket, 0, null, "this is not JSON");

            ReactorException cause = assertInstanceOf(
                    ReactorException.class,
                    assertThrows(ExecutionException.class, future::get).getCause());
            assertEquals("DECODE_FAILED", cause.code());
            assertTrue(cause.getMessage().contains("this is not JSON"), cause.getMessage());
        }
    }

    /** Calls the completion callback the way the FFI would: through its function pointer. */
    @SuppressWarnings("restricted") // downcallHandle: calling our own stub as C would
    private static void fire(Arena arena, Completions.Ticket ticket, int ok, String resultJson, String errorJson) {
        MethodHandle call = Linker.nativeLinker()
                .downcallHandle(
                        ticket.callback(),
                        FunctionDescriptor.ofVoid(
                                JAVA_INT,
                                java.lang.foreign.ValueLayout.ADDRESS,
                                java.lang.foreign.ValueLayout.ADDRESS,
                                java.lang.foreign.ValueLayout.ADDRESS));
        MemorySegment result = resultJson == null ? MemorySegment.NULL : arena.allocateFrom(resultJson);
        MemorySegment error = errorJson == null ? MemorySegment.NULL : arena.allocateFrom(errorJson);
        try {
            call.invokeExact(ok, result, error, ticket.userdata());
        } catch (Throwable t) {
            throw new AssertionError("the completion callback threw out of the stub", t);
        }
    }
}
