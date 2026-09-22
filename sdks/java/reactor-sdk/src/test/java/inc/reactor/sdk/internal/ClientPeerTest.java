package inc.reactor.sdk.internal;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import inc.reactor.sdk.ConnectionStatus;
import inc.reactor.sdk.ReactorException;
import inc.reactor.sdk.ReactorOptions;
import inc.reactor.sdk.ReactorSdk;
import inc.reactor.sdk.Subscription;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Client lifetime, against a fake library.
 *
 * <p>Four of the cases here end the process rather than fail a call if the code is wrong, which is
 * why they are tests and not review comments.
 */
final class ClientPeerTest {

    private static final ReactorOptions OPTIONS =
            ReactorOptions.builder("https://api.example.test", "owner/model").build();

    /** Runs events on the caller's thread, so a test can assert without waiting. */
    private static ReactorOptions inline() {
        return ReactorOptions.builder("https://api.example.test", "owner/model")
                .dispatcher(Runnable::run)
                .build();
    }

    private static ClientPeer peer(FakeNativeLibrary fake, ReactorOptions options) {
        return ClientPeer.create(options, arena -> Ffi.open(fake.lookup()));
    }

    /**
     * A peer that has been through a connect, and so has a native client behind it.
     *
     * <p>The handle is created on the first connect rather than with the peer, because that is
     * where the token is settled — so anything that needs the library to have been handed this
     * client's callbacks has to connect first. The connect is settled rather than left pending so
     * that a later {@code settleLastCall} in the same test answers the call the test made.
     */
    private static ClientPeer connected(FakeNativeLibrary fake, ReactorOptions options) {
        ClientPeer client = peer(fake, options);
        client.connect(null, null);
        fake.settleLastCall(true, "{}", null);
        fake.clearPendingCall();
        return client;
    }

    @Test
    @DisplayName("creation pins the synthetic audio module and reports this binding's own identity")
    void creationPinsSyntheticAudioAndReportsItself() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            ClientPeer client = connected(fake, OPTIONS);

            // 0 is synthetic. Nothing may open a microphone because a model declared a sendonly
            // audio track, and there is no option anywhere that changes this.
            assertEquals(0, fake.admMode, "the client must ask for the synthetic audio device module");
            assertEquals("java", fake.sdkType, "the coordinator must be able to tell this binding apart");
            assertEquals(
                    ReactorSdk.version(),
                    fake.sdkVersion,
                    "the version reported must be this artifact's own, not reactor-core's");
            assertEquals(1, fake.autoResumeTracks, "auto-resume tracks defaults to true");
            client.close();
        }
    }

    @Test
    @DisplayName("autoResumeTracks(false) is forwarded to reactor_create_with_adm")
    void autoResumeTracksCanBeDisabled() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            ReactorOptions options = ReactorOptions.builder("https://api.example.test", "owner/model")
                    .autoResumeTracks(false)
                    .build();
            ClientPeer client = connected(fake, options);

            assertEquals(0, fake.autoResumeTracks, "auto-resume tracks must be off when the caller opts out");
            client.close();
        }
    }

    @Test
    @DisplayName("destroy returning 0 releases the arena; -1 orphans it and never closes it")
    void teardownHonoursWhatDestroyAnswered() {
        try (FakeNativeLibrary quiesced = new FakeNativeLibrary()) {
            quiesced.destroyResult = 0;
            ClientPeer client = connected(quiesced, OPTIONS);
            client.close();
            assertEquals(1, quiesced.destroyCalls);
        }

        try (FakeNativeLibrary busy = new FakeNativeLibrary()) {
            busy.destroyResult = -1;
            ClientPeer client = connected(busy, OPTIONS);
            int orphanedBefore = OrphanedArenas.count();

            client.close();

            assertEquals(
                    orphanedBefore + 1,
                    OrphanedArenas.count(),
                    "on -1 the library still holds pointers into the arena: closing it would be a jump"
                            + " into freed code, so it has to leak");
        }
    }

    @Test
    @DisplayName("close twice destroys once")
    void closeIsIdempotent() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            ClientPeer client = connected(fake, OPTIONS);
            client.close();
            client.close();
            client.close();
            assertEquals(1, fake.destroyCalls);
        }
    }

    @Test
    @DisplayName("close settles every pending operation instead of leaving the caller waiting")
    void closeSettlesPendingOperations() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            ClientPeer client = peer(fake, OPTIONS);
            CompletableFuture<Void> connecting = client.connect(null, null);
            assertEquals(1, client.pendingOperations());

            client.close();

            assertTrue(connecting.isCompletedExceptionally());
            ExecutionException thrown = assertThrows(ExecutionException.class, connecting::get);
            assertEquals(
                    "ABORTED",
                    assertInstanceOf(ReactorException.class, thrown.getCause()).code());
        }
    }

    @Test
    @DisplayName("a call after close is refused, rather than reaching a destroyed handle")
    void callsAfterCloseAreRefused() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            ClientPeer client = peer(fake, OPTIONS);
            client.close();

            CompletableFuture<Void> after = client.connect(null, null);

            ExecutionException thrown = assertThrows(ExecutionException.class, after::get);
            assertEquals(
                    "INVALID_STATE",
                    assertInstanceOf(ReactorException.class, thrown.getCause()).code());
            assertThrows(ReactorException.class, client::status);
        }
    }

    @Test
    @DisplayName("a handler that throws does not silence the handlers beside it")
    void oneThrowingHandlerDoesNotSilenceTheOthers() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            ClientPeer client = connected(fake, inline());
            List<ConnectionStatus> seen = new ArrayList<>();
            client.onStatus(status -> {
                throw new IllegalStateException("a handler with a bug in it");
            });
            client.onStatus(seen::add);

            fireStatus(fake, "ready");

            assertEquals(List.of(ConnectionStatus.READY), seen, "the second handler must still have run");
            client.close();
        }
    }

    @Test
    @DisplayName("a handler that closes its own client does not deadlock or end the process")
    void aHandlerMayCloseItsOwnClient() throws Exception {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            // The default dispatcher: its own thread, which is where this gets interesting —
            // shutting an executor down from inside its own thread and then waiting for it is a
            // thread waiting for itself.
            ClientPeer client = connected(fake, OPTIONS);
            CountDownLatch closed = new CountDownLatch(1);
            long[] elapsedMillis = new long[1];
            client.onStatus(status -> {
                long start = System.nanoTime();
                client.close();
                elapsedMillis[0] = (System.nanoTime() - start) / 1_000_000;
                closed.countDown();
            });

            fireStatus(fake, "disconnected");

            assertTrue(closed.await(5, TimeUnit.SECONDS), "closing from inside a handler never finished");
            assertEquals(1, fake.destroyCalls);
            // The guard is what makes this fast. Without it, close() waits for the event thread to
            // terminate while running *on* that thread, so it can only end at the two-second
            // timeout. A bounded stall rather than a permanent deadlock — and still worth not
            // having, which is what this bound proves.
            assertTrue(
                    elapsedMillis[0] < 1_000,
                    "closing from inside a handler took " + elapsedMillis[0]
                            + "ms; it waited for the thread it was running on");
        }
    }

    @Test
    @DisplayName("removing a handler stops it firing")
    void removalActuallyRemoves() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            ClientPeer client = connected(fake, inline());
            AtomicInteger calls = new AtomicInteger();
            Subscription subscription = client.onStatus(status -> calls.incrementAndGet());

            fireStatus(fake, "ready");
            subscription.close();
            fireStatus(fake, "waiting");
            subscription.close(); // idempotent

            assertEquals(1, calls.get(), "the handler fired after it was removed");
            client.close();
        }
    }

    @Test
    @DisplayName("an error event carries the same typed exception a failed call would throw")
    void errorEventsAreTheSameObjectAsFailures() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            ClientPeer client = connected(fake, inline());
            List<ReactorException> seen = new ArrayList<>();
            client.onError(seen::add);

            fake.fireCallback(
                    "on_error",
                    Ffi.Callbacks.ON_ERROR,
                    fake.cString("{\"code\":\"SESSION_TERMINAL\",\"message\":\"gone\",\"recoverable\":false}"),
                    MemorySegment.NULL);

            assertEquals(1, seen.size());
            assertEquals("SESSION_TERMINAL", seen.get(0).code());
            assertFalse(seen.get(0).isRecoverable());
            client.close();
        }
    }

    @Test
    @DisplayName("status and session id read through the FFI, freeing only what is owned")
    void readsFreeOnlyWhatTheyOwn() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            ClientPeer client = peer(fake, OPTIONS);
            fake.nextOwnedString = "session-42";

            assertEquals(ConnectionStatus.READY, client.status());
            assertEquals("session-42", client.sessionId().orElseThrow());

            assertEquals(1, fake.freeCallCount(), "the owned session id is freed; the static status is not");
            assertFalse(fake.aStaticStringWasFreed());
            client.close();
        }
    }

    private static void fireStatus(FakeNativeLibrary fake, String status) {
        fake.fireCallback(
                "on_status", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS), fake.cString(status), MemorySegment.NULL);
    }
}
