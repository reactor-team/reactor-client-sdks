package inc.reactor.sdk.integration;

import inc.reactor.sdk.Reactor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * One connected client for a whole test class.
 *
 * <p>Not one per test, which is what this suite did first and what the platform refused: there is a
 * ceiling on how many sessions may exist at once, and a suite that needs a dozen to check a dozen
 * things meets it on a busy afternoon however politely it paces. Everything below the session is
 * the same code either way, so sharing costs no coverage.
 */
abstract class LiveFixture {

    /** Connected before the class runs, and left alone by every test that is not about connecting. */
    protected Reactor shared;

    @BeforeAll
    void openOneSession() {
        shared = Live.connected(Live.jwt());
    }

    @AfterAll
    void closeIt() {
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
