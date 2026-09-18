package inc.reactor.examples;

import inc.reactor.sdk.CommandReply;
import inc.reactor.sdk.JsonValue;
import inc.reactor.sdk.Reactor;
import inc.reactor.sdk.ReactorOptions;
import inc.reactor.sdk.VideoFrame;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 08 · Snapshot and rewind — what {@code sendCommand} actually hands back.
 *
 * <pre>
 * export REACTOR_API_KEY=rk_...
 * mise run example:java 08
 * </pre>
 *
 * <p>Every earlier example either fires a command without reading its reply or reads only the bare
 * type. These three answer with a payload worth reading: the index a snapshot was assigned, the
 * full list, and where a rewind landed.
 *
 * <p>The reply comes back through the command's own call. Firing a command and then listening for
 * a matching message is the mistake that looks like it works: the reply can arrive before the
 * listener exists, and then nothing ever settles.
 */
public final class Example08SnapshotAndRewind {

    private static final String MODEL = Examples.model("reactor/helios");
    private static final String OUTPUT_TRACK = "main_video";
    private static final String PROMPT = "a lighthouse in a storm";

    private Example08SnapshotAndRewind() {}

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
            reactor.track(OUTPUT_TRACK).onFrame((VideoFrame frame) -> {
                frames.incrementAndGet();
                window.submit(frame.toByteArray(), frame.width(), frame.height());
            });

            window.hold(Duration.ofSeconds(Examples.seconds(10)));

            // A payload worth reading: the index this snapshot was given.
            describe(
                    "save_snapshot",
                    reactor.sendCommand("save_snapshot", JsonValue.object().build())
                            .join());

            window.hold(Duration.ofSeconds(6));
            describe(
                    "list_snapshots",
                    reactor.sendCommand("list_snapshots", JsonValue.object().build())
                            .join());

            // And where the rewind actually landed, which is not always where it was asked to.
            describe(
                    "rewind",
                    reactor.sendCommand(
                                    "rewind",
                                    // snapshot_index, as list_snapshots reports it — not "index".
                                    // An unknown argument comes back as the model's own code,
                                    // invalid_command, which this SDK carries through unchanged
                                    // rather than trying to classify.
                                    JsonValue.object().put("snapshot_index", 1).build())
                            .join());

            window.hold(Duration.ofSeconds(6));
            System.out.println("frames: " + frames.get());

            reactor.disconnect().join();
        }
    }

    private static void describe(String command, Optional<CommandReply> reply) {
        if (reply.isEmpty()) {
            // The handler ran and acknowledged without producing a message. That is an answer.
            System.out.println(command + " -> acknowledged, no message");
            return;
        }
        CommandReply answered = reply.get();
        System.out.println(command + " -> type=" + answered.type().orElse("(none)"));
        System.out.println("  data: " + answered.dataOrNull().toJsonString());
    }
}
