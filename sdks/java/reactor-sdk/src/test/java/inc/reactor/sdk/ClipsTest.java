package inc.reactor.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Clips, and the download that outlives the client.
 *
 * <p>The two tests that matter here are the pair of opposite failures a binding has already shipped
 * once: releasing a download's callbacks with the client, and leaving the download out of teardown
 * so its caller waits forever.
 */
final class ClipsTest extends SendingFixture {

    /**
     * A clip as the platform answers one.
     *
     * <p>Field names taken from the FFI header's documented result and from the Python SDK's own
     * Clip, not invented here — a fixture written from imagination agrees with the code that was
     * written from the same imagination.
     */
    private static final String CLIP = "{\"session_id\":\"sess_1\",\"kind\":\"clip\","
            + "\"start_marker\":10.0,\"end_marker\":20.0,\"now_marker\":20.0,"
            + "\"predicted_ready_at_ms\":1750000000000,"
            + "\"playlist_url\":\"https://api.example.test/clips/abc/index.m3u8\"}";

    @Test
    @DisplayName("a clip request answers with somewhere to download from")
    void aClipRequestAnswersWithAPlaylist() throws Exception {
        CompletableFuture<Clip> pending = reactor.requestClip(10);

        fake.settleLastCall(true, CLIP, null);

        Clip clip = pending.get();
        assertEquals("sess_1", clip.sessionId());
        assertEquals("https://api.example.test/clips/abc/index.m3u8", clip.playlistUrl());
        assertEquals(1_750_000_000_000.0, clip.predictedReadyAtMs());
    }

    @Test
    @DisplayName("a clip without a playlist is a decode failure, since there is nothing to download")
    void aClipWithoutAPlaylistIsADecodeFailure() {
        CompletableFuture<Clip> pending = reactor.requestClip(10);

        fake.settleLastCall(true, "{\"session_id\":\"sess_1\"}", null);

        ExecutionException thrown = assertThrows(ExecutionException.class, pending::get);
        assertEquals(
                "DECODE_FAILED",
                assertInstanceOf(ReactorException.class, thrown.getCause()).code());
    }

    @Test
    @DisplayName("a clip needs a finite positive duration")
    void aClipNeedsAFiniteDuration() {
        for (double bad : List.of(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -5.0)) {
            ExecutionException thrown = assertThrows(
                    ExecutionException.class, () -> reactor.requestClip(bad).get());
            assertEquals(
                    "BAD_REQUEST",
                    assertInstanceOf(ReactorException.class, thrown.getCause()).code(),
                    "duration " + bad + " should have been refused");
        }
    }

    @Test
    @DisplayName("a download reports progress and answers with the assembled file")
    void aDownloadReportsProgress(@TempDir Path directory) throws Exception {
        Clip clip = requestedClip();
        AtomicInteger lastDone = new AtomicInteger();
        Path out = directory.resolve("clip.mp4");

        CompletableFuture<DownloadedClip> pending =
                reactor.downloadClip(clip, out, (done, total) -> lastDone.set(done));

        fake.reportDownloadProgress(3, 7);
        assertEquals(3, lastDone.get());

        fake.settleDownload(
                true,
                "{\"path\":\"" + out.toAbsolutePath().toString().replace("\\", "\\\\") + "\",\"bytes\":1024,"
                        + "\"segments\":7}",
                null);

        DownloadedClip downloaded = pending.get();
        assertEquals(1024L, downloaded.bytes());
        assertEquals(7L, downloaded.segments());
    }

    @Test
    @DisplayName("the wait is bounded on the session, not on a number of seconds")
    void theWaitIsBoundedOnTheSession(@TempDir Path directory) {
        Clip clip = requestedClip();

        reactor.downloadClip(clip, directory.resolve("clip.mp4"), null);

        // Negative means "as long as the session lives". A model generating at a tenth of real time
        // reaches the boundary chunk ten times later than any fixed timeout would allow for, and
        // once the session is gone a "not ready" is a "not ready" forever.
        assertTrue(fake.downloadTimeout < 0, "the timeout was " + fake.downloadTimeout);
    }

    @Test
    @DisplayName("closing the client settles the caller, and says the file may still arrive")
    void closingSettlesTheCallerWithoutClaimingTheDownloadStopped(@TempDir Path directory) {
        Clip clip = requestedClip();
        CompletableFuture<DownloadedClip> pending = reactor.downloadClip(clip, directory.resolve("clip.mp4"), null);

        reactor.close();

        // Leaving the download out of teardown would leave this caller waiting for the life of the
        // process — the other half of the same bug as freeing it too early.
        ExecutionException thrown = assertThrows(ExecutionException.class, pending::get);
        ReactorException cause = assertInstanceOf(ReactorException.class, thrown.getCause());
        assertEquals("ABORTED", cause.code());
        // A download whose client was destroyed is still downloading. Saying "aborted" and nothing
        // else would be a worse answer than the truth.
        assertTrue(cause.getMessage().contains("may still arrive"), cause.getMessage());
    }

    @Test
    @DisplayName("a progress callback after the client closed touches nothing")
    void progressAfterCloseTouchesNothing(@TempDir Path directory) {
        Clip clip = requestedClip();
        AtomicInteger reports = new AtomicInteger();
        CompletableFuture<DownloadedClip> pending =
                reactor.downloadClip(clip, directory.resolve("clip.mp4"), (done, total) -> reports.incrementAndGet());

        reactor.close();

        // The FFI does not know the client closed: the download was never bounded by the handle.
        // This is that callback arriving anyway. Freeing its arena on close would have made this a
        // jump into released memory rather than a no-op.
        fake.reportDownloadProgress(1, 7);
        fake.reportDownloadProgress(2, 7);

        assertEquals(0, reports.get(), "a settled download must not report progress");
        assertTrue(pending.isCompletedExceptionally());
    }

    @Test
    @DisplayName("the completion that arrives after a close releases the download, once")
    void theLateCompletionStillReleasesTheDownload(@TempDir Path directory) {
        int before = inc.reactor.sdk.internal.ClipDownload.inFlight();
        Clip clip = requestedClip();
        CompletableFuture<DownloadedClip> pending = reactor.downloadClip(clip, directory.resolve("clip.mp4"), null);
        assertEquals(before + 1, inc.reactor.sdk.internal.ClipDownload.inFlight());

        reactor.close();
        // Still in flight: the client closing settles the caller and releases nothing, because the
        // FFI still holds pointers into this download's own arena.
        assertEquals(before + 1, inc.reactor.sdk.internal.ClipDownload.inFlight());

        fake.settleDownload(true, "{\"path\":\"/tmp/x\",\"bytes\":1,\"segments\":1}", null);

        // The completion fires exactly once, and it is the only thing that frees the arena.
        assertEquals(before, inc.reactor.sdk.internal.ClipDownload.inFlight());
        assertTrue(pending.isCompletedExceptionally(), "the caller keeps the answer the close gave them");
    }

    @Test
    @DisplayName("a NaN timeout is refused by name, rather than reaching the FFI")
    void aNaNTimeoutIsRefused(@TempDir Path directory) {
        Clip clip = requestedClip();

        ExecutionException thrown = assertThrows(
                ExecutionException.class,
                () -> reactor.downloadClip(clip, directory.resolve("clip.mp4"), Double.NaN, null)
                        .get());

        ReactorException cause = assertInstanceOf(ReactorException.class, thrown.getCause());
        assertEquals("BAD_REQUEST", cause.code());
        assertTrue(cause.getMessage().contains("NaN"), cause.getMessage());
        assertFalse(cause.isRecoverable());
    }

    private Clip requestedClip() {
        CompletableFuture<Clip> pending = reactor.requestClip(10);
        fake.settleLastCall(true, CLIP, null);
        return pending.join();
    }

    @Test
    @DisplayName("a download asked for after close is refused, not started")
    void aDownloadAfterCloseIsRefused(@TempDir java.nio.file.Path directory) {
        Clip clip = new Clip("s", "clip", 0, 0, 0, 0, "https://example.test/clip.m3u8");
        reactor.close();

        // Every other asynchronous operation refused this and this one did not: it reached
        // reactor_download_clip with a handle reactor_destroy had already freed.
        java.util.concurrent.CompletionException thrown = assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> reactor.downloadClip(clip, directory.resolve("clip.mp4"), null)
                        .join());

        ReactorException refused = (ReactorException) thrown.getCause();
        assertEquals(ErrorCode.INVALID_STATE.code(), refused.code());
        assertTrue(refused.getMessage().contains("closed client"), refused.getMessage());
    }
}
