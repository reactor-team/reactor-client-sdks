package inc.reactor.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every row of the refuse-do-not-fail-quietly table.
 *
 * <p>The native layer is permissive: pushing into a track that does not exist, or points the other
 * way, or was never published, reaches the FFI, finds nothing to do, and returns. The caller sees a
 * loop pushing at 30fps and a model receiving nothing. Each case here is one of those, and what is
 * being asserted is not only that it raises but that the message says how to fix it.
 */
final class SendingTest extends SendingFixture {

    @Test
    @DisplayName("a name the session never declared raises, listing the names it did declare")
    void anUndeclaredNameListsWhatExists() {
        ReactorException thrown = assertThrows(ReactorException.class, () -> reactor.track("no_such_track"));

        assertEquals("NOT_FOUND", thrown.code());
        assertTrue(thrown.getMessage().contains("no_such_track"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("camera_in"), "must list the declared names: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("screen_out"), thrown.getMessage());
    }

    @Test
    @DisplayName("pushing into a recvonly track raises, naming the direction")
    void pushingIntoARecvonlyTrackRaises() {
        Track receiving = reactor.track("screen_out");

        ReactorException thrown = assertThrows(ReactorException.class, () -> receiving.pushFrame(new byte[4], 1, 1));

        assertTrue(thrown.getMessage().contains("recvonly"), thrown.getMessage());
    }

    @Test
    @DisplayName("receiving on a sendonly track raises, rather than never firing")
    void receivingOnASendonlyTrackRaises() {
        Track sending = reactor.track("camera_in");

        ReactorException thrown = assertThrows(ReactorException.class, () -> sending.onFrame((VideoFrame frame) -> {}));

        assertTrue(thrown.getMessage().contains("sendonly"), thrown.getMessage());
    }

    @Test
    @DisplayName("pushing before publish raises, and says to publish first")
    void pushingBeforePublishRaises() {
        Track sending = reactor.track("camera_in");
        assertEquals(PublishState.UNPUBLISHED, sending.publishState());

        ReactorException thrown = assertThrows(ReactorException.class, () -> sending.pushFrame(new byte[4], 1, 1));

        assertEquals("INVALID_STATE", thrown.code());
        assertTrue(thrown.getMessage().contains("publish()"), thrown.getMessage());
        assertEquals(0, fake.videoFramesPushed, "nothing may reach the FFI from a slot with no sender");
    }

    @Test
    @DisplayName("publishing is a third state, and pushing during it says to await the future")
    void publishingIsItsOwnState() {
        Track sending = reactor.track("camera_in");
        sending.publish();

        assertEquals(PublishState.PUBLISHING, sending.publishState());
        ReactorException thrown = assertThrows(ReactorException.class, () -> sending.pushFrame(new byte[4], 1, 1));
        // Not "call publish()" — the caller just did. There is simply no sender yet.
        assertTrue(thrown.getMessage().contains("Await"), thrown.getMessage());

        fake.settleLastCall(true, "{}", null);
        assertEquals(PublishState.PUBLISHED, sending.publishState());
        sending.pushFrame(new byte[4], 1, 1);
        assertEquals(1, fake.videoFramesPushed);
    }

    @Test
    @DisplayName("a failed publish goes back to unpublished, so a retry means something")
    void aFailedPublishIsRetryable() {
        Track sending = reactor.track("camera_in");
        sending.publish();

        fake.settleLastCall(false, null, "{\"code\":\"SERVER_ERROR\",\"message\":\"busy\",\"recoverable\":true}");

        assertEquals(PublishState.UNPUBLISHED, sending.publishState());
    }

    @Test
    @DisplayName("a BGRA buffer of the wrong size raises, naming both numbers")
    void aWrongSizedFrameNamesBothNumbers() {
        Track sending = publishedCamera();

        ReactorException thrown = assertThrows(ReactorException.class, () -> sending.pushFrame(new byte[100], 4, 4));

        assertTrue(thrown.getMessage().contains("64"), "must name what was required: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("100"), "must name what was given: " + thrown.getMessage());
    }

    @Test
    @DisplayName("dimensions that would overflow an int are rejected, not silently accepted")
    void overflowingDimensionsAreRejected() {
        Track sending = publishedCamera();

        // width * height * 4 overflows int here and lands on a small positive number. Computed in
        // int, this comparison would accept the buffer and the FFI would read far past its end.
        ReactorException thrown =
                assertThrows(ReactorException.class, () -> sending.pushFrame(new byte[1024], 40000, 40000));

        assertTrue(thrown.getMessage().contains("6400000000"), thrown.getMessage());
    }

    @Test
    @DisplayName("a PCM buffer on a video track raises, naming the buffer type it wanted")
    void aMismatchedBufferNamesWhatItExpected() {
        Track sending = publishedCamera();

        ReactorException thrown =
                assertThrows(ReactorException.class, () -> sending.pushFrame(new short[64], 48000, 1));

        assertTrue(thrown.getMessage().contains("BGRA byte[]"), thrown.getMessage());
    }

    @Test
    @DisplayName("publish state is cleared when the status leaves ready")
    void publishStateDoesNotSurviveLeavingReady() {
        Track sending = publishedCamera();
        assertEquals(PublishState.PUBLISHED, sending.publishState());

        leaveReady();

        // A reconnect resumes recvonly tracks and nothing else. Remembering this as published
        // would let the caller push into a slot with no sender and see nothing arrive.
        assertEquals(PublishState.UNPUBLISHED, sending.publishState());
    }

    @Test
    @DisplayName("a failed unpublish leaves the track published, so retrying means something")
    void aFailedUnpublishIsRetryable() {
        Track sending = publishedCamera();
        fake.unpublishError = "{\"code\":\"DISCONNECTED\",\"message\":\"gone\",\"recoverable\":true}";

        ReactorException thrown = assertThrows(ReactorException.class, sending::unpublish);

        assertEquals("DISCONNECTED", thrown.code());
        assertEquals(PublishState.PUBLISHED, sending.publishState(), "a failed unpublish must stay retryable");

        fake.unpublishError = null;
        sending.unpublish();
        assertEquals(PublishState.UNPUBLISHED, sending.publishState());
    }

    @Test
    @DisplayName("an audio format the FFI drops is refused, not accepted")
    void unsupportedAudioFormatsAreRefused() {
        Track microphone = publishedMicrophone();

        // The FFI takes 8/16/24/32/44.1/48 kHz in mono or stereo and logs-and-drops the rest, so
        // these returned normally and produced silence.
        ReactorException rate =
                assertThrows(ReactorException.class, () -> microphone.pushFrame(new short[480], 12_345, 1));
        assertTrue(rate.getMessage().contains("12345"), rate.getMessage());
        assertTrue(rate.getMessage().contains("48000"), rate.getMessage());

        ReactorException channels =
                assertThrows(ReactorException.class, () -> microphone.pushFrame(new short[480], 48_000, 3));
        assertTrue(channels.getMessage().contains("mono or stereo"), channels.getMessage());

        // And the ones it does take still work.
        microphone.pushFrame(new short[480], 48_000, 1);
        microphone.pushFrame(new short[480], 44_100, 2);
    }

    @Test
    @DisplayName("a publish that settles after the session left ready does not re-arm the track")
    void aStalePublishDoesNotReArmTheTrack() {
        Track camera = reactor.track("camera_in");
        camera.publish();

        // The session goes away while the publish is still in flight. A reconnect resumes recvonly
        // tracks and nothing else, so there is no sender behind this slot afterwards.
        leaveReady();

        // The FFI answers the publish it was asked for, successfully, because it was asked before
        // any of that. Writing PUBLISHED back here let pushFrame accept frames the FFI then dropped.
        fake.settleLastCall(true, "{}", null);

        assertEquals(PublishState.UNPUBLISHED, camera.publishState());
        ReactorException refused = assertThrows(ReactorException.class, () -> camera.pushFrame(new byte[4], 1, 1));
        assertTrue(refused.getMessage().contains("publish"), refused.getMessage());
    }

    @Test
    @DisplayName("an older publish cannot restore PUBLISHED after a successful unpublish")
    void aStalePublishDoesNotSurviveAnUnpublish() {
        Track camera = publishedCamera();
        assertEquals(PublishState.PUBLISHED, camera.publishState());

        // A second attempt is in flight when the caller unpublishes. The unpublish succeeds, so
        // there is no sender behind the slot — and then the old attempt answers.
        camera.publish();
        camera.unpublish();
        assertEquals(PublishState.UNPUBLISHED, camera.publishState());

        fake.settleLastCall(true, "{}", null);

        // A client-wide generation did not move on unpublish, so this used to write PUBLISHED back
        // and pushFrame accepted frames the FFI drops.
        assertEquals(PublishState.UNPUBLISHED, camera.publishState());
        assertThrows(ReactorException.class, () -> camera.pushFrame(new byte[4], 1, 1));
    }

    @Test
    @DisplayName("an older publish's failure cannot overwrite a newer success")
    void anOlderFailureDoesNotOverwriteANewerSuccess() {
        Track camera = reactor.track("camera_in");

        // Two attempts on one track. The second succeeds; the first fails afterwards, which is an
        // ordering the FFI is free to produce and the SDK has to survive.
        camera.publish();
        MemorySegment firstCompletion = fake.lastCompletionStub();
        MemorySegment firstUserdata = fake.lastUserdataFor();
        camera.publish();
        fake.settleLastCall(true, "{}", null);
        assertEquals(PublishState.PUBLISHED, camera.publishState());

        fake.settle(
                firstCompletion, firstUserdata, false, null, "{\"code\":\"internal_error\",\"message\":\"too late\"}");

        // One generation for the whole client could not tell these two attempts apart.
        assertEquals(PublishState.PUBLISHED, camera.publishState());
        camera.pushFrame(new byte[4], 1, 1);
    }
}
