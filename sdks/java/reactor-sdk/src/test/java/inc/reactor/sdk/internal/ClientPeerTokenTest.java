package inc.reactor.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import inc.reactor.sdk.ReactorException;
import inc.reactor.sdk.ReactorOptions;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Turning an API key into a token, and the handle that carries it.
 *
 * <p>The native client is handed its token at creation and cannot be told a new one, so which
 * token a connect needs decides when a handle may be reused and when it has to be replaced. These
 * are the cases where getting that wrong is invisible until the coordinator refuses something: a
 * token minted once and reused spends a session grant it cannot get back, and a model-scoped token
 * cannot reach a session it did not create.
 */
final class ClientPeerTokenTest {

    private static final String API_URL = "https://api.example.test";
    private static final String MODEL = "owner/model";

    private static ReactorOptions withKey() {
        return ReactorOptions.builder(API_URL, MODEL)
                .apiKey("rk_test")
                .dispatcher(Runnable::run)
                .build();
    }

    private static ClientPeer peer(FakeNativeLibrary fake, ReactorOptions options) {
        return ClientPeer.create(options, arena -> Ffi.open(fake.lookup()));
    }

    /** @return how many of the SDK's own event threads are alive right now */
    private static int eventThreads() {
        return (int) Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && "reactor-events".equals(thread.getName()))
                .count();
    }

    /**
     * Waits for the SDK's event threads to reach {@code expected}.
     *
     * <p>A wait rather than a read: the executor starts its thread on first use and stops it a
     * moment after {@code shutdown()}, and neither is synchronous with the call that caused it.
     * Failing on the timeout is what makes this an assertion — a thread that is never shut down
     * never reaches zero, which is the leak this is here to catch.
     */
    private static void awaitEventThreads(int expected) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (eventThreads() != expected) {
            if (System.nanoTime() > deadline) {
                assertEquals(
                        expected,
                        eventThreads(),
                        "the dispatcher's own thread outlived the client — setting the teardown"
                                + " flags by hand skips the shutdown that close() does");
            }
            Thread.onSpinWait();
        }
    }

    @Test
    @DisplayName("a key is exchanged for a token scoped to this model, and the client is built with it")
    void aKeyIsExchangedScopedToThisModel() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            fake.nextMintedJwt = "scoped-token";
            ClientPeer client = peer(fake, withKey());

            // Nothing was minted and nothing was created: a client that never connects costs no
            // session grant capacity and no native allocation.
            assertEquals(List.of(), fake.jwtRequests);
            assertEquals(List.of(), fake.createdWithJwt);

            client.connect(null, null);

            assertEquals(
                    List.of("{\"models\":[\"owner/model\"]}"),
                    fake.jwtRequests,
                    "the token must be scoped to this model, so a leak is worth sessions here rather"
                            + " than everything the key can reach");
            assertEquals(
                    List.of("scoped-token"),
                    fake.createdWithJwt,
                    "the native client is handed its token at creation, so the minted one must be"
                            + " what it was built with");
            client.close();
        }
    }

    @Test
    @DisplayName("a second connect at the same scope reuses the token and the client behind it")
    void theSameScopeIsNotMintedTwice() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            ClientPeer client = peer(fake, withKey());

            client.connect(null, null);
            fake.settleLastCall(true, "{}", null);
            client.connect(null, null);

            assertEquals(1, fake.jwtRequests.size(), "a token already right for this connect must be reused");
            assertEquals(1, fake.createdWithJwt.size(), "and so must the client it was baked into");
            assertEquals(0, fake.destroyCalls, "nothing was replaced, so nothing may have been destroyed");
            client.close();
        }
    }

    @Test
    @DisplayName("adopting a session mints an unscoped token, and replaces the client built with the old one")
    void adoptingASessionNeedsABroaderToken() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            fake.nextMintedJwt = "scoped-token";
            ClientPeer client = peer(fake, withKey());
            client.connect(null, null);
            fake.settleLastCall(true, "{}", null);
            fake.clearPendingCall();

            // A scoped token cannot reach a session it did not create, so this connect needs the
            // broader one — and the client built with the old token has to go with it.
            fake.nextMintedJwt = "unscoped-token";
            client.connect("session-1", null);

            assertEquals(
                    java.util.Arrays.asList("{\"models\":[\"owner/model\"]}", null),
                    fake.jwtRequests,
                    "adopting a session must ask for a token that is not scoped to one model");
            assertEquals(
                    List.of("scoped-token", "unscoped-token"),
                    fake.createdWithJwt,
                    "a token only reaches the native client through a new one");
            assertEquals(1, fake.destroyCalls, "the client built with the stale token must be destroyed");
            client.close();
        }
    }

    @Test
    @DisplayName("a token the caller supplied is never re-minted over")
    void aCallerSuppliedTokenIsLeftAlone() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            ClientPeer client = peer(
                    fake,
                    ReactorOptions.builder(API_URL, MODEL)
                            .jwt("the-caller's-own")
                            .apiKey("rk_test")
                            .dispatcher(Runnable::run)
                            .build());

            client.connect(null, null);

            assertEquals(List.of(), fake.jwtRequests, "a token the caller brought is theirs, not ours to replace");
            assertEquals(List.of("the-caller's-own"), fake.createdWithJwt);
            client.close();
        }
    }

    @Test
    @DisplayName("local mode does not authenticate, so it exchanges nothing")
    void localModeExchangesNothing() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            ClientPeer client = peer(
                    fake,
                    ReactorOptions.builder(API_URL, MODEL)
                            .apiKey("rk_test")
                            .local(true)
                            .dispatcher(Runnable::run)
                            .build());

            client.connect(null, null);

            assertEquals(List.of(), fake.jwtRequests);
            assertNull(fake.createdWithJwt.get(0), "a local runtime is handed no token at all");
            client.close();
        }
    }

    @Test
    @DisplayName("a refused key fails the connect rather than reaching the FFI with no token")
    void aRefusedKeyFailsTheConnect() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            fake.nextMintedJwt = null;
            ClientPeer client = peer(fake, withKey());

            CompletableFuture<Void> connecting = client.connect(null, null);

            ExecutionException failed = assertThrows(ExecutionException.class, connecting::get);
            assertTrue(failed.getCause() instanceof ReactorException, failed.toString());
            assertEquals(
                    List.of(),
                    fake.createdWithJwt,
                    "an exchange that failed must not leave a client built with no token behind it");
            client.close();
        }
    }

    @Test
    @DisplayName("a client given neither a key nor a token still connects, for a local runtime")
    void neitherKeyNorTokenStillConnects() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            ClientPeer client = peer(
                    fake,
                    ReactorOptions.builder(API_URL, MODEL)
                            .dispatcher(Runnable::run)
                            .build());

            client.connect(null, null);

            assertEquals(List.of(), fake.jwtRequests);
            assertEquals(1, fake.createdWithJwt.size());
            client.close();
        }
    }

    @Test
    @DisplayName("an operation still outstanding when the token changes is settled, not left waiting")
    void aReplacedHandleSettlesWhatItWasCarrying() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            ClientPeer client = peer(fake, withKey());
            client.connect(null, null);
            fake.settleLastCall(true, "{}", null);
            fake.clearPendingCall();

            // Registered against the handle that is about to be replaced, and deliberately never
            // settled by the library. Its downcall has already returned, so the in-flight count
            // this used to wait on says zero and knows nothing about it.
            CompletableFuture<?> outstanding = client.sendCommand("get_status", null, null);
            assertFalse(outstanding.isDone(), "the library is holding this one");

            fake.nextMintedJwt = "unscoped-token";
            client.connect("session-1", null);

            // reactor_destroy ended the old client's right to call back, so this completion is one
            // that can now never arrive. Settled is the only honest answer; pending forever is what
            // the caller would otherwise get.
            assertTrue(outstanding.isCompletedExceptionally(), "a future nobody can ever settle is a hang");
            client.close();
        }
    }

    @Test
    @DisplayName("a close that races the re-mint still destroys the handle the re-mint replaced")
    void aCloseRacingTheRemintStillDestroysTheStaleHandle() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            int orphanedBefore = OrphanedArenas.count();
            ClientPeer client = peer(fake, withKey());
            client.connect(null, null);
            fake.settleLastCall(true, "{}", null);
            fake.clearPendingCall();

            // The interleaving that used to lose the handle: the replaced one has been detached
            // but not yet destroyed when close() arrives. Provoked rather than waited for — this
            // operation's failure is what the re-mint settles on its way past, and closing from a
            // handler is a pattern this SDK supports, so the close lands inside that exact window.
            CompletableFuture<?> outstanding = client.sendCommand("get_status", null, null);
            outstanding.whenComplete((ignored, failed) -> client.close());

            fake.nextMintedJwt = "unscoped-token";
            CompletableFuture<Void> adopting = client.connect("session-1", null);

            assertTrue(adopting.isCompletedExceptionally(), "the connect cannot proceed on a closed client");
            assertEquals(
                    1,
                    fake.destroyCalls,
                    "the handle the re-mint replaced must still be destroyed — by teardown, if the"
                            + " thread that replaced it lost the race");
            assertEquals(
                    orphanedBefore,
                    OrphanedArenas.count(),
                    "and with it destroyed, the arena holding its callback stubs can be released");
        }
    }

    @Test
    @DisplayName("two connects wanting different scopes mint one at a time, not at once")
    void mintsAreSerialized() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            fake.deferFetchJwt = true;
            ClientPeer client = peer(fake, withKey());

            CompletableFuture<Void> creating = client.connect(null, null);
            CompletableFuture<Void> adopting = client.connect("session-1", null);

            // Both connects want a token and they want different ones. Letting them exchange at
            // once would leave two mints racing to rebuild the handle under each other, and the
            // loser's rebuild landing under the winner's connect.
            assertEquals(1, fake.heldJwtCount(), "the second connect must wait for the first to mint");
            assertFalse(creating.isDone());
            assertFalse(adopting.isDone());

            fake.settleHeldJwt("scoped-token");
            assertEquals(1, fake.heldJwtCount(), "the second mint starts only once the first is done");

            fake.settleHeldJwt("unscoped-token");
            assertEquals(
                    java.util.Arrays.asList("{\"models\":[\"owner/model\"]}", null),
                    fake.jwtRequests,
                    "and each asks for the scope its own connect needed");
            client.close();
        }
    }

    @Test
    @DisplayName("a re-mint whose destroy reports a live callback retains the arena and closes once")
    void aRemintThatCannotDestroyClosesThroughTheOrdinaryPath() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            int liveBefore = ClientPeer.liveClients();
            int orphanedBefore = OrphanedArenas.count();
            // The default dispatcher, which owns a thread — the one this branch used to leave
            // running, because it set the teardown flags by hand instead of going through close().
            ClientPeer client = peer(
                    fake,
                    ReactorOptions.builder(API_URL, MODEL).apiKey("rk_test").build());
            client.connect(null, null);
            fake.settleLastCall(true, "{}", null);
            fake.clearPendingCall();

            // The executor creates its thread on first use, not at construction, so an event has
            // to go through it before there is anything to assert about.
            fake.fireCallback(
                    "on_status", Ffi.Callbacks.ON_STATUS, fake.cString("ready"), java.lang.foreign.MemorySegment.NULL);
            awaitEventThreads(1);

            // -1: a callback is still running against the arena the next handle would share, so
            // there is no rebuilding this client.
            fake.destroyResult = -1;
            fake.nextMintedJwt = "unscoped-token";
            CompletableFuture<Void> adopting = client.connect("session-1", null);

            assertTrue(adopting.isCompletedExceptionally(), "the client cannot be rebuilt, so the connect fails");
            assertTrue(client.isClosed(), "and the client is finished, not left half-torn-down");
            assertEquals(
                    orphanedBefore + 1,
                    OrphanedArenas.count(),
                    "the library still holds pointers into the arena, so it has to stay");
            assertEquals(
                    liveBefore,
                    ClientPeer.liveClients(),
                    "counted down once, by the close this delegates to rather than by hand");
            awaitEventThreads(0);

            // The close already happened; this must not count a second time.
            client.close();
            assertEquals(liveBefore, ClientPeer.liveClients(), "and closing again changes nothing");
        }
    }

    @Test
    @DisplayName("a client that never connected tears down without destroying a handle it never had")
    void anUnconnectedClientTearsDownCleanly() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            // -1 is the answer that orphans the arena. A client with no handle must never reach it:
            // reactor_destroy takes null and answers 0 before anything else happens.
            fake.destroyResult = -1;
            int orphanedBefore = OrphanedArenas.count();
            ClientPeer client = peer(fake, withKey());

            client.close();

            assertEquals(0, fake.destroyCalls, "there was no handle to destroy");
            assertEquals(orphanedBefore, OrphanedArenas.count(), "and so no reason to leak the arena");
        }
    }
}
