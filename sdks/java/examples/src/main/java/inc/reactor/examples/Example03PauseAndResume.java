package inc.reactor.examples;

import inc.reactor.sdk.JsonValue;
import inc.reactor.sdk.Reactor;
import inc.reactor.sdk.ReactorOptions;
import inc.reactor.sdk.Track;
import inc.reactor.sdk.VideoFrame;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 03 · Pause a track and start it again.
 *
 * <pre>
 * export REACTOR_API_KEY=rk_...
 * mise run example:java 03
 *
 * REACTOR_SHOW=1   show the video, where a pause looks like a frozen frame
 * </pre>
 *
 * <p>Nothing is generated while paused, and that is only visible as a frozen frame — so this counts
 * frames in each phase instead. A count that keeps climbing through the pause means the pause did
 * not take.
 */
public final class Example03PauseAndResume {

    private static final String MODEL = Examples.model("reactor/helios");
    private static final String OUTPUT_TRACK = "main_video";
    private static final String PROMPT = "a forest at dawn, sunbeams through the canopy";

    private Example03PauseAndResume() {}

    /**
     * @param args ignored
     * @throws Exception when the run fails
     */
    public static void main(String[] args) throws Exception {
        String jwt = Reactor.fetchJwt(Examples.apiUrl(), Examples.apiKey()).join();

        try (Reactor reactor = Reactor.open(ReactorOptions.builder(Examples.apiUrl(), MODEL)
                        .jwt(jwt)
                        .build());
                Display window = Display.window(MODEL + " · " + OUTPUT_TRACK, Examples.show())) {

            reactor.onStatus(status -> System.out.println("status: " + status));
            reactor.connect().join();

            reactor.sendCommand(
                            "set_prompt",
                            JsonValue.object().put("prompt", PROMPT).build())
                    .join();
            reactor.sendCommand("start", JsonValue.object().build()).join();

            Track output = reactor.track(OUTPUT_TRACK);
            AtomicInteger frames = new AtomicInteger();
            output.onFrame((VideoFrame frame) -> {
                frames.incrementAndGet();
                window.submit(frame.toByteArray(), frame.width(), frame.height());
            });

            int before = countOver(window, frames, Duration.ofSeconds(6), "running");

            output.pause().join();
            System.out.println("paused: " + output.isPaused());
            int during = countOver(window, frames, Duration.ofSeconds(6), "paused");

            output.resume().join();
            System.out.println("paused: " + output.isPaused());
            int after = countOver(window, frames, Duration.ofSeconds(6), "resumed");

            System.out.println("frames — running: " + before + ", paused: " + during + ", resumed: " + after);
            if (during >= before) {
                System.out.println("the pause did not take: frames kept arriving at the same rate");
            }

            reactor.disconnect().join();
        }
    }

    private static int countOver(Display window, AtomicInteger frames, Duration duration, String phase)
            throws InterruptedException {
        int start = frames.get();
        window.hold(duration);
        int counted = frames.get() - start;
        System.out.println(phase + ": " + counted + " frames");
        return counted;
    }
}
