# Optional desktop media helpers

Depend on the separate `reactor-desktop` module to opt into Java Sound and Swing.
`reactor-core`, `reactor-jvm`, and the Android artifact have no dependency on this module.
Creating a Reactor or a helper does not open a microphone, speaker, or window. The native
client still uses synthetic ADM; these helpers explicitly push/receive SDK PCM.
The desktop module requires a JVM with `java.desktop` and an OS audio device supported by
its Java Sound provider. No extra media dependency is added to the core.

```kotlin
import inc.reactor.sdk.desktop.DesktopMicrophone
import inc.reactor.sdk.desktop.DesktopSpeaker
import inc.reactor.sdk.desktop.DesktopAudioFormat

val microphone = DesktopMicrophone()
val speaker = DesktopSpeaker(DesktopAudioFormat(sampleRate = 48000, channels = 1))
try {
    val input = reactor.tracks.withKind(TrackKind.AUDIO).withDirection(TrackDirection.SENDONLY).one()
    val output = reactor.tracks.withKind(TrackKind.AUDIO).withDirection(TrackDirection.RECVONLY).one()
    input.publish() // Wait for the sender before capturing.
    withContext(Dispatchers.IO) {
        microphone.start(input)
        speaker.start(output)
    }
    // Keep the client alive while the application uses audio.
} finally {
    withContext(Dispatchers.IO) { microphone.close(); speaker.close() }
    reactor.close()
}
```

Start/close are blocking device operations: run them off the UI thread. Each helper is
single-use; create a new one to restart after stop, device loss, or reconnect. The platform
may require microphone permission. Failure to open the default device is reported to the
start caller; asynchronous device/consumer failures go to `onFailure` and stop that worker.
Throwing reporters are contained. Device selection/resampling is not implemented in this
slice: configure the requested sample rate/channels to match the track and device.

Capture reads 10 ms blocks and explicitly converts little-endian signed PCM16 into owned
interleaved `ShortArray`s. Playback converts back, handles partial frame-aligned writes,
and refuses mismatched formats or submissions larger than 20 ms. Its queue holds at most
four blocks (80 ms); overflow drops the oldest queued block. Java Sound is asked for a
40 ms device buffer; the provider may round that size. One block can also be in flight.
Neither queue size nor latency grows with a blocked speaker.

Closing marks the worker inactive before closing the device, so a capture read released
by stop cannot publish its stale block. Device teardown holds no lock used by capture or
render work. Close from the worker skips joining itself; external close waits up to two
seconds and reports a still-blocked worker. An already running user callback can outlive
that bounded wait. Helpers cannot restart into that worker's state. The Java Sound
[read](https://docs.oracle.com/en/java/javase/17/docs/api/java.desktop/javax/sound/sampled/TargetDataLine.html#read(byte[],int,int))
and [write](https://docs.oracle.com/en/java/javase/17/docs/api/java.desktop/javax/sound/sampled/SourceDataLine.html#write(byte[],int,int))
contracts allow close/stop to release blocked I/O with a partial result.

For visual checks, construct `DesktopVideoWindow` explicitly and call `show(VideoFrame)`
from a recvonly video handler. It copies the BGRA frame, keeps only the latest pending
frame, converts to ARGB, and updates Swing on the EDT. Close the frame subscription and
window in `finally`. Headless JVMs fail with an actionable error. No GUI is opened by the
core or by audio helpers.

The normal CI suite uses a fake device backend to reproduce capture/stop and render/stop
races, partial writes, device loss, throwing callbacks, PCM signedness, bounded queues,
and pixel conversion without touching hardware. An explicit local check is available:

```sh
cd sdks/kotlin
./gradlew :reactor-desktop:testHardware
```

That opt-in check opens the microphone for ten 10 ms blocks and plays a low-amplitude
100 ms tone. Captured audio is neither retained nor sent anywhere. It checks device I/O;
a person still needs to confirm audible output and the example window on a supported
desktop before signing off the manual media acceptance criterion.
