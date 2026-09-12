# Kotlin client lifecycle

`Reactor` now provides `connect(sessionId, connectionId)`, `reconnect`, `disconnect`,
`close`, synchronous status/session reads, and removable control-event listeners.
The native contract remains [reactor_ffi.h](../../../crates/reactor-ffi/include/reactor_ffi.h).
Media and commands are subsequent stack slices.

The `TokenProvider` is suspending and receives the session being adopted. Supply a
short-lived token from your application's backend. Each `connect` resolves it again;
a changed token rebuilds the native handle. `reconnect` keeps the current session and
credentials; `disconnect` ends the session server-side. Use `connect(existingSession)`
with a freshly minted token when replacing credentials. API-key exchange is explicit
in `DevelopmentAuth.fetchJwt`, requires an explicit options object, and is for development
or trusted server applications. Never embed an API key in an Android app.

```kotlin
val reactor = Reactor("owner/model", TokenProvider { sessionId ->
    applicationBackend.tokenFor(sessionId) // Application-provided backend client.
})
val subscription = reactor.onEvent { event -> println(event) }
try {
    reactor.connect()
    // Work with this session; call reconnect() after a transient failure.
    reactor.disconnect()
} finally {
    subscription.close()
    reactor.close()
}
```

## Lifetime and threads

- A lease serializes every native handle use with destruction. Callbacks never take
  that lease. Lifecycle methods serialize initiation and waiting through a coroutine
  mutex, but close bypasses that mutex to abort a pending connect.
- `close` is suspending, idempotent, and non-cancellable once entered. Handle allocation
  and destruction run on IO; closing from Android's main dispatcher does not block it
  waiting for native teardown. Synchronous status/session reads may wait for a handle
  lease; do not use them as a polling loop on the UI thread.
- Cancellation removes an awaiter, not native work. JNI operation tickets remain until
  completion or successful destroy. Destroy=-1 consumes the handle and deliberately
  retains its callback context forever. Detached authentication owns its ticket until
  completion even after cancellation.
- Native callbacks copy data before returning. Events reach a serial coroutine worker
  on the chosen `eventDispatcher`, entering through a Default worker so even an
  Unconfined dispatcher cannot execute a listener on a native callback thread;
  completions resume through a separate Default worker,
  including for Unconfined callers. Native threads never run application listeners.
- Each listener has its own exception boundary. Failures are reported once per distinct
  class/message; a broken reporting hook is contained too. Removing a listener affects
  future snapshots, and an already executing listener may finish after close.
- The native callback ticket refers weakly to independent event/operation state. The
  event worker does not own the public wrapper. There is no GC-triggered native cleanup:
  callers must close in finally, including when connect fails or is cancelled.
- Construction always pins synthetic ADM. The package version comes from Gradle's
  `reactorVersion`; the FFI receives that version and `sdk_type=kotlin`.

## Development builds and tests

The shared JAR embeds R8 rules preserving the JNI symbols and callback methods.
Binary distribution and automatic platform loading arrive in the packaging PR. For
now, load the two development libraries explicitly before constructing a client:
`System.load(absoluteFfiPath)` followed by `System.load(absoluteJniPath)`. Android also
requires the matching relocated WebRTC JAR from the native probe.

```sh
mise run build:kotlin:native:host
mise run build:kotlin:jni:host
mise run test:kotlin:jni:real
mise run test:kotlin
# Linux/GCC: AddressSanitizer over the production bridge and fake C ABI.
mise run test:kotlin:jni:asan
```

The fake C ABI drives the actual JNI entrypoints and foreign-thread callbacks. Tests
cover synthetic ADM/package identity, session and uint32 connection adoption, token
replacement, reconnect/disconnect, handler exceptions/removal, cancellation, late
callbacks after destroy=-1, a blocked native initiation racing close, and closing from
an event listener while releasing the last application reference. The real-library
smoke test runs ten create/status/session/destroy cycles in its own process.

On Android arm64, run these separately (never load the fake and real JNI bindings in
one instrumentation process):

```sh
mise run test:kotlin:native:android
mise run build:kotlin:jni:android
cd sdks/kotlin
./gradlew :native-probe:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=inc.reactor.sdk.internal.RealNativeLifecycleTest
cd ../..
mise run test:kotlin:jni:android
```

The real smoke requires no credentials or microphone permission. It does not prove
negotiated media or a production session. Those remain explicit acceptance gates.
Sanitizer execution on this Mac is unavailable because the JVM loader rejects the
ASan runtime; the Linux CI task is the sanitizer gate. Leak detection is disabled for
that task because the JVM and intentional destroy=-1 orphans are outside its contract.
