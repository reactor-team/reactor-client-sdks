# Messaging

Once a session is `ready`, `send_command` and a handful of events are how
you talk to the model and the platform — you never see a raw channel or
wire format directly. This assumes you've read
[concepts.md](concepts.md) (scopes, tracks, capabilities).

## Sending a command

```python
r.send_command("set_prompt", {"text": "a red bicycle"})   # application scope (default) — goes to the model
r.send_command("ping", {}, scope="runtime")                 # runtime scope — goes to the platform
```

`send_command` is fire-and-forget: it returns `0` on success or `-1` if
the handle isn't connected, the payload is too large (256 KiB by default),
or it couldn't be serialized. There is no per-command acknowledgment — use
the `message` / `runtime_message` events below to observe whatever
response the model or platform chooses to send back.

## Receiving messages

```python
r.on("message", lambda msg: print("model said:", msg))             # application scope
r.on("runtime_message", lambda msg: print("platform said:", msg))  # runtime scope
```

Both handlers receive the already-parsed JSON body — not a raw string, and
not wrapped in any envelope. Messages are parsed leniently: an unrecognized
field or a new message `type` from a newer model/runtime won't raise, so
don't assume you've seen the full set of message types a given model can
emit.

## Capabilities negotiation

Once the session is `ready`, one `capabilities_received` event tells you
what the model actually supports — which tracks exist and in which
direction, and (optionally) which commands it accepts:

```python
r.on("capabilities_received", lambda caps: print(caps["tracks"]))
```

```json
{
  "protocol_version": "1.0",
  "tracks": [
    { "name": "main_video", "kind": "video", "direction": "recvonly" },
    { "name": "webcam", "kind": "video", "direction": "sendonly" }
  ],
  "commands": [{ "name": "set_prompt", "description": "Set the prompt" }],
  "emission_fps": 30.0
}
```

Use the track `name`s from here — not names you invent — as the
`track_name` argument everywhere else (`publish_track`,
`push_video_frame`, `pause_track`, ...): they're model-defined, and two
models can expose completely different tracks.

## Track control

Four calls manage a track's pub/sub state:

| Call | Awaits a response? | Effect |
|---|---|---|
| `publish_track(name)` | Yes | Claims the exclusive publisher slot for a `sendonly` track. Raises if another publisher already holds it. |
| `unpublish_track(name)` | No (fire-and-forget) | Releases the slot. |
| `pause_track(name)` | Yes | Stops delivery on a `recvonly` track without tearing down the subscription. |
| `resume_track(name)` | Yes | Resumes delivery. |

```python
await r.publish_track("webcam")     # must succeed before push_video_frame/push_audio_frame do anything
...
r.unpublish_track("webcam")

await r.pause_track("main_video")   # stop receiving frames on a recvonly track
await r.resume_track("main_video")  # resume
```

`push_video_frame` / `push_audio_frame` are silent no-ops until the
corresponding track has actually been published — a common source of "why
isn't anything happening" when wiring up a new track. See
[`sdks/python/README.md`](../sdks/python/README.md#api-reference) for
exact call signatures, and
[`sdks/python/examples/pause_resume.py`](../sdks/python/examples/pause_resume.py)
for a full pause/resume cycle with frame-count verification.
