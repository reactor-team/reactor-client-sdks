package inc.reactor.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import inc.reactor.sdk.CommandReply;
import inc.reactor.sdk.ConnectionStatus;
import inc.reactor.sdk.JsonValue;
import inc.reactor.sdk.Reactor;
import inc.reactor.sdk.ReactorException;
import inc.reactor.sdk.ReactorOptions;
import inc.reactor.sdk.Stats;
import inc.reactor.sdk.TrackDirection;
import inc.reactor.sdk.TrackKind;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

/** The session: connecting, what it declares, what it answers, and reconnecting. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class LiveSessionTest extends LiveFixture {

    @Test
    @DisplayName("connecting reaches ready, assigns a session, and reports the status changes")
    @Timeout(180)
    void connectingReachesReady() {
        // Its own client, because this is the one test about connecting rather than about
        // something a connected session does.
        List<ConnectionStatus> seen = new CopyOnWriteArrayList<>();
        try (Reactor reactor = Reactor.open(
                ReactorOptions.builder(Live.API_URL, Live.MODEL).jwt(Live.jwt()).build())) {
            reactor.onStatus(seen::add);
            reactor.connect().join();

            assertEquals(ConnectionStatus.READY, reactor.status());
            assertTrue(reactor.sessionId().isPresent(), "a connected client has a session id");
            // The whole ladder, not just the end of it: a client that jumped straight to ready
            // would mean the status channel is not reporting what it is watching.
            assertTrue(seen.contains(ConnectionStatus.CONNECTING), "saw: " + seen);
            assertTrue(seen.contains(ConnectionStatus.READY), "saw: " + seen);

            reactor.disconnect().join();
        }
    }

    @Test
    @DisplayName("the session declares all four kind and direction combinations")
    @Timeout(180)
    void theSessionDeclaresEveryCombination() {
        // Declaration order, as the session gave it — not sorted, and not a set.
        List<String> names = shared.tracks().stream().map(track -> track.name()).toList();
        assertEquals(4, names.size(), "echo declares four tracks; got " + names);

        assertEquals(TrackDirection.SENDONLY, shared.track(Live.VIDEO_IN).direction());
        assertEquals(TrackKind.VIDEO, shared.track(Live.VIDEO_IN).kind());
        assertEquals(TrackKind.AUDIO, shared.track(Live.AUDIO_IN).kind());
        assertEquals(TrackDirection.RECVONLY, shared.track(Live.VIDEO_OUT).direction());

        assertEquals(
                1,
                shared.tracks()
                        .withKind(TrackKind.AUDIO)
                        .withDirection(TrackDirection.RECVONLY)
                        .size());
    }

    @Test
    @DisplayName("a name the session never declared raises, listing the ones it did")
    @Timeout(180)
    void anUndeclaredNameRaisesAgainstARealSession() {
        ReactorException thrown = assertThrows(ReactorException.class, () -> shared.track("no_such_track"));

        assertEquals("NOT_FOUND", thrown.code());
        assertTrue(thrown.getMessage().contains(Live.VIDEO_OUT), thrown.getMessage());
    }

    @Test
    @DisplayName("a command answers through its own call, with the model's payload")
    @Timeout(180)
    void aCommandAnswersThroughItsOwnCall() {
        Optional<CommandReply> reply = shared.sendCommand(
                        "set_effect", JsonValue.object().put("effect", "invert").build())
                .join();

        // The reply comes back through this call. Firing and then listening for a matching message
        // is the bug that looks like it works until the reply arrives first.
        assertTrue(reply.isPresent(), "set_effect answered with nothing");
        assertNotNull(reply.get().dataOrNull());

        assertTrue(shared.sendCommand("get_status").join().isPresent());
    }

    @Test
    @DisplayName("a command the model rejects carries its own code through unclassified")
    @Timeout(180)
    void aRejectedCommandCarriesItsOwnCode() {
        // join() wraps whatever the future failed with, so the assertion is on the cause — the
        // same shape every caller of this API writes.
        CompletionException wrapper = assertThrows(
                CompletionException.class,
                () -> shared.sendCommand(
                                "set_effect",
                                JsonValue.object().put("not_a_parameter", 1).build())
                        .join());
        ReactorException thrown = assertInstanceOf(ReactorException.class, wrapper.getCause());

        // The platform's code set is open-ended. Whatever this is, it must arrive intact rather
        // than being flattened into a parse failure.
        assertFalse(thrown.code().isBlank());
        System.out.println("the model rejected it as: " + thrown.code());
    }

    @Test
    @DisplayName("reconnect keeps the session rather than starting a new one")
    @Timeout(240)
    void reconnectKeepsTheSession() {
        // Its own client rather than the shared one. A reconnect that does not complete leaves its
        // client disconnected, and on a shared client that turns one slow transport into every
        // later test in the class failing with INVALID_STATE — a cascade that hides whatever the
        // real failure was. Found by watching exactly that happen.
        try (Reactor reactor = Live.connected(Live.jwt())) {
            String session = reactor.sessionId().orElseThrow();

            reactor.reconnect().join();
            Live.await(
                    "the transport to come back",
                    Duration.ofSeconds(60),
                    () -> reactor.status() == ConnectionStatus.READY);

            assertEquals(session, reactor.sessionId().orElseThrow(), "reconnect must not start a new session");
            reactor.disconnect().join();
        }
    }

    @Test
    @DisplayName("the client reports statistics the platform actually sent")
    @Timeout(180)
    void statisticsComeBack() {
        Stats stats = shared.getStats().join();

        // Deliberately not asserting on a particular measurement: what a runtime reports grows over
        // time, and a test naming today's fields would fail on a platform improvement.
        assertNotNull(stats.raw());
        assertInstanceOf(JsonValue.JsonObject.class, stats.raw());
    }
}
