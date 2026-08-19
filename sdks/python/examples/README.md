# Reactor Python SDK examples

Six minimal examples, one per capability. Each teaches exactly one thing, and
the same six exist in every Reactor SDK — so the set doubles as a conformance
grid: an example missing from an SDK is a code path that SDK has never run.

For a full application rather than a lesson, see [`pygame_app/`](pygame_app) —
live video, speaker playback and a UI built from the model's own capabilities.

## The set

| # | Example | Teaches | Model |
|---|---|---|---|
| 01 | [`01_connect_and_receive.py`](01_connect_and_receive.py) | Connect, send the model's first command, read the reply, count frames | Helios |
| 02 | [`02_pause_and_resume.py`](02_pause_and_resume.py) | `track.pause()` / `track.resume()` — nothing generated while paused | Helios |
| 03 | [`03_publish_track.py`](03_publish_track.py) | `upload_file` for a conditioning image, then publishing and pushing frames | morpheus-v4 |
| 04 | [`04_multi_connection.py`](04_multi_connection.py) | Two clients on one session: `connect(session_id=…)` | Helios |
| 05 | [`05_record_clip.py`](05_record_clip.py) | `request_clip` and downloading the result | Helios |
| 06 | [`06_frame_metadata.py`](06_frame_metadata.py) | Per-frame `user_data` out and back, with the round-trip timed | local runtime |

Every example shares one spine — connect, wait for ready, give the model the
minimum it needs, receive frames — and adds one new call on top. The diff
against 01 is the lesson.

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

Against a local runtime instead of the cloud, same files:

```bash
REACTOR_LOCAL=1 REACTOR_MODEL=my-model uv run python examples/01_connect_and_receive.py
```

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

The one exception is example 03. What morpheus-v4 demands — a conditioning image —
is model trivia, but `upload_file` is an SDK capability, and a capability hidden
in a helper is one nobody sees and nobody tests. So it is in the example.
