# Optional Android lifecycle and media adapters

`reactor-android-media` is an optional Android library (API 26+, Java 17). It depends on
`reactor-android`, lifecycle-common and coroutines-android; neither the core nor the base
Android artifact depends on it. It adds no permission declarations to the release manifest.
Construction does not open audio devices, create a camera, or enable a foreground session.

## Explicit foreground ownership

The application requests `RECORD_AUDIO`/`CAMERA` and chooses when to enable media. A denied
permission must not be followed by a start request. The microphone also checks permission
before opening and before every read; denial/revocation or system silencing ends capture
with an actionable `SecurityException`. Android can terminate an app when permissions are
revoked; no in-process adapter can intercept that termination. The app owns audio focus,
routing, privacy UI, and any foreground-service policy. This adapter is foreground-only.

```kotlin
val appContext = applicationContext // Do not capture an Activity in a long-lived factory.
val media = ForegroundMedia(lifecycle) {
    val reactor = ownClient(Reactor(model, tokenProvider))
    reactor.connect()
    val input = reactor.tracks.withKind(TrackKind.AUDIO).withDirection(TrackDirection.SENDONLY).one()
    val output = reactor.tracks.withKind(TrackKind.AUDIO).withDirection(TrackDirection.RECVONLY).one()
    input.publish()
    val microphone = ownMedia(AndroidMicrophone(appContext))
    val speaker = ownMedia(AndroidSpeaker(appContext))
    microphone.start(input)
    speaker.start(output)
}
// Call only after the application has received permission and the user enables media.
media.start()
```

`ForegroundMedia` registers its observer only on explicit `start()`. Its factory runs on IO
while the lifecycle is STARTED or RESUMED. Register each resource immediately, before any
suspending start/connect: `ownClient`, `ownMedia`, or `own` for other AutoCloseables. A factory
failure or cancellation still releases resources already registered, in reverse order.
Clients disconnect (with a five-second bound) and then close. Media resources request stop
and await release. Cleanup runs on IO, not the main thread, and failures do not skip the
remaining resources.

STOP cancels the current session; re-entering the foreground creates a fresh one only after
its cleanup finishes. `stop()` disables subsequent restarts until another explicit `start()`.
Calling `start()` again also retries a failed permission/device/session start. DESTROY removes
the observer and closes the adapter. `close()` is nonblocking; `awaitClosed()` is the explicit
completion signal. After close, create a new adapter.

Activity recreation destroys the old adapter. Before a replacement adapter opens devices,
await the previous adapter's `awaitClosed()` in the new factory; a retained application/session
owner or ViewModel can carry that handoff. Do not retain the old Activity or its views in the
factory. If a factory uses child tasks, keep them in structured coroutine scopes so they end
before resource release. The SDK does not close an application-owned camera/session/reader
unless it was registered with the resource scope.

## Audio format and buffering

`AndroidMicrophone` uses explicit AudioRecord PCM16 capture, with 10 ms device blocks. Its
configurable `deviceFormat` and `outputFormat` select sample rate and mono/stereo channels.
`AndroidSpeaker` converts received PCM to its configured AudioTrack device format. Both use
nonblocking Android read/write operations on IO, so close requests never wait for device I/O
on main. Negative return codes (including a dead device) stop the helper. Zero returns wait
briefly rather than busy-spin; partial results preserve interleaved frame boundaries.

`PcmConverter` performs stateful linear interpolation, mono duplication and stereo averaging.
Fractional phase carries across calls, so splitting a stream into blocks does not change the
result or accumulate sample-rate drift. It is a lightweight converter, not a band-limited
studio-quality resampler; applications needing higher quality can convert before submission.
It accepts core-supported rates (8/16/24/32/44.1/48 kHz), mono/stereo, and at most 100 ms per
conversion. A changed source format requires a new converter/helper.

The speaker accepts at most 20 ms per submission and queues at most four converted blocks,
dropping the oldest on overflow. One block may be writing, in addition to the platform buffer
(requested as at least 40 ms or Android's minimum). The microphone has no unbounded queue.
All queued/converted PCM is owned storage. Helpers are single-use after stop or device loss.

## Camera images and rendering

Camera2/ImageReader creation, camera selection and CAMERA permission belong to the application.
Use `CameraImageSink` as the ImageReader listener on a background Handler and publish its
sendonly video track first. It acquires the latest image, copies it, and closes every acquired
image in `finally`, including conversion errors and callbacks after shutdown. `consume(image)`
also takes image ownership. Calling it on main fails explicitly while still closing the image.
`close()` refuses new work; an already running push may finish, and `awaitClosed()` waits for
those callbacks. Stop/unregister the camera and reader through the application's resource scope.

`Image.toVideoFrame` copies without taking ownership. It handles the Image crop rectangle,
row strides, pixel strides and buffer positions. Supported inputs are YUV_420_888 and
RGBA_8888; output is packed, owned BGRA. Rotation is explicitly clockwise (0/90/180/270),
then optional horizontal mirroring in the rotated output. YUV supports BT.601 limited range
(default), BT.709 limited range and BT.601 full range; select the camera's convention. Other
color spaces/HDR require an application conversion rather than silently treating them as BGRA8.

`CameraTimestampSource.REALTIME` maps the image's elapsed-realtime nanoseconds to the current
engine clock by subtracting frame age. The timestamp must not be in the future. UNKNOWN uses
the engine time at delivery instead of assuming an unrelated sensor clock matches it. Supply
REALTIME only when the camera's timestamp-source metadata says so. These capture times are
passed as `captureTimeMicros`; they are not Unix timestamps or received-frame metadata.

`AndroidVideoRenderer` is an optional ImageView utility. It holds the view weakly, keeps only
the latest pending frame and bitmap, converts BGRA on a background worker, and changes the
view on main. Register it with `ownMedia`, and its track subscription with `own`, so both end
with the foreground scope. Closing clears only its own displayed bitmap, preserving a drawable
that the application replaced. No Compose, CameraX or other UI toolkit dependency is required.

## Validation

The normal Kotlin tasks run PCM/image/worker tests. `mise run test:kotlin:android` additionally
runs the optional module's real Activity stop/start/recreation, permission-denial, AudioTrack
and ImageView instrumentation on the CI emulator. `scripts/kotlin-jni-android-test.sh` exercises
real ImageReader/ImageWriter objects through the actual JNI send path on arm64.

The current local target is the Android SDK arm64 emulator, API 35 (Android 15). It is not a
physical-device validation. A real arm64 handset run, including actual camera/microphone,
permission revocation and a visual/audio check, remains an explicit K12 acceptance gate.

Android contracts used here:
[AudioRecord](https://developer.android.com/reference/android/media/AudioRecord),
[AudioTrack](https://developer.android.com/reference/android/media/AudioTrack),
[camera timestamp source](https://developer.android.com/reference/android/hardware/camera2/CameraMetadata#SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME),
and [permission revocation](https://developer.android.com/training/permissions/requesting#app-process-terminates).
