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
    @DisplayName("close waits for a frame push too, not only the calls through invoke()")
    void closeWaitsForMixedArgumentCallsAsWell() throws Exception {
        // invokeMixed is the funnel for every call whose arguments are not all segments — frame
        // pushes, bitrate requests, downloads. It was left out of the lease, so all of them went
        // past the guard at a handle close() was free to destroy underneath them.
        FakeNativeLibrary fake = new FakeNativeLibrary();
        fake.tracksJson = "[{\"name\":\"camera_in\",\"kind\":\"video\",\"direction\":\"sendonly\"}]";
        fake.blockInPushVideo = true;
        ClientPeer peer = ClientPeer.create(
                ReactorOptions.builder("https://api.example.test", "owner/model")
                        .dispatcher(Runnable::run)
                        .build(),
                arena -> Ffi.open(fake.lookup()));
        peer.publish("camera_in");
        fake.settleLastCall(true, "{}", null);

        Thread pusher =
                Thread.ofPlatform().start(() -> peer.pushVideoFrame("camera_in", new byte[4], 1, 1, null, null));
        assertTrue(fake.enteredPush.await(10, TimeUnit.SECONDS), "the push never reached the library");

        CountDownLatch closed = new CountDownLatch(1);
        Thread closer = Thread.ofPlatform().start(() -> {
            peer.close();
            closed.countDown();
        });

        assertFalse(closed.await(1, TimeUnit.SECONDS), "close returned while a push was inside the FFI");
        assertEquals(0, fake.destroyCalls, "reactor_destroy ran with a push in flight");

        fake.blockPush.countDown();
        pusher.join(TimeUnit.SECONDS.toMillis(10));
        assertTrue(closed.await(10, TimeUnit.SECONDS), "close never finished once the push returned");
        closer.join(TimeUnit.SECONDS.toMillis(10));
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

        // And then it happens. Skipping it outright was the other half of the same mistake: the
        // session stayed alive on the platform, its tasks kept running and the handle leaked for
        // the life of the process. Deferring has to mean later, not never.
        fake.blockStatus.countDown();
        reader.join(TimeUnit.SECONDS.toMillis(10));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (fake.destroyCalls == 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(1, fake.destroyCalls, "the handle was never destroyed once the call returned");
        fake.close();
    }

    @Test
    @DisplayName("a close from inside a native call destroys once that call returns")
    void aReentrantCloseStillTearsDown() throws Exception {
        // A callback closing its own client cannot wait — it would be waiting for the call it is
        // itself making. Deferring is right; never destroying is not, and both used to be the same
        // branch.
        FakeNativeLibrary fake = new FakeNativeLibrary();
        ClientPeer[] holder = new ClientPeer[1];
        fake.duringStatus = () -> holder[0].close();
        ClientPeer peer = ClientPeer.create(
                ReactorOptions.builder("https://api.example.test", "owner/model")
                        .dispatcher(Runnable::run)
                        .build(),
                arena -> Ffi.open(fake.lookup()));
        holder[0] = peer;

        peer.status();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (fake.destroyCalls == 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(1, fake.destroyCalls, "a reentrant close never destroyed the handle");
        fake.close();
    }
}
