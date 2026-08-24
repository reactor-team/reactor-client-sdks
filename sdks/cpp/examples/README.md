# Reactor C++ SDK examples

Seven examples, one capability each. The same seven exist in every Reactor SDK,
so the set doubles as a conformance grid: **an example missing from an SDK is a
code path that SDK has never run.**

## The set

| # | Example | Teaches | Model |
|---|---|---|---|
| 01 | [`01_connect_and_receive.cpp`](01_connect_and_receive.cpp) | Connect, send the model's first command, read the reply, count frames | `reactor/helios` |
| 02 | [`02_upload_image.cpp`](02_upload_image.cpp) | `upload_file`, then passing the `FileRef` into a command | `reactor/helios` |
| 03 | [`03_pause_and_resume.cpp`](03_pause_and_resume.cpp) | `track.pause()` / `resume()` — nothing is generated while paused | `reactor/helios` |
| 04 | [`04_publish_track.cpp`](04_publish_track.cpp) | Publishing an input track and pushing tagged, stamped frames into it | `xmax/x2` |
| 05 | [`05_multi_connection.cpp`](05_multi_connection.cpp) | Two clients on one session: `connect({.session_id = …})` | `reactor/helios` |
| 06 | [`06_record_clip.cpp`](06_record_clip.cpp) | `request_clip` and downloading the result | `reactor/helios` |
| 07 | [`07_frame_metadata.cpp`](07_frame_metadata.cpp) | The trailer on each incoming frame: id, capture time, `user_data` | `reactor/helios` |

Every example shares one spine — connect, wait for ready, give the model the
minimum it needs, receive frames — and adds one call on top. **The diff against
01 is the lesson.**

"The minimum it needs" is per model and not optional. Helios stays silent until
`set_prompt` and then `start`; X2 needs a prompt and no `start`, because it edits
the track it is given as soon as it has one. Each example spells its own out,
taken from the model's published schema — the first place to look when nothing
arrives.

Tracks are asked for by name, `client.track("main_video")`, the way an app that
knows its model does. `client.tracks()` lists what a session declared, for
discovering them instead.

**A model name is `owner/name`.** A bare name resolves under `reactor/`, so it
works by luck of ownership and answers 403 for anyone else's model.

## Running them

```bash
export REACTOR_API_KEY=...          # https://www.reactor.inc/account/api-keys
mise run build:ffi                  # the native library the SDK links
mise run build:cpp                  # the SDK and these examples

./sdks/cpp/build/examples/01_connect_and_receive
./sdks/cpp/build/examples/02_upload_image path/to/image.png
./sdks/cpp/build/examples/06_record_clip out.mp4
```

Each runs against **production**, which is the bar: it is the only place the
whole path exists — the coordinator serving `/clips`, segments presigned onto
another host, the codecs the fleet negotiates, and the model contracts as
deployed rather than as declared in a manifest.

### Seeing the frames

A frame count proves something arrived, not that it was the right something. With
SDL2 available at build time, `REACTOR_SHOW=1` opens a window:

```bash
REACTOR_SHOW=1 ./sdks/cpp/build/examples/01_connect_and_receive
```

The drawing lives in one shared [`display.hpp`](display.hpp) so that no example
contains it; without SDL2 it compiles to nothing and the examples still build and
run. Everything else stays in the example itself — a reader who has to open two
files to understand one example is reading one file too many.

## What a clean run looks like

Two results surprise people, and neither is a fault:

**Example 06 gives you a shorter clip than you asked for.** A clip cannot contain
media the model has not generated yet, and these models generate slower than real
time. Asking for six seconds after eight seconds of wall clock yields whatever
media exists — the example prints both numbers. It is the same reason a download
waits on the session being alive rather than on a timer: readiness is in media
time.

**Example 07 prints zeros.** No published model attaches a per-frame trailer
today, so `frame_id=false timestamp=false user_data=false` is the current state of
the world rather than a broken setup. The example exists for the reading side, so
a client is written to handle a trailer when a model starts sending one; example
04 shows the sending side, which works now.

## Docs

| Page | |
|---|---|
| [Using the SDK](https://docs.reactor.inc/sdk-reference/using-the-sdk) | the per-language guides |
| [Sessions](https://docs.reactor.inc/concepts/sessions) | lifecycle, multiple connections, adoption |
| [Tracks](https://docs.reactor.inc/concepts/tracks) | input and output tracks, publishing, pausing |
| [Commands & messages](https://docs.reactor.inc/concepts/commands-and-messages) | what you send, what the model sends back |
| [File uploads](https://docs.reactor.inc/concepts/file-uploads) | `upload_file` and passing a `FileRef` |
| [Recordings](https://docs.reactor.inc/concepts/recordings) | clips, full-session recordings, playlists |
| [Frame metadata](https://docs.reactor.inc/concepts/frame-metadata) | `frame_id`, `timestamp_us`, `user_data` |
| [Model API reference](https://docs.reactor.inc/model-api-reference/overview) | per-model tracks, commands and messages |

A model's own reference page is the thing to check when a command is rejected or
no frame arrives — [Helios' schema](https://docs.reactor.inc/model-api-reference/helios/schema)
is where `start` requiring a prompt is written down. `client.request_schema()`
returns the same document from the running model, which is the more current of
the two.

## When a run fails

**A failed run is not automatically the SDK's fault.** Read the session's own
reason first:

- **Billing enforcement** can close a session mid-run, and every symptom then
  wears a disguise: a clip that never becomes ready, a track that reports itself
  unpublished, a peer connection that only says it disconnected.
- **A model may be on an older runtime** that parses the control channel as JSON
  and drops the protobuf this SDK sends — a command then times out with nothing
  logged anywhere.
- **A `CONFLICT` on connect** usually means a previous run went away without
  disconnecting and left the session orphaned. Every example here tears down in
  all paths for exactly that reason; wait for the orphan to clear and try again.
