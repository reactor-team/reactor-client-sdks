package inc.reactor.sdk.endurance;

import inc.reactor.sdk.PublishState;
import inc.reactor.sdk.Reactor;
import inc.reactor.sdk.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Publish and unpublish, and nothing else in the loop.
 *
 * <p>Session churn mixes publish, frames, commands and unpublish every cycle, so a leak specific to
 * just one of them shows up as a small contribution to a trend several operations are feeding. On
 * its own it is either flat or it is not — and the report names the culprit rather than the mix.
 */
final class PublishChurnTest {

    @Test
    @DisplayName("publish churn")
    void publishChurn() throws Exception {
        String apiKey = Endurance.apiKey();
        Endurance.run(
                "publish-churn",
                "publish then unpublish, nothing else: no frames, no commands, no reconnects.",
                false,
                run -> {
                    Reactor reactor = Reactor.open(Endurance.options(apiKey));
                    try {
                        reactor.connect().join();
                        Track input = reactor.track("webcam");

                        while (run.keepGoing()) {
                            input.publish().join();
                            input.unpublish();

                            // This scenario's own invariant: the state it churns has to come back each time.
                            if (input.publishState() != PublishState.UNPUBLISHED) {
                                throw new AssertionError(
                                        "a track stayed published after unpublish: " + input.publishState());
                            }
                            run.endOfCycle();
                        }
                    } finally {
                        try {
                            reactor.disconnect().join();
                        } catch (RuntimeException alreadyGone) {
                            // The report still has to be written.
                        }
                        reactor.close();
                    }
                });
    }
}
