package inc.reactor.sdk.endurance;

import inc.reactor.sdk.Reactor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A fresh client per full cycle — the only scenario that exercises the native handle's whole
 * lifetime, so a leak tied to <em>tearing a client down</em> shows up here and nowhere else.
 */
final class LifecycleChurnTest {

    @Test
    @DisplayName("lifecycle churn")
    void lifecycleChurn() throws Exception {
        String apiKey = Endurance.apiKey();
        Endurance.run(
                "lifecycle-churn",
                "A new client every cycle: create, connect, send one command, disconnect, close. "
                        + "The only scenario that creates and destroys native handles.",
                true,
                run -> {
                    while (run.keepGoing()) {
                        // Closed explicitly rather than by scope exit, so the moment it happens is this line
                        // and not wherever the last reference happened to be dropped.
                        Reactor reactor = Reactor.open(Endurance.options(apiKey));
                        try {
                            reactor.connect().join();
                            reactor.sendCommand("get_status").join();
                            reactor.disconnect().join();
                        } finally {
                            reactor.close();
                        }
                        // Its own invariant, which belongs here rather than in the shared metrics: this
                        // scenario is the one that must bring the count back to zero every cycle.
                        if (inc.reactor.sdk.internal.ClientPeer.liveClients() != 0) {
                            throw new AssertionError("a client outlived its cycle: "
                                    + inc.reactor.sdk.internal.ClientPeer.liveClients());
                        }
                        run.endOfCycle();
                    }
                });
    }
}
