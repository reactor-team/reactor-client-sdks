# Commands, schemas and statistics

```kotlin
val reply = reactor.sendCommand("get_status")
println(reply?.data)
val schema = reactor.requestSchema()
val subscription = reactor.onMessage { message -> println(message) }
try {
    val stats = reactor.getStats()
    println(stats.incomingBitrateBps) // null until a measurement window exists
} finally {
    subscription.close()
}
```

`sendCommand` waits for the FFI's correlated completion. Do not send a command and then
register an event listener for its reply: the reply may already have arrived. Concurrent
commands can finish in any order and each caller receives its own answer. A null reply
means the handler acknowledged the command without returning a message. A returned reply
preserves its optional type and arbitrary JSON data (including explicit JSON null).
Arguments are JSON objects; JSON encoding preserves Unicode and embedded NUL characters,
while a C-string command name must be nonempty and contain no NUL.

`onMessage` receives unsolicited application messages; `onRuntimeMessage` receives runtime
messages. Both use the configured control dispatcher and return removable subscriptions.
Errors and malformed payloads become typed exceptions. A missing or malformed schema does
not become an empty document. Cancelling the coroutine removes its awaiter; native work may
still complete. Closing the client settles pending queries, and orphaned native callbacks
cannot revive them.

`getStats` returns `ConnectionStats`, including `InboundStats`, `OutboundStats` and
`CandidatePairStats`. Fields mirror `crates/reactor-core/src/stats.rs` in Kotlin camelCase.
Rates are computed by the core, not recomputed by the binding. Unknown summary measurements
remain nullable; `packetsLost` is signed (negative values can represent duplicates), and
unsigned counters use UInt/ULong so values are never rounded through Double. Non-finite
measurements, missing required fields and invalid scalar types fail decoding. Unknown extra
JSON fields are ignored for forward compatibility. The timestamp uses Unix milliseconds;
jitter and per-stream RTT use seconds, while the summary RTT uses milliseconds.

The shared JNI tests exercise immediate and reverse-order completions, no-message
acknowledgements, error/malformed responses, cancellation, close with a later orphan
completion, unsolicited messages and subscription removal. Statistics fixtures originate in
`sdks/python/tests/test_stats.py`, with signed/unsigned boundary cases added in Kotlin.
These tests run with CheckJNI on JVM and Android and in the Linux ASan gate. Live model
validation remains part of the later integration-suite milestone.
