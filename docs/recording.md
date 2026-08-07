# Recording

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

## `Clip`

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

See [frame-metadata.md](frame-metadata.md) for tagging and reading
per-frame data on live video tracks — a separate feature from recording,
despite both dealing with video.
