package inc.reactor.examples;

import inc.reactor.sdk.JsonValue;
import inc.reactor.sdk.Reactor;
import inc.reactor.sdk.ReactorOptions;
import inc.reactor.sdk.Track;
import inc.reactor.sdk.VideoFrame;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 01 · Connect, prompt, receive — the baseline the other examples build on.
 *
 * <pre>
 * export REACTOR_API_KEY=rk_...
 * mise run example:java 01
 *
 * REACTOR_SHOW=1   show the video in a window
 * </pre>
 *
 * <p>Nothing arrives until the model's own minimum is met, and that minimum is per model. Helios
 * emits nothing until {@code start}, and {@code start} refuses without a prompt — so a run that
 * connects, waits and reports zero frames is usually a missing command rather than a broken
 * transport.
 *
 * @see <a href="https://docs.reactor.inc/sdk-reference/using-the-sdk">Using the SDK</a>
 */
public final class Example01ConnectAndReceive {

    private static final String MODEL = Examples.model("reactor/helios");
    private static final String OUTPUT_TRACK = "main_video";
    private static final String PROMPT = "a forest at dawn, sunbeams through the canopy";

    private Example01ConnectAndReceive() {}

    /**
     * @param args ignored
     * @throws Exception when the run fails
     */
    public static void main(String[] args) throws Exception {
        String jwt = Reactor.fetchJwt(Examples.apiUrl(), Examples.apiKey()).join();

        // try-with-resources, so the session is left even if the body throws. A creator that goes
        // away without disconnecting orphans the session, and the next run cannot start until that
        // clears.
        try (Reactor reactor = Reactor.open(ReactorOptions.builder(Examples.apiUrl(), MODEL)
                        .jwt(jwt)
                        .build());
                Display window = Display.window(MODEL + " · " + OUTPUT_TRACK, Examples.show())) {

            reactor.onStatus(status -> System.out.println("status: " + status));
            reactor.onError(error -> System.out.println("error: " + error));
            // Most handlers return nothing and answer with a message instead.
            reactor.onMessage(message -> System.out.println("message: " + message.toJsonString()));

            reactor.connect().join();
            System.out.println("session: " + reactor.sessionId().orElseThrow());

            System.out.println("set_prompt -> "
                    + reactor.sendCommand(
                                    "set_prompt",
                                    JsonValue.object().put("prompt", PROMPT).build())
                            .join());
            System.out.println("start -> "
                    + reactor.sendCommand("start", JsonValue.object().build()).join());

            // By name, as the model's schema declares it. reactor.tracks() lists them; asking by
            // name is what an application that knows its model does.
            Track output = reactor.track(OUTPUT_TRACK);
            AtomicInteger frames = new AtomicInteger();

            output.onFrame((VideoFrame frame) -> {
                if (frames.incrementAndGet() == 1) {
                    System.out.println("first frame: " + frame.width() + "x" + frame.height());
                }
                // Copied here: the frame's pixels belong to the FFI and are gone when this returns.
                window.submit(frame.toByteArray(), frame.width(), frame.height());
            });

            window.hold(Duration.ofSeconds(Examples.seconds(15)));
            System.out.println("frames: " + frames.get());

            reactor.disconnect().join();
        }
    }
}
