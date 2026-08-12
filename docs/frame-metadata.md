# Frame metadata

Reactor models can attach a metadata trailer to individual **video**
frames, in either direction: a frame you push out can carry your own tag,
and a frame you receive can carry one the model attached. Typical use:
correlate a specific output frame with the input that produced it (a
request ID, a prompt version, a timestamp from your own pipeline) without
a side channel. **Video only** — `push_audio_frame` has no metadata
parameter and the `audio` event carries no trailer.

Tagging needs no separate negotiation — a peer that doesn't support
trailers simply sends frames without one, which the SDK surfaces as
`frame_id=0`, `timestamp_us=0`, empty `user_data`, so a model that predates
this feature (or doesn't use it) is indistinguishable from "no tag on this
particular frame."

The trailer carries three fields, delivered as extra arguments on the
`frame` event:

| Field | Meaning |
|---|---|
| `frame_id` | Monotonically increasing counter set by the sender. `0` means no trailer was present. |
| `timestamp_us` | Wall-clock microseconds set by the sender. `0` when no trailer. |
| `user_data` | Arbitrary application bytes — UTF-8 text, JSON, binary, whatever you and the model agree on. |

## Tagging an outbound frame

```python
r.push_video_frame(
    "webcam",
    frame_bgra,
    width,
    height,
    user_data=b'{"seq": 42}',   # reaches the model as this frame's metadata
)
```

A tag is dropped (the frame still sends) unless the far end declared that
it reads trailers — so tagging is always safe, whatever the model was
built against. Untagged calls (`push_video_frame(name, data, w, h)`, no
`user_data`) go through the plain path with no trailer at all.

## Reading trailers on an inbound frame

```python
def on_frame(data, width, height, frame_id, timestamp_us, user_data):
    if frame_id or timestamp_us or user_data:
        print(f"frame #{frame_id} ts={timestamp_us/1e6:.3f}s tag={user_data!r}")

r.on("frame", on_frame)
```

The `@r.on_frame` decorator is the same event with a nicer shape: it hands
your handler a decoded RGB `numpy` array instead of raw BGRA bytes, and
you can declare only the trailer fields you actually want:

```python
@r.on_frame
def on_frame(frame, frame_id, timestamp_us, user_data):
    ...
```

[`sdks/python/examples/frame_metadata.py`](../sdks/python/examples/frame_metadata.py)
does exactly this over a live track and prints a summary of how many
frames carried a trailer.

`frame` delivery applies backpressure: if your handler falls behind the
incoming rate, only the newest frame is kept and the ones in between are
dropped (see [concepts.md](concepts.md#where-handlers-run)). A gap in
`frame_id` doesn't mean a bug — under load, it's the expected way frames
get dropped.

## Round-tripping a tag

Some models (e.g. an echo model used for testing) return each frame's
metadata on the processed frame they produce, letting a client pair
outbound and inbound frames without a side channel —
[`sdks/python/examples/frame_metadata_roundtrip.py`](../sdks/python/examples/frame_metadata_roundtrip.py)
tags outgoing frames with a sequence number, waits for the same tag to
come back, and reports round-trip latency and ordering. It's also the
reference for what "no trailer support" looks like from the client side: if
nothing comes back, the model isn't echoing metadata (or the two peers
never negotiated the capability) rather than the request having failed.

## Across two processes

Tagging isn't limited to a single client round-trip — a session's tags are
visible to anyone connected to it.
[`sdks/python/examples/metadata_publisher.py`](../sdks/python/examples/metadata_publisher.py)
publishes tagged frames with no UI, printing the session ID; a second
process (the [`pygame_app`](../sdks/python/examples/pygame_app/) example)
joins that same session by ID and renders each frame's tag on screen.
