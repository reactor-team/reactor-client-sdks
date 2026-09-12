# Uploads and Android content inputs

```kotlin
import inc.reactor.sdk.uploadBytes
import inc.reactor.sdk.uploadFile

val image = reactor.uploadBytes(pngBytes, "image.png", "image/png")
reactor.sendCommand("set_image", uploads = mapOf("image" to image))
val document = reactor.uploadFile(File("document.pdf"))
```

Uploads return a `FileRef` with `uploadId`, `name`, `mimeType` and an exact unsigned `size`.
The command sends that metadata under the named upload parameter, not the file bytes.
`FileRef.toJson()` also supports references inside nested JSON lists/objects. A parameter
cannot appear in both the JSON arguments and the upload-reference map.

File uploads use native filename/MIME inference. The caller owns the source file and must
keep it readable while native work may still use it, including after cancelling the
coroutine. The SDK never removes a caller-owned file. Missing files and denied permissions
are reported as typed, actionable errors before the native call where possible; later
native failures retain the FFI's error code. Byte uploads preserve the supplied name/MIME,
including empty content. JNI copies the Java array, and the FFI copies that buffer before
the call returns. Do not mutate the input array concurrently with that call.

On Android, use the optional platform adapter from `reactor-android`:

```kotlin
import inc.reactor.sdk.android.uploadContent

val image = reactor.uploadContent(context, selectedUri, name = "image.png")
reactor.sendCommand("set_image", uploads = mapOf("image" to image))
```

The host app must declare `android.permission.INTERNET` for network calls.
The URI must have a `content` scheme and an existing read grant. It is opened through
`ContentResolver`, never passed to the C API as a filesystem path. Supply a portable
filename with the correct extension; native file upload infers the MIME type from it.
The adapter does not acquire or persist URI permissions on the application's behalf.

The shared `uploadStream(name, cacheDirectory, maxBytes, open)` helper stages content in a
unique directory within the supplied cache, using 64 KiB reads. It opens/closes the stream
on the IO dispatcher, checks cancellation between reads, and limits staged bytes to 64 MiB
by default. The limit is configurable, including zero for empty content. This bounds the
staging copy's memory and disk use; the current FFI subsequently reads the file into memory
for upload, so choose the limit for your application's memory budget.

Cleanup follows native lifetime rather than coroutine lifetime:

- Copy failures, exceeded limits, and cancellation before native submission remove staging
  immediately. Missing/closed native handles are rejected before the source is opened.
- After submission, JNI owns the private file and directory. It removes them when the native
  completion arrives, before settling the caller, even if the caller stopped waiting.
- Closing the client settles the awaiter immediately but deletes staging only after native
  destruction reports quiescence. If destruction returns `-1`, staging stays with the
  orphaned native operation until its completion. If that callback never returns, the
  retained cache file is intentional; deleting it early could invalidate native work.

Tests drive the actual C callbacks and delay native file reads until after coroutine
cancellation or client close. They cover caller-file preservation, empty and non-ASCII
byte uploads, exact command metadata, an 8 MiB generated stream with bounded reads,
permission/limit failures and malformed references. Android instrumentation additionally
uses a real test ContentProvider for normal and revoked reads. The core has no Android
imports or dependency; content URI handling lives exclusively in the Android module.
