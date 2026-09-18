package inc.reactor.examples;

import inc.reactor.sdk.JsonValue;
import inc.reactor.sdk.Reactor;
import inc.reactor.sdk.ReactorOptions;
import inc.reactor.sdk.VideoFrame;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 07 · Read the per-frame trailer: frame id, sender timestamp, tag.
 *
 * <pre>
 * export REACTOR_API_KEY=rk_...
 * mise run example:java 07
 * </pre>
 *
 * <p>{@code timestampUs} is on the sender's own clock. Differences between stamps from one sender
 * are what it supports; comparing it with this machine's clock produces a number that looks like a
 * latency and is not one.
 *
 * <p>A tag is dropped unless the far end declared that it reads tags, so an empty {@code userData}
 * here is usually the model's choice rather than a lost field.
 */
public final class Example07FrameMetadata {

    private static final String MODEL = Examples.model("reactor/helios");
    private static final String OUTPUT_TRACK = "main_video";
    private static final String PROMPT = "a forest at dawn, sunbeams through the canopy";

    private Example07FrameMetadata() {}

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

            AtomicInteger frames = new AtomicInteger();
            AtomicInteger withTrailer = new AtomicInteger();
            AtomicInteger withoutTrailer = new AtomicInteger();
            AtomicInteger tagged = new AtomicInteger();
            AtomicLong previousTimestamp = new AtomicLong();
            AtomicLong previousId = new AtomicLong();

            reactor.track(OUTPUT_TRACK).onFrame((VideoFrame frame) -> {
                int seen = frames.incrementAndGet();
                if (frame.frameId() == 0 && frame.timestampUs() == 0) {
                    withoutTrailer.incrementAndGet();
                } else {
                    withTrailer.incrementAndGet();
                }
                frame.userData().ifPresent(bytes -> tagged.incrementAndGet());
                long lastTimestamp = previousTimestamp.getAndSet(frame.timestampUs());
                long lastId = previousId.getAndSet(frame.frameId());

                if (seen <= 5 || seen % 60 == 0) {
                    String tag = frame.userData()
                            .map(bytes -> "\"" + new String(bytes, StandardCharsets.UTF_8) + "\"")
                            .orElse("(none)");
                    // The gap between two stamps from this sender is meaningful; the stamp itself
                    // compared with System.currentTimeMillis() is not.
                    String gap = lastTimestamp == 0 ? "—" : (frame.timestampUs() - lastTimestamp) + "us";
                    String idGap = lastId == 0 ? "—" : String.valueOf(frame.frameId() - lastId);
                    System.out.println("frame " + seen + ": id=" + frame.frameId() + " (+" + idGap + ")"
                            + " timestamp=" + frame.timestampUs() + " (+" + gap + ")"
                            + " tag=" + tag);
                }
                window.submit(frame.toByteArray(), frame.width(), frame.height());
            });

            window.hold(Duration.ofSeconds(Examples.seconds(15)));
            System.out.println("frames: " + frames.get() + " — with a trailer: " + withTrailer.get() + ", without: "
                    + withoutTrailer.get() + ", tagged: " + tagged.get());
            if (withTrailer.get() == 0) {
                // Not a fault. A frame without a trailer arrives as zeros, and Helios is purely
                // generative: it has no input frames, so there is nothing of a client's to carry
                // one. reactor/echo mirrors what it is sent, which is where these fields are
                // visible — and example 04 is the sending side.
                System.out.println("this model attaches no trailer: it has no input frames to carry one");
            }

            reactor.disconnect().join();
        }
    }
}
