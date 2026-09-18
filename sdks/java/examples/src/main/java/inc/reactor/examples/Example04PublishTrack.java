package inc.reactor.examples;

import inc.reactor.sdk.JsonValue;
import inc.reactor.sdk.Reactor;
import inc.reactor.sdk.ReactorOptions;
import inc.reactor.sdk.Track;
import inc.reactor.sdk.TrackDirection;
import inc.reactor.sdk.VideoFrame;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 04 · Publish a track and push tagged frames into it.
 *
 * <pre>
 * export REACTOR_API_KEY=rk_...
 * mise run example:java 04
 * </pre>
 *
 * <p>This one needs a model that declares a sendonly track — {@code xmax/x2} takes a video input
 * named {@code source}. Helios, which every other example uses, only produces.
 *
 * <p><b>Publishing is what puts a sender behind the slot.</b> Pushing before it raises rather than
 * silently going nowhere, and a publish does not survive the session leaving {@code ready}: the SDK
 * clears its own record then, because a reconnect resumes recvonly tracks and nothing else.
 */
public final class Example04PublishTrack {

    private static final String MODEL = Examples.model("xmax/x2");
    private static final String INPUT_TRACK = "source";
    private static final String PROMPT = "repaint the scene as a watercolour painting";

    private static final int WIDTH = 512;
    private static final int HEIGHT = 512;
    private static final int FPS = 15;

    private Example04PublishTrack() {}

    /**
     * @param args ignored
     * @throws Exception when the run fails
     */
    public static void main(String[] args) throws Exception {
        String jwt = Reactor.fetchJwt(Examples.apiUrl(), Examples.apiKey()).join();

        try (Reactor reactor = Reactor.open(ReactorOptions.builder(Examples.apiUrl(), MODEL)
                        .jwt(jwt)
                        .build());
                Display window = Display.window(MODEL + " · output", Examples.show())) {

            reactor.onStatus(status -> System.out.println("status: " + status));
            reactor.connect().join();
            System.out.println("tracks: " + reactor.tracks().asList());

            reactor.sendCommand(
                            "set_prompt",
                            JsonValue.object().put("prompt", PROMPT).build())
                    .join();

            Track input = reactor.track(INPUT_TRACK);
            System.out.println("publish state before: " + input.publishState());
            input.publish().join();
            System.out.println("publish state after: " + input.publishState());

            // Whatever the model sends back, so the run can be watched rather than assumed.
            AtomicInteger received = new AtomicInteger();
            reactor.tracks().withDirection(TrackDirection.RECVONLY).stream()
                    .findFirst()
                    .ifPresent(output -> output.onFrame((VideoFrame frame) -> {
                        received.incrementAndGet();
                        window.submit(frame.toByteArray(), frame.width(), frame.height());
                    }));

            long frames = Examples.seconds(15) * FPS;
            for (long index = 0; index < frames; index++) {
                // A tag travels with the frame and comes back on the far side's trailer — if that
                // side declared that it reads tags. It is dropped silently otherwise.
                byte[] tag = ("frame-" + index).getBytes(StandardCharsets.UTF_8);
                input.pushFrame(drawMovingBar(index), WIDTH, HEIGHT, tag, reactor.timeMicros());
                Thread.sleep(1000 / FPS);
            }

            System.out.println("pushed: " + frames + " frames, received: " + received.get());

            input.unpublish();
            System.out.println("publish state after unpublish: " + input.publishState());

            reactor.disconnect().join();
        }
    }

    /** A bar that moves, so the far side has something that visibly changes. */
    private static byte[] drawMovingBar(long index) {
        byte[] bgra = new byte[WIDTH * HEIGHT * 4];
        int barX = (int) (index * 7 % WIDTH);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int at = (y * WIDTH + x) * 4;
                boolean onBar = Math.abs(x - barX) < 24;
                bgra[at] = (byte) (onBar ? 240 : 30); // blue
                bgra[at + 1] = (byte) (onBar ? 200 : 60); // green
                bgra[at + 2] = (byte) (onBar ? 80 : 120); // red
                bgra[at + 3] = (byte) 255; // alpha
            }
        }
        return bgra;
    }
}
