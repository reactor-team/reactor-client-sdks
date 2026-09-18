package inc.reactor.examples;

import inc.reactor.sdk.FileRef;
import inc.reactor.sdk.JsonValue;
import inc.reactor.sdk.Reactor;
import inc.reactor.sdk.ReactorOptions;
import inc.reactor.sdk.VideoFrame;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

/**
 * 02 · Upload a file and pass the reference into a command.
 *
 * <pre>
 * export REACTOR_API_KEY=rk_...
 * mise run example:java 02
 * </pre>
 *
 * <p>The bytes cross the wire once. {@code uploadFile} hands the platform the file and answers with
 * a reference; the command names that reference rather than carrying the image, so the platform
 * resolves it on its own side.
 */
public final class Example02UploadImage {

    private static final String MODEL = Examples.model("reactor/helios");
    private static final String OUTPUT_TRACK = "main_video";
    private static final String PROMPT = "the same scene at night, lit by a campfire";

    private Example02UploadImage() {}

    /**
     * @param args ignored
     * @throws Exception when the run fails
     */
    public static void main(String[] args) throws Exception {
        Path image = drawSomething();
        System.out.println("uploading " + image);

        String jwt = Reactor.fetchJwt(Examples.apiUrl(), Examples.apiKey()).join();
        try (Reactor reactor = Reactor.open(ReactorOptions.builder(Examples.apiUrl(), MODEL)
                        .jwt(jwt)
                        .build());
                Display window = Display.window(MODEL + " · " + OUTPUT_TRACK, Examples.show())) {

            reactor.onStatus(status -> System.out.println("status: " + status));
            reactor.connect().join();

            FileRef reference = reactor.uploadFile(image).join();
            System.out.println("uploaded: " + reference.uploadId() + " (" + reference.size() + " bytes, "
                    + reference.mimeType() + ")");

            // Helios takes the prompt and the image together, through set_conditioning, so that
            // start cannot observe a half-set session.
            //
            // The reference is named beside the arguments rather than embedded in them. Python can
            // find a FileRef sitting inside a command's arguments because a Python value carries
            // its type at run time; by the time Java's arguments are a JsonValue tree, a reference
            // is indistinguishable from any other object, so the parameter it fills is said out
            // loud.
            System.out.println("set_conditioning -> "
                    + reactor.sendCommand(
                                    "set_conditioning",
                                    JsonValue.object().put("prompt", PROMPT).build(),
                                    Map.of("image", reference))
                            .join());
            System.out.println("start -> "
                    + reactor.sendCommand("start", JsonValue.object().build()).join());

            AtomicInteger frames = new AtomicInteger();
            reactor.track(OUTPUT_TRACK).onFrame((VideoFrame frame) -> {
                frames.incrementAndGet();
                window.submit(frame.toByteArray(), frame.width(), frame.height());
            });

            window.hold(Duration.ofSeconds(Examples.seconds(15)));
            System.out.println("frames: " + frames.get());

            reactor.disconnect().join();
        } finally {
            Files.deleteIfExists(image);
        }
    }

    /** Something to upload, so the example needs no asset beside it. */
    private static Path drawSomething() throws Exception {
        BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        Graphics2D pen = image.createGraphics();
        pen.setColor(new Color(20, 40, 80));
        pen.fillRect(0, 0, 512, 512);
        pen.setColor(new Color(240, 190, 90));
        pen.fillOval(180, 120, 150, 150);
        pen.dispose();

        File file = File.createTempFile("reactor-example-", ".png");
        ImageIO.write(image, "png", file);
        return file.toPath();
    }
}
