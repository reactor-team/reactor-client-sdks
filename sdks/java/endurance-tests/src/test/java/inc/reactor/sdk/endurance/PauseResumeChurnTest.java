package inc.reactor.sdk.endurance;

import inc.reactor.sdk.Reactor;
import inc.reactor.sdk.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pause and resume a recvonly track, and nothing else.
 *
 * <p>Not hypothetical: the first real CI run of this scenario in another binding found a linear,
 * no-plateau RSS climb of 50% over five minutes that publish churn — same run, same process, same
 * fixture — did not show at all. Folded into the broad mix, that signal reads as "RSS grew a bit,
 * inconclusive" instead of naming pause and resume outright.
 */
final class PauseResumeChurnTest {

    @Test
    @DisplayName("pause and resume churn")
    void pauseResumeChurn() throws Exception {
        String jwt = Endurance.jwt();
        Endurance.run(
                "pause-resume-churn", "pause then resume a recvonly track, nothing else in the loop.", false, run -> {
                    Reactor reactor = Reactor.open(Endurance.options(jwt));
                    try {
                        reactor.connect().join();
                        Track output = reactor.track("main_video");

                        while (run.keepGoing()) {
                            output.pause().join();
                            output.resume().join();

                            // This scenario's own invariant: what it churns must not accumulate.
                            if (output.isPaused()) {
                                throw new AssertionError("a track stayed paused after resume");
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
