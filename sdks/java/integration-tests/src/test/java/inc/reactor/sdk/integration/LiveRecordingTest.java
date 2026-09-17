package inc.reactor.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import inc.reactor.sdk.Clip;
import inc.reactor.sdk.DownloadedClip;
import inc.reactor.sdk.FileRef;
import inc.reactor.sdk.JsonValue;
import inc.reactor.sdk.Track;
import inc.reactor.sdk.VideoFrame;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Uploads and clips, against the platform that actually serves them. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class LiveRecordingTest extends LiveFixture {

    @Test
    @DisplayName("an uploaded file reaches a command under the parameter it fills")
    @Timeout(180)
    void anUploadReachesACommand(@TempDir Path directory) throws Exception {
        Path image = anImage(directory);

        FileRef uploaded = shared.uploadFile(image).join();

        assertTrue(!uploaded.uploadId().isBlank(), "the platform assigned no upload id");
        assertEquals("image/png", uploaded.mimeType());
        assertEquals(Files.size(image), uploaded.size());

        // Named, not embedded. This is the half a unit test cannot check: whether the platform
        // resolves a reference passed this way.
        shared.sendCommand(
                        "set_overlay_image",
                        JsonValue.object().put("overlay_strength", 0.5).build(),
                        // overlay_image, as the model's own schema names it. A guessed parameter comes
                        // back as invalid_command, which is the model telling you so rather than
                        // anything this SDK could have caught.
                        Map.of("overlay_image", uploaded))
                .join();
    }

    @Test
    @DisplayName("a clip is requested, becomes ready, and downloads into a playable file")
    @Timeout(420)
    void aClipDownloadsIntoAPlayableFile(@TempDir Path directory) throws Exception {
        Path out = directory.resolve("clip.mp4");
        Track input = shared.track(Live.VIDEO_IN);

        AtomicInteger received = new AtomicInteger();
        var subscription = shared.track(Live.VIDEO_OUT).onFrame((VideoFrame frame) -> received.incrementAndGet());

        // Something has to be recorded before there is anything to clip.
        if (input.publishState() != inc.reactor.sdk.PublishState.PUBLISHED) {
            input.publish().join();
        }
        for (int index = 0; index < 300 && received.get() < 60; index++) {
            input.pushFrame(frame(index), 320, 240);
            Thread.sleep(33);
        }
        Live.await("media to record", Duration.ofSeconds(90), () -> received.get() > 10);

        Clip clip = shared.requestClip(5).join();
        assertTrue(clip.playlistUrl().startsWith("http"), "no playlist: " + clip.playlistUrl());

        // Readiness is in media time. The clip's window ends at now, so the chunk holding that end
        // is the one still open — and it closes because the model keeps generating. Echo only
        // generates what it is fed, so a test that stops pushing here waits forever for a boundary
        // that never arrives. Keep feeding it until the download is done.
        AtomicBoolean downloading = new AtomicBoolean(true);
        Thread keepFeeding = Thread.ofPlatform().name("feed-echo").start(() -> {
            for (int index = 0; downloading.get() && index < 3_000; index++) {
                try {
                    input.pushFrame(frame(index), 320, 240);
                    Thread.sleep(33);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException stopped) {
                    return;
                }
            }
        });

        AtomicInteger segments = new AtomicInteger();
        DownloadedClip downloaded;
        try {
            downloaded = shared.downloadClip(clip, out, (done, total) -> segments.set(done))
                    .join();
        } finally {
            downloading.set(false);
            keepFeeding.join();
        }

        assertTrue(Files.exists(downloaded.path()), "the download reported a file that is not there");
        assertTrue(downloaded.bytes() > 0, "an empty clip is not a clip");

        // The init segment carries the header every fragment is parsed against, and it arrives as a
        // comment line in the playlist. A file that starts with anything else is one no player
        // opens — which is the bug this check exists for.
        byte[] head = Files.readAllBytes(downloaded.path());
        assertTrue(head.length > 8, "the file is too short to be an MP4");
        assertEquals(
                "ftyp",
                new String(head, 4, 4, StandardCharsets.US_ASCII),
                "the file does not begin with an init segment");

        System.out.println("clip: " + downloaded.bytes() + " bytes across " + downloaded.segments() + " segments, "
                + segments.get() + " progress reports");

        input.unpublish();
        subscription.close();
    }

    private static Path anImage(Path directory) throws Exception {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                image.setRGB(x, y, (x * 4) << 16 | (y * 4) << 8 | 0x40);
            }
        }
        File file = directory.resolve("overlay.png").toFile();
        ImageIO.write(image, "png", file);
        return file.toPath();
    }

    private static byte[] frame(int index) {
        byte[] bgra = new byte[320 * 240 * 4];
        for (int at = 0; at < bgra.length; at += 4) {
            bgra[at] = (byte) (index * 3 % 255);
            bgra[at + 1] = (byte) 90;
            bgra[at + 2] = (byte) 40;
            bgra[at + 3] = (byte) 255;
        }
        return bgra;
    }
}
