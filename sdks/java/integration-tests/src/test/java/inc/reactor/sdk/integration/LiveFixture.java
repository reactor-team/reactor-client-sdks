package inc.reactor.sdk.integration;

import inc.reactor.sdk.ConnectionStatus;
import inc.reactor.sdk.Reactor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

/**
 * One connected client for a whole test class.
 *
 * <p>Not one per test, which is what this suite did first and what the platform refused: there is a
 * ceiling on how many sessions may exist at once, and a suite that needs a dozen to check a dozen
 * things meets it on a busy afternoon however politely it paces. Everything below the session is
 * the same code either way, so sharing costs no coverage.
 *
 * <p>Sharing has its own cost, and it showed up as a cascade: a class takes minutes, a live session
 * can go away inside them for reasons that are nothing to do with this SDK, and every test after
 * that point failed with {@code INVALID_STATE ... status: disconnected}. One dropped session read
 * as several broken features. {@link #ensureTheSessionSurvived()} puts it back, and counts how
 * often it had to — because a fixture that quietly repairs itself is a fixture that can hide the
 * thing it is meant to catch, and a run that repairs on every test should be read as a failure of
 * something even when every assertion passes.
 */
abstract class LiveFixture {

    /** Connected before the class runs, and left alone by every test that is not about connecting. */
    protected Reactor shared;

    @BeforeAll
    void openOneSession() {
        shared = Live.connected(Live.jwt());
    }

    /** How many times this class's session had to be put back. Printed at the end, always. */
    private int reopened;

    /**
     * Puts the shared session back if it is gone.
     *
     * <p>Reconnect first: the session may still exist on the platform, and rejoining it costs
     * nothing against the ceiling that made this fixture shared in the first place. A new client
     * only when that fails.
     */
    @BeforeEach
    void ensureTheSessionSurvived() {
        if (shared != null && shared.status() == ConnectionStatus.READY) {
            return;
        }
        reopened++;
        System.out.println("the shared session was " + (shared == null ? "never opened" : shared.status())
                + " — putting it back (" + reopened + " so far in this class)");
        if (shared != null) {
            try {
                shared.reconnect().orTimeout(90, TimeUnit.SECONDS).join();
                if (shared.status() == ConnectionStatus.READY) {
                    return;
                }
            } catch (RuntimeException reconnectFailed) {
                System.out.println("  reconnect did not take: " + reconnectFailed.getMessage());
            }
            shared.close();
        }
        shared = Live.connected(Live.jwt());
    }

    @AfterAll
    void closeIt() {
        // Always, not only when it happened. Zero is the number that says the session held, and a
        // reader who has to notice the absence of a line will not.
        System.out.println("sessions re-established during this class: " + reopened);
        if (shared == null) {
            return;
        }
        try {
            // Disconnect before close: a creator that goes away without disconnecting orphans the
            // session, and the next run of this suite is the one that pays for it.
            shared.disconnect().join();
        } catch (RuntimeException alreadyGone) {
            // Nothing useful to do about it; the close below still releases the handle.
        }
        shared.close();
    }
}
