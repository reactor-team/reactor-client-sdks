# Recording and frame metadata

## Clips and full-session recordings

Requesting a clip is a single `await` — the SDK handles the underlying
runtime-scoped command and correlates the response for you:

```python
clip = await r.request_clip(10.0)      # last 10 seconds of the session
# or
clip = await r.request_recording()     # the whole session so far

print(clip.playlist_url)   # HLS manifest — absolute, or resolve against the coordinator base URL
```

Both are `async` and resolve (or raise `ReactorFFIError`) through the same
completion-callback bridge as `connect()` — there's no separate
`clip_ready` / `clip_failed` event to subscribe to; `await` the call.

### `Clip`

| Field | Meaning |
|---|---|
| `session_id` | Session the clip belongs to. |
| `kind` | Free-form string (`"snap"` or `"recording"` today) — kept open-ended for forward compatibility, don't exhaustively match on it. |
| `start_marker`, `end_marker` | Session-relative seconds covered by the clip. |
| `now_marker` | Session-relative seconds at the moment the clip was requested. |
| `predicted_ready_at_ms` | Unix epoch milliseconds the runtime predicts the chunk will be written — a prediction, not a guarantee; poll or retry the playlist URL if you fetch it immediately. |
| `playlist_url` | HLS manifest (`.m3u8`) listing the clip's `.ts` segments. |

[`sdks/python/examples/record.py`](../sdks/python/examples/record.py) shows
both request kinds plus a `--download` flag that fetches every segment
listed in the playlist and concatenates them to a file — useful as a
starting point for anything beyond "play the HLS URL directly."

## Frame metadata

Reactor models can attach a metadata trailer to individual encoded video
frames, in either direction: a frame you push out can carry your own tag,
and a frame you receive can carry one the model attached. This needs no
separate negotiation — a peer that doesn't support trailers simply sends
frames without one, which every binding surfaces as `frame_id=0`,
`timestamp_us=0`, empty `user_data`.

The trailer carries three fields, delivered as extra arguments on the
`frame` event:

| Field | Meaning |
|---|---|
| `frame_id` | Monotonically increasing counter set by the sender. `0` means no trailer was present. |
| `timestamp_us` | Wall-clock microseconds set by the sender. `0` when no trailer. |
| `user_data` | Arbitrary application bytes — UTF-8 text, JSON, binary, whatever you and the model agree on. |

### Tagging an outbound frame

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

### Reading trailers on an inbound frame

```python
def on_frame(data, width, height, frame_id, timestamp_us, user_data):
    if frame_id or timestamp_us or user_data:
        print(f"frame #{frame_id} ts={timestamp_us/1e6:.3f}s tag={user_data!r}")

r.on("frame", on_frame)
```

[`sdks/python/examples/frame_metadata.py`](../sdks/python/examples/frame_metadata.py)
does exactly this over a live track and prints a summary of how many
frames carried a trailer.

### Round-tripping a tag

Some models (e.g. an echo model used for testing) return each frame's
metadata on the processed frame they produce, letting a client pair
outbound and inbound frames without a side channel —
[`sdks/python/examples/frame_metadata_roundtrip.py`](../sdks/python/examples/frame_metadata_roundtrip.py)
tags outgoing frames with a sequence number, waits for the same tag to
come back, and reports round-trip latency and ordering. It's also the
reference for what "no trailer support" looks like from the client side: if
nothing comes back, the model isn't echoing metadata (or the two peers
never negotiated the capability) rather than the request having failed.
