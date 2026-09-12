# Clips, recordings and downloads

```kotlin
val clip = reactor.requestClip(durationSeconds = 5.0)
val recording = reactor.requestRecording() // Session up to now.
val result = reactor.download(clip, File("clip.mp4")) { progress ->
    println("${progress.done}/${progress.total} segments")
}
```

`Clip` exposes session/kind, media-time markers, the readiness prediction and playlist URL.
`DownloadResult` carries the output file, unsigned byte count and segment count.
The downloader is the existing Rust implementation described in the
[FFI contract](../../../../crates/reactor-ffi/include/reactor_ffi.h): init fragment first,
bearer authorization only on the playlist origin, and session-aware readiness for a 202.
The binding does not parse HLS or implement another HTTP transport.

The default readiness timeout is negative: wait while the session can still produce media.
Either infinity also means unbounded grace; NaN is rejected before entering JNI. Finite
nonnegative values are grace seconds past the runtime prediction. This is not a timeout
for the entire download. Clip duration must be finite and positive.

Progress is delivered on a background dispatcher with one conflated pending value. Slow
handlers may miss intermediate counts. Handler exceptions reach the configured
`onHandlerFailure` reporter and do not stop the native download. Completion/cancellation
can suppress queued progress; an already running handler may finish after close.

Always close the client in `finally`. Closing settles Kotlin awaiters with `AbortedError`,
but the native download may continue writing, even after destroy returned zero. Cancelling
an awaiter also does not cancel native work. JNI retains each detached callback ticket
until its one completion, independent of client lifetime. Do not delete, move, copy or reuse
an output while native work might still write it. Kotlin cancellation/close never removes
the output; the Rust downloader may remove it when its own download fails. Successfully
returning from `download` is the safe point to consume
or copy that output. Cancellation means the caller no longer has that completion signal.

On Android, use app-private output and copy only after successful completion:

```kotlin
import inc.reactor.sdk.android.createRecordingOutput
import inc.reactor.sdk.android.copyToContent

val output = createRecordingOutput(context)
val result = reactor.download(reactor.requestRecording(), output)
result.copyToContent(context, destinationUri)
```

The app must declare `android.permission.INTERNET` and already hold the destination URI's
write grant. The adapter opens through `ContentResolver`, truncates the destination, copies
in 64 KiB blocks on IO, and closes streams. It preserves the source. Permission errors are
typed and actionable. Cancellation or provider failure may leave a partial destination;
the application owns its cleanup. `content://` is never passed as a native filesystem path.

Tests drive actual C callbacks after cancellation and destroy, including throwing progress
handlers. Android tests exercise a real ContentProvider. The isolated `testRealNative`
JVM task checks the Rust downloader against two local HTTP origins using the real producer's
fMP4 manifest shape: init first, authenticated same-origin fragments, unauthenticated
presigned cross-origin fragments. These local checks do not replace the later production
recording scenario/integration gate.
