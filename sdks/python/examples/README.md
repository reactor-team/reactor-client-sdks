# Reactor Python SDK examples

Seven minimal examples, one per capability. Each teaches exactly one thing, and
the same seven exist in every Reactor SDK — so the set doubles as a conformance
grid: an example missing from an SDK is a code path that SDK has never run.

For a full application rather than a lesson, see [`pygame_app/`](pygame_app) —
live video, speaker playback and a UI built from the model's own capabilities.

## The set

| # | Example | Teaches | Model |
|---|---|---|---|
| 01 | [`01_connect_and_receive.py`](01_connect_and_receive.py) | Connect, send the model's first command, read the reply, count frames | Helios |
| 02 | [`02_pause_and_resume.py`](02_pause_and_resume.py) | `track.pause()` / `track.resume()` — nothing generated while paused | Helios |
| 03 | [`03_publish_track.py`](03_publish_track.py) | Publishing an input track and pushing tagged frames into it | SANA-Streaming |
| 04 | [`04_multi_connection.py`](04_multi_connection.py) | Two clients on one session: `connect(session_id=…)` | Helios |
| 05 | [`05_record_clip.py`](05_record_clip.py) | `request_clip` and downloading the result | Helios |
| 06 | [`06_frame_metadata.py`](06_frame_metadata.py) | The trailer on each incoming frame: id, sender timestamp, `user_data` | Helios |
| 07 | [`07_upload_image.py`](07_upload_image.py) | `upload_file`, then passing the `FileRef` into a command | Helios |

Every example shares one spine — connect, wait for ready, give the model the
minimum it needs, receive frames — and adds one new call on top. The diff
against 01 is the lesson.

"The minimum it needs" is per model and not optional: Helios stays silent until
`set_prompt` and then `start`; SANA-Streaming wants `start` too, and edits the
live track rather than generating from nothing. Those sequences live in
`common.py`, taken from the models' own published schemas — check there first
when an example connects and no frame ever arrives.

## Docs

Each example links the pages that explain what it is doing; these are the ones
worth having open while reading any of them.

| Page | |
|---|---|
| [Using the SDK](https://docs.reactor.inc/sdk-reference/using-the-sdk) | the Python and JS guides |
| [Sessions](https://docs.reactor.inc/concepts/sessions) | lifecycle, multiple connections, adoption |
| [Tracks](https://docs.reactor.inc/concepts/tracks) | input and output tracks, publishing, pausing |
| [Commands & messages](https://docs.reactor.inc/concepts/commands-and-messages) | what you send, what the model sends back |
| [File uploads](https://docs.reactor.inc/concepts/file-uploads) | `upload_file` and passing a `FileRef` in a command |
| [Recordings](https://docs.reactor.inc/concepts/recordings) | clips, full-session recordings, playlists |
| [Frame metadata](https://docs.reactor.inc/concepts/frame-metadata) | `frame_id`, `timestamp_us`, `user_data` |
| [Model API reference](https://docs.reactor.inc/model-api-reference/overview) | per-model tracks, commands and messages |
| [Python SDK reference](https://docs.reactor.inc/sdk-reference/python/reactor) | `Reactor`, [`Track`](https://docs.reactor.inc/sdk-reference/python/track), [types](https://docs.reactor.inc/sdk-reference/python/types) |

A model's own reference page is the thing to check when a command is rejected or
no frame arrives — [Helios' schema](https://docs.reactor.inc/model-api-reference/helios/schema),
for instance, is where `start` requiring a prompt is written down.

## Running them

Needs an API key from [reactor.inc](https://www.reactor.inc/account/api-keys),
and the SDK installed (`pip install reactor-sdk`, or `uv sync` in a checkout).

```bash
cd sdks/python
export REACTOR_API_KEY=rk_...

uv run python examples/01_connect_and_receive.py
uv run python examples/02_pause_and_resume.py --seconds 6
uv run python examples/05_record_clip.py --clip 5 --out clip.mp4
```

Example 03 needs a model with an input track, so it defaults to SANA-Streaming;
07 needs an image to condition on:

```bash
uv run python examples/03_publish_track.py --seconds 10
uv run python examples/07_upload_image.py --image ref.png
```

Against a local runtime instead of the cloud, same files:

```bash
REACTOR_LOCAL=1 REACTOR_MODEL=my-model uv run python examples/01_connect_and_receive.py
```

A model this repo does not know gets `set_prompt` and nothing else, which is
right for most of them. If yours needs something else first, add it to
`BOOTSTRAP` in `common.py`.

Every example takes `--help`. Options come from flags, falling back to the
environment:

| Variable | Meaning |
|---|---|
| `REACTOR_API_KEY` | API key, exchanged for a session-scoped token at connect time |
| `REACTOR_JWT` | a token to use as-is, instead of an API key |
| `REACTOR_MODEL` | model to connect to (default: `helios`) |
| `REACTOR_API_URL` | coordinator base URL |
| `REACTOR_LOCAL` | `1` to talk to a local runtime |

Frames are read with `on_raw_frame`, which hands over the decoded BGRA bytes and
needs nothing installed. `on_frame` delivers the same frames as a numpy array if
you would rather have one.

## What lives in `common.py`

Options parsing, the per-model bootstrap (which command, which prompt) and the
throwaway frames two examples push. Nothing else: connecting, wiring events and
tearing down stay in every example, even though that repeats them, because they
are what you opened the file to read.

The one exception is example 07: which command carries an image, and under what
name, is model trivia — but `upload_file` is an SDK capability, and a capability
buried in a helper is one nobody sees and nobody tests. So that whole sequence is
in the example, spelled out.
