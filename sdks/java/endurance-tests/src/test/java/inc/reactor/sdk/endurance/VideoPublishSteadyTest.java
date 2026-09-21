package inc.reactor.sdk.endurance;

import inc.reactor.sdk.Reactor;
import inc.reactor.sdk.Track;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Publish once and hold it, streaming for the whole run.
 *
 * <p>The opposite shape from every churn scenario. A leak tied to <em>elapsed streaming time or
 * frame count</em> rather than to churn count would not necessarily show up in a churn scenario
 * even run forever, which makes this a distinct category rather than a variant.
 */
final class VideoPublishSteadyTest {

    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;

    @Test
    @DisplayName("video publish steady")
    void videoPublishSteady() throws Exception {
        String apiKey = Endurance.apiKey();
        Endurance.run(
                "video-publish-steady",
                "publish once and hold it, pushing video for the whole run — no pause, no unpublish, "
                        + "no reconnect.",
                false,
                run -> {
                    Reactor reactor = Reactor.open(Endurance.options(apiKey));
                    try {
                        reactor.connect().join();
                        Track input = reactor.track("webcam");
                        input.publish().join();
                        byte[] frame = new byte[WIDTH * HEIGHT * 4];

                        while (run.keepGoing()) {
                            // A cycle is a second of video rather than one frame: at 30fps a per-frame sample
                            // would be more measuring than streaming.
                            for (int index = 0; index < 30 && run.keepGoing(); index++) {
                                input.pushFrame(frame, WIDTH, HEIGHT);
                                Endurance.pause(Duration.ofMillis(33));
                            }

                            // This scenario's own invariant: the publish must survive the whole run, and a
                            // session that dropped out from under it would make every later reading meaningless.
                            if (!input.isPublished()) {
                                throw new AssertionError("the publish did not survive the run");
                            }
                            run.endOfCycle();
                        }
                        input.unpublish();
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
