package inc.reactor.sdk.endurance;

import inc.reactor.sdk.Reactor;
import inc.reactor.sdk.Track;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The same shape as video publish steady, for audio.
 *
 * <p>A separate scenario rather than one parameterised over both: they share nothing below
 * {@code pushFrame} — separate adapters, separate encoder threads in the native runtime — so a leak
 * in one is not evidence about the other, and a report naming {@code audio-publish-steady} is worth
 * more than one saying {@code media-publish-steady[audio]}.
 */
final class AudioPublishSteadyTest {

    /** 20 ms at 48 kHz mono: the frame size every Reactor model takes. */
    private static final short[] SILENCE = new short[48_000 / 50];

    @Test
    @DisplayName("audio publish steady")
    void audioPublishSteady() throws Exception {
        String apiKey = Endurance.apiKey();
        Endurance.run(
                "audio-publish-steady",
                "publish once and hold it, pushing PCM for the whole run — no pause, no unpublish, " + "no reconnect.",
                false,
                run -> {
                    Reactor reactor = Endurance.connected(apiKey);
                    try {
                        Track microphone = reactor.track("mic");
                        microphone.publish().join();

                        while (run.keepGoing()) {
                            for (int index = 0; index < 50 && run.keepGoing(); index++) {
                                microphone.pushFrame(SILENCE, 48_000, 1);
                                Endurance.pause(Duration.ofMillis(20));
                            }
                            if (!microphone.isPublished()) {
                                throw new AssertionError("the publish did not survive the run");
                            }
                            run.endOfCycle();
                        }
                        microphone.unpublish();
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
