# Tracks and receiving media

Tracks are model-declared slots, returned by `Reactor.tracks` in declaration order.
`track(name)` and `tracks.withKind(...).withDirection(...).one()` fail with the available
names when the lookup is invalid or ambiguous. Track references hold their client weakly.

```kotlin
val video = reactor.tracks.withKind(TrackKind.VIDEO)
    .withDirection(TrackDirection.RECVONLY).one()
val subscription = video.onFrame { frame ->
    val image = frame as VideoFrame
    consumeBgra(image.pixels, image.width, image.height)
}
try {
    // Keep the subscription for as long as frames are wanted.
    video.pause()
    video.resume()
} finally {
    subscription.close()
}
```

Receive handlers run inline on the native delivery thread. A slow handler therefore
preserves the FFI's bounded backpressure (newest video frame; short audio queue).
Do not use an unbounded queue to transfer frames to a UI thread. Control events remain
on the selected Kotlin dispatcher, including the new `TrackReceived(name, mid)` event.

- `VideoFrame` owns BGRA bytes and an optional metadata byte array. `frameId` and
  `timestampMicros` preserve all 64 bits. The timestamp is in the sender's clock domain;
  it is not comparable with local monotonic or Unix time. Zero denotes absent metadata.
- `AudioFrame` owns interleaved signed PCM16 samples and exposes the actual sample rate
  and channel count. `samplesPerChannel` is distinct from the total array size. This binds
  the format-preserving FFI contract merged in PR #172; it does not assume 48 kHz mono.
- Buffers are copied before the FFI callback returns and can be retained. Handlers share
  a frame's owned arrays; treat them as read-only if multiple listeners are registered.
- `mid` is refreshed by track events and cleared when the connection leaves ready.
  `paused` reads the native state with owned-string cleanup instead of caching it.
- Snapshot updates use a revision guard: a capability event or newer completed read
  prevents an older read from overwriting current declarations. Native handle epochs
  also prevent orphan callbacks from changing a replacement handle's state.
- Receive registration on a sendonly slot is rejected. Unknown/wrong-kind frames and
  malformed native buffers are dropped with deduplicated diagnostics. One throwing
  media handler cannot prevent the next handler from receiving the frame.
- Removing a subscription prevents new invocations and, when called outside a media
  callback, waits for already-running invocations. Removal inside a media callback
  does not wait: callbacks must not join themselves or one another. After a declaration
  changes kind/direction, refresh the Track and register a matching handler.

JNI fake-library tests drive actual C callback pointers on foreign threads. They verify
routing, retained arrays, unsigned metadata, PCM 44.1 kHz stereo, pause/resume, invalid
buffer rejection, unknown tracks, removal during an in-flight delivery, self-removal,
handler exceptions, deterministic snapshot invalidation and orphan callbacks. The same
suite runs on JVM/CheckJNI, Android arm64 and the Linux AddressSanitizer gate.

Rebuild both the JNI bridge and FFI after changing declarations; an old development
JNI library is not interchangeable with a new Kotlin JAR. The isolated real-library
smoke remains a create/read/destroy test. Production negotiated-media validation remains
an explicit gate in the native probe and later live integration suite.
