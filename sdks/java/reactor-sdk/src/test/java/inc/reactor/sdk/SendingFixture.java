package inc.reactor.sdk;

import inc.reactor.sdk.internal.FakeNativeLibrary;
import inc.reactor.sdk.internal.Ffi;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * A client over a fake library, declaring one sendonly video track and one recvonly one.
 *
 * <p>Events run on the calling thread, so a test can fire one and assert on the next line.
 */
abstract class SendingFixture {

    private static final String TRACKS = "[{\"name\":\"camera_in\",\"kind\":\"video\",\"direction\":\"sendonly\"},"
            + "{\"name\":\"screen_out\",\"kind\":\"video\",\"direction\":\"recvonly\"},"
            + "{\"name\":\"mic_in\",\"kind\":\"audio\",\"direction\":\"sendonly\"}]";

    FakeNativeLibrary fake;
    Reactor reactor;

    @BeforeEach
    void openClient() {
        fake = new FakeNativeLibrary();
        fake.tracksJson = TRACKS;
        reactor = Reactor.open(
                ReactorOptions.builder("https://api.example.test", "owner/model")
                        .dispatcher(Runnable::run)
                        .build(),
                arena -> Ffi.open(fake.lookup()));
        // The native client exists from the first connect, not from `open` — that is where the
        // token it is handed is settled. Until then the library has not been given this client's
        // callbacks, so nothing here could fire one.
        reactor.connect();
        fake.settleLastCall(true, "{}", null);
        fake.clearPendingCall();
    }

    @AfterEach
    void closeClient() {
        reactor.close();
        fake.close();
    }

    /** The sendonly video track, with its publish settled. */
    Track publishedCamera() {
        Track camera = reactor.track("camera_in");
        camera.publish();
        fake.settleLastCall(true, "{}", null);
        return camera;
    }

    /** The sendonly audio track, with its publish settled. */
    Track publishedMicrophone() {
        Track microphone = reactor.track("mic_in");
        microphone.publish();
        fake.settleLastCall(true, "{}", null);
        return microphone;
    }

    /** Tells the client the session is no longer ready. */
    void leaveReady() {
        fake.fireCallback(
                "on_status",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                fake.cString("disconnected"),
                MemorySegment.NULL);
    }
}
