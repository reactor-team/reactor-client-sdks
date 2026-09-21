package inc.reactor.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
