package inc.reactor.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import inc.reactor.sdk.ReactorOptions;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Closing while another thread is inside a native call.
 *
 * <p>The client is documented as thread-safe, and the closed flag alone did not make it so: a
 * thread could pass that check and `close()` could free the handle through `reactor_destroy` before
 * that thread reached the FFI. What the call then read was freed memory.
 */
final class ClientPeerTeardownRaceTest {

    @Test
    @DisplayName("close waits for a native call that is already inside the FFI")
    void closeWaitsForCallsInFlight() throws Exception {
        FakeNativeLibrary fake = new FakeNativeLibrary();
        fake.blockInStatus = true;
        ClientPeer peer = ClientPeer.create(
                ReactorOptions.builder("https://api.example.test", "owner/model")
                        .dispatcher(Runnable::run)
                        .build(),
                arena -> Ffi.open(fake.lookup()));

        AtomicBoolean finished = new AtomicBoolean();
        Thread reader = Thread.ofPlatform().start(() -> {
            peer.status();
            finished.set(true);
        });
        assertTrue(fake.enteredStatus.await(10, TimeUnit.SECONDS), "the call never reached the library");

        CountDownLatch closed = new CountDownLatch(1);
        Thread closer = Thread.ofPlatform().start(() -> {
            peer.close();
            closed.countDown();
        });

        // The whole assertion: close must still be waiting, because the handle is in use.
        assertFalse(closed.await(1, TimeUnit.SECONDS), "close returned while a call was inside the FFI");
        assertEquals(0, fake.destroyCalls, "reactor_destroy ran with a call in flight");

        fake.blockStatus.countDown();
        reader.join(TimeUnit.SECONDS.toMillis(10));
        assertTrue(closed.await(10, TimeUnit.SECONDS), "close never finished once the call returned");
        closer.join(TimeUnit.SECONDS.toMillis(10));
        assertTrue(finished.get(), "the in-flight call never completed");
        assertEquals(1, fake.destroyCalls, "the handle was never destroyed");
        fake.close();
    }

    @Test
    @DisplayName("a call that never returns leaves the handle alone rather than freeing under it")
    void aCallThatNeverReturnsStopsTheDestroy() throws Exception {
        // The wait used to time out and fall through to reactor_destroy, which is the crash it
        // exists to prevent — the comment beside it even said so while the code did the opposite.
        // A leaked handle beats a jump into freed memory, and that is the trade this makes.
        FakeNativeLibrary fake = new FakeNativeLibrary();
        fake.blockInStatus = true;
        ClientPeer peer = ClientPeer.create(
                ReactorOptions.builder("https://api.example.test", "owner/model")
                        .dispatcher(Runnable::run)
                        .build(),
                arena -> Ffi.open(fake.lookup()));

        Thread reader = Thread.ofPlatform().start(peer::status);
        assertTrue(fake.enteredStatus.await(10, TimeUnit.SECONDS), "the call never reached the library");

        // A close that cannot wait long enough. The bound is this SDK's, not the test's, so this
        // asserts the decision rather than the duration: interrupt the closer and it must still
        // refuse to destroy.
        Thread closer = Thread.ofPlatform().start(peer::close);
        Thread.sleep(50);
        closer.interrupt();
        closer.join(TimeUnit.SECONDS.toMillis(10));

        assertEquals(0, fake.destroyCalls, "reactor_destroy ran with a call still inside the FFI");

        fake.blockStatus.countDown();
        reader.join(TimeUnit.SECONDS.toMillis(10));
        fake.close();
    }
}
