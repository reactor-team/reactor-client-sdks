package inc.reactor.sdk.android

/**
 * Whether a sendonly slot has a sender behind it.
 *
 * Three states, not two, and the middle one is the whole point. A publish asked for and not yet
 * answered is **not** published: there is no sender behind the slot, so a push in that window is
 * taken by the FFI and dropped. Counting it as published reintroduces exactly the silent failure
 * this SDK exists to prevent; counting it as unpublished tells a caller who just called
 * `publish()` to call `publish()`.
 */
public enum class PublishState {
    /** No sender. Pushing raises. */
    UNPUBLISHED,

    /** Asked for, not yet answered. Pushing raises, and says to await the publish. */
    PUBLISHING,

    /** A sender is attached. Pushing works. */
    PUBLISHED,
}
