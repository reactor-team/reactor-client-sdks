package inc.reactor.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import inc.reactor.sdk.Clip;
import inc.reactor.sdk.ReactorOptions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A download that is starting while the client is closing.
 *
 * <p>close() takes the set of downloads, settles it, and then waits for calls in flight.
 * Registration used to happen after the native call returned, which put a download in the gap
 * between those two: close saw an empty set, waited for the very call that was about to register,
 * and finished — leaving the caller's future pending for the life of the process.
 */
final class DownloadTeardownRaceTest {

    @Test
    @DisplayName("a download starting during close still has its caller settled")
    void aDownloadStartingDuringCloseIsSettled(@TempDir java.nio.file.Path directory) throws Exception {
        FakeNativeLibrary fake = new FakeNativeLibrary();
        fake.blockInDownload = true;
        ClientPeer peer = ClientPeer.create(
                ReactorOptions.builder("https://api.example.test", "owner/model")
                        .dispatcher(Runnable::run)
                        .build(),
                arena -> Ffi.open(fake.lookup()));

        Clip clip = new Clip("s", "clip", 0, 0, 0, 0, "https://example.test/clip.m3u8");
        CompletableFuture<inc.reactor.sdk.DownloadedClip> caller = new CompletableFuture<>();
        Thread starter = Thread.ofPlatform()
                .start(() -> peer.downloadClip(clip, null, directory.resolve("clip.mp4"), -1, false, null)
                        .whenComplete((ok, failure) -> {
                            if (failure == null) {
                                caller.complete(ok);
                            } else {
                                caller.completeExceptionally(failure);
                            }
                        }));
        assertTrue(fake.enteredDownload.await(10, TimeUnit.SECONDS), "the download never reached the library");

        CountDownLatch closed = new CountDownLatch(1);
        Thread closer = Thread.ofPlatform().start(() -> {
            peer.close();
            closed.countDown();
        });

        // Let the native initiation finish; close must not return until it has, and must have
        // settled this caller before it does.
        Thread.sleep(50);
        fake.blockDownload.countDown();

        assertTrue(closed.await(10, TimeUnit.SECONDS), "close never finished");
        starter.join(TimeUnit.SECONDS.toMillis(10));
        closer.join(TimeUnit.SECONDS.toMillis(10));

        assertTrue(caller.isDone(), "close returned with the download's caller still waiting");
        fake.close();
    }
}
