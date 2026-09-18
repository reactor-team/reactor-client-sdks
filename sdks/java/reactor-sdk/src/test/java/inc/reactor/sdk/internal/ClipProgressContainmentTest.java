package inc.reactor.sdk.internal;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What happens when a progress listener has a bug.
 *
 * <p>The listener runs inside an upcall stub. An exception cannot cross native code: it reaches no
 * caller, and the JVM terminates rather than guess. Application code is entitled to a bug; the
 * process is not the price, and a test that failed by killing its own JVM would at least be
 * unmistakable.
 */
final class ClipProgressContainmentTest {

    @Test
    @DisplayName("a progress listener that throws does not take the process with it")
    void aThrowingProgressListenerIsContained() {
        ClipDownload.Progress exploding = (done, total) -> {
            throw new IllegalStateException("the listener has a bug");
        };
        ClipDownload download = ClipDownload.start(new CompletableFuture<>(), exploding, (progress, completion) -> {});

        download.onProgress(1, 10, MemorySegment.NULL);
        // And it is not reported on every chunk: the listener is dropped after the first throw.
        download.onProgress(2, 10, MemorySegment.NULL);
    }
}
