/// Whether this client has a sender behind a track's slot.
///
/// The session records none of this. `reactor_publish_track` is a *request* and
/// `reactor_unpublish_track` a *notification*, and neither leaves anything to
/// query — so the binding keeps it, and keeping it right is the whole job.
///
/// ## Three states, not two
///
/// A publish asked for and not yet answered is **not** published: there is no
/// sender behind the slot yet, and a frame pushed into that window is taken by
/// the FFI and dropped. Counting it as published reintroduces exactly the silent
/// failure this SDK exists to refuse; counting it as nothing tells a caller who
/// *just called* `publish()` to call `publish()`. So it is its own state, and the
/// refusal says "await the publish" rather than "publish first".
enum PublishState: Sendable {

    /// No sender behind this slot. Pushing drops the frame.
    case unpublished

    /// A publish is in flight. Still no sender: pushing still drops the frame.
    case publishing

    /// Published, and pushes reach the far end.
    case published
}
