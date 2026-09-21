package inc.reactor.sdk.endurance;

import inc.reactor.sdk.Reactor;
import inc.reactor.sdk.Track;
import inc.reactor.sdk.VideoFrame;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One client, connected once, repeating everything a session does without ever disconnecting.
 *
 * <p>For a leak in a single operation, which a connect-and-close cycle dilutes into noise.
 */
final class SessionChurnTest {

    @Test
    @DisplayName("session churn")
    void sessionChurn() throws Exception {
        String apiKey = Endurance.apiKey();
        Endurance.run(
                "session-churn",
                "One client, connected once: publish, subscribe, push a frame, send a command, "
                        + "unpublish — repeated without ever disconnecting.",
                false,
                run -> {
                    Reactor reactor = Reactor.open(Endurance.options(apiKey));
                    try {
                        reactor.connect().join();
                        Track input = reactor.track("webcam");
                        Track output = reactor.track("main_video");
                        byte[] frame = new byte[320 * 240 * 4];

                        while (run.keepGoing()) {
                            AtomicInteger received = new AtomicInteger();
                            var subscription = output.onFrame((VideoFrame ignored) -> received.incrementAndGet());

                            input.publish().join();
                            input.pushFrame(frame, 320, 240);
                            reactor.sendCommand("get_status").join();
                            input.unpublish();
                            subscription.close();

                            // This scenario's own invariant: every operation it starts is answered, so nothing
                            // may be left waiting at the end of a cycle.
                            if (reactor.isClosed()) {
                                throw new AssertionError("the client closed itself mid-run");
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
