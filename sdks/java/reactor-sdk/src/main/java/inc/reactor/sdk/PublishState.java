package inc.reactor.sdk;

/**
 * Whether a sendonly track has a sender behind it.
 *
 * <p>Three states rather than a boolean, and the middle one is the reason. A publish that has been
 * asked for and not yet answered is not published — there is no sender behind the slot, so a frame
 * pushed in that window is taken by the FFI and dropped. It is also not nothing: telling a caller
 * who has just called {@code publish()} to call {@code publish()} is no help at all.
 *
 * <p>The session does not record any of this. {@code reactor_publish_track} is a request and
 * {@code reactor_unpublish_track} a notification, and neither leaves anything to query, so the SDK
 * keeps it — and clears it whenever the status leaves {@code ready}, because a reconnect resumes
 * recvonly tracks and nothing else.
 */
public enum PublishState {

    /** No sender. Pushing raises. */
    UNPUBLISHED,

    /** A publish was asked for and has not been answered. Pushing raises, saying to await it. */
    PUBLISHING,

    /** There is a sender behind the slot. Pushing works. */
    PUBLISHED
}
