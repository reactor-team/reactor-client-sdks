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

[`sdks/python/examples/frame_metadata.py`](../sdks/python/examples/frame_metadata.py)
does exactly this over a live track and prints a summary of how many
frames carried a trailer.

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
