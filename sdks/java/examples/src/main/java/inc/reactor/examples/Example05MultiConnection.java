package inc.reactor.examples;

import inc.reactor.sdk.JsonValue;
import inc.reactor.sdk.Reactor;
import inc.reactor.sdk.ReactorOptions;
import inc.reactor.sdk.VideoFrame;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 05 · Two clients on one session, the second adopting it by id.
 *
 * <pre>
 * export REACTOR_API_KEY=rk_...
 * mise run example:java 05
 * </pre>
 *
 * <p>The second client passes the first one's session id to {@code connect}, and receives the same
 * model's output without creating a session of its own.
 *
 * <p>Both are closed in the reverse order they were opened, and the creator disconnects: a creator
 * that goes away without disconnecting orphans the session, and the next run cannot start until
 * that clears.
 */
public final class Example05MultiConnection {

    private static final String MODEL = Examples.model("reactor/helios");
    private static final String OUTPUT_TRACK = "main_video";
    private static final String PROMPT = "a forest at dawn, sunbeams through the canopy";

    private Example05MultiConnection() {}

    /**
     * @param args ignored
     * @throws Exception when the run fails
     */
    public static void main(String[] args) throws Exception {
        String jwt = Reactor.fetchJwt(Examples.apiUrl(), Examples.apiKey()).join();
        ReactorOptions options =
                ReactorOptions.builder(Examples.apiUrl(), MODEL).jwt(jwt).build();

        try (Reactor creator = Reactor.open(options)) {
            creator.onStatus(status -> System.out.println("creator: " + status));
            creator.connect().join();

            String session = creator.sessionId().orElseThrow();
            System.out.println("session: " + session);

            creator.sendCommand(
                            "set_prompt",
                            JsonValue.object().put("prompt", PROMPT).build())
                    .join();
            creator.sendCommand("start", JsonValue.object().build()).join();

            AtomicInteger creatorFrames = new AtomicInteger();
            creator.track(OUTPUT_TRACK).onFrame((VideoFrame frame) -> creatorFrames.incrementAndGet());

            // The second client joins the session that already exists rather than making one.
            try (Reactor joiner = Reactor.open(options);
                    Display window = Display.window(MODEL + " · joiner", Examples.show())) {
                joiner.onStatus(status -> System.out.println("joiner: " + status));
                joiner.connect(session, null).join();
                System.out.println("joiner session: " + joiner.sessionId().orElseThrow());

                AtomicInteger joinerFrames = new AtomicInteger();
                joiner.track(OUTPUT_TRACK).onFrame((VideoFrame frame) -> {
                    joinerFrames.incrementAndGet();
                    window.submit(frame.toByteArray(), frame.width(), frame.height());
                });

                window.hold(Duration.ofSeconds(Examples.seconds(15)));
                System.out.println("frames — creator: " + creatorFrames.get() + ", joiner: " + joinerFrames.get());

                // The joiner leaves first, and does not disconnect: ending the session is the
                // creator's to do.
            }

            creator.disconnect().join();
        }
    }
}
