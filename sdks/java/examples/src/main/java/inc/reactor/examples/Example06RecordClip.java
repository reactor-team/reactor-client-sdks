package inc.reactor.examples;

import inc.reactor.sdk.Clip;
import inc.reactor.sdk.DownloadedClip;
import inc.reactor.sdk.JsonValue;
import inc.reactor.sdk.Reactor;
import inc.reactor.sdk.ReactorOptions;
import inc.reactor.sdk.VideoFrame;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 06 · Request a clip and download it.
 *
 * <pre>
 * export REACTOR_API_KEY=rk_...
 * mise run example:java 06
 * </pre>
 *
 * <p><b>Readiness is in media time, not wall clock.</b> The manifest appears once the recording
 * passes the end of the chunk holding the window, and a clip of the last few seconds always ends in
 * the chunk that is still open — so waiting before asking only moves the target. The download waits
 * on the session still being alive rather than on a number of seconds, because a model generating
 * at a tenth of real time reaches that boundary ten times later.
 *
 * <p>The assembly is the native layer's: the init segment arrives as a comment line in the
 * playlist, a segment can be presigned on another host that rejects an Authorization header, and a
 * "not yet" is not an error. Each of those cost a shipped bug to learn, which is why no binding
 * does it again.
 */
public final class Example06RecordClip {

    private static final String MODEL = Examples.model("reactor/helios");
    private static final String OUTPUT_TRACK = "main_video";
    private static final String PROMPT = "a forest at dawn, sunbeams through the canopy";

    private Example06RecordClip() {}

    /**
     * @param args ignored
     * @throws Exception when the run fails
     */
    public static void main(String[] args) throws Exception {
        String jwt = Reactor.fetchJwt(Examples.apiUrl(), Examples.apiKey()).join();
        Path out = Path.of("clip.mp4").toAbsolutePath();

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

            // Generate something worth clipping first.
            window.hold(Duration.ofSeconds(Examples.seconds(20)));
            System.out.println("frames so far: " + frames.get());

            Clip clip = reactor.requestClip(10).join();
            System.out.println("clip: " + clip.playlistUrl());
            System.out.println(
                    "window: " + clip.startMarker() + " to " + clip.endMarker() + " (now at " + clip.nowMarker() + ")");

            System.out.println("downloading to " + out);
            DownloadedClip downloaded = reactor.downloadClip(
                            clip, out, (done, total) -> System.out.println("  segment " + done + "/" + total))
                    .join();

            System.out.println("wrote " + downloaded.path() + " — " + downloaded.bytes() + " bytes across "
                    + downloaded.segments() + " segments");

            reactor.disconnect().join();
        }
    }
}
