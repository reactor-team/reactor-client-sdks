# Errors and coroutine completions

`ReactorError` has a catchable subtype for every shared error code and preserves
unknown codes on the base type. Status, operation, retry delay and event timestamp
are retained. Recoverability follows reactor-core's code mapping and is checked by
`check-error-codes-parity.py`, rather than trusting a contradictory payload flag.
Failed calls and error events use the same decoder and error hierarchy.

The internal `CompletionRegistry` owns suspended callers, not native work.
It validates/decodes a response before claiming its entry, then removes it under
a lock and settles outside the lock. Cancellation, duplicate callbacks and close
can each win only once. An absent success payload is Kotlin null; a JSON null is
`JsonNull`; malformed data fails with `DecodeFailedError`.

Closing settles all callers with `AbortedError`, explicitly stating that native
work (including a file download) may still finish. Cancelling an await removes its
entry and does not claim to stop the native task. The later client-lifetime layer
must hold a handle lease around the supplied start callback; the registry does
not make native pointers safe or serialize calls on a client handle.

For handle-independent operations, `completeDetached` owns the native callback
ticket until the FFI's exactly-once completion arrives, independently of whether
its Kotlin receiver is still alive. The fake JWT exchange test completes on a
foreign thread after cancellation and registry close, with no Reactor handle.
Real authentication and clip-download entry points belong to subsequent slices.

Tests cover typed/unknown errors, metadata, malformed successes, absent payloads,
cancellation, duplicate delivery, start failures, idempotent close and a
close-during-decode race controlled with latches. JNI tests additionally cover
completion-owned ticket lifetime after cancellation. Run `mise run test:kotlin`
and `mise run test:kotlin:jni:android`.
