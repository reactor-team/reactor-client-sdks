/// Strings crossing the FFI boundary, and who owns them.
///
/// The header states this per function, and it is easy to get backwards in all
/// three directions: freeing the static one corrupts the heap, freeing a
/// borrowed one is a double free, and not freeing an owned one leaks on every
/// property read. There are exactly two initialisers below, so the SDK never has
/// to decide again at a call site — it decides by picking one.
///
/// | Kind | Which functions | What to do |
/// |---|---|---|
/// | Static | `reactor_status` | copy, never free |
/// | Owned | `reactor_session_id`, `reactor_tracks`, `reactor_paused_tracks`, `reactor_unpublish_track`'s failure | copy, then `reactor_free_string` |
/// | Borrowed | every string handed *to* a callback | copy before returning; the FFI frees it |
extension String {

    /// Copy a string the FFI heap-allocated for this caller, then release it.
    ///
    /// The copy and the free happen in one expression with nothing between them,
    /// which is the point: there is no window in which an early return, a throw
    /// or a `guard` can skip the free. `nil` in is `nil` out — the getters return
    /// null for "no session yet" and for a null handle, and neither is an error.
    init?(
        takingOwnership raw: UnsafeMutablePointer<CChar>?,
        freeing free: (UnsafeMutablePointer<CChar>?) -> Void
    ) {
        guard let raw else { return nil }
        defer { free(raw) }
        self.init(cString: raw)
    }

    /// Copy a string the FFI still owns.
    ///
    /// Covers both non-owned cases, because the SDK treats them identically —
    /// copy now, free never:
    ///
    /// - the **static** string `reactor_status` returns, which outlives
    ///   everything and must not be freed;
    /// - a **borrowed** string handed to a callback, which the FFI frees as soon
    ///   as the callback returns. Keeping the pointer instead of the copy is a
    ///   use-after-free that reproduces under load and not in tests.
    init?(borrowing raw: UnsafePointer<CChar>?) {
        guard let raw else { return nil }
        self.init(cString: raw)
    }
}
