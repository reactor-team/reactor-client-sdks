# Reactor Python SDK examples

Eight minimal examples, one per capability. Each teaches exactly one thing, and
the first seven exist in every Reactor SDK — so that part of the set doubles as
a conformance grid: an example missing from an SDK is a code path that SDK has
never run. `08` matches the JS SDK's `07` (numbered differently only because JS
has no native frame-metadata example).

## The set

| # | Example | Teaches | Model |
|---|---|---|---|
| 01 | [`01_connect_and_receive.py`](01_connect_and_receive.py) | Connect, send the model's first command, read the reply, count frames | Helios |
| 02 | [`02_upload_image.py`](02_upload_image.py) | `upload_file`, then passing the `FileRef` into a command | Helios |
| 03 | [`03_pause_and_resume.py`](03_pause_and_resume.py) | `track.pause()` / `track.resume()` — nothing generated while paused | Helios |
| 04 | [`04_publish_track.py`](04_publish_track.py) | Publishing an input track and pushing tagged frames into it | X2 (`xmax/x2`) |
| 05 | [`05_multi_connection.py`](05_multi_connection.py) | Two clients on one session: `connect(session_id=…)` | Helios |
| 06 | [`06_record_clip.py`](06_record_clip.py) | `request_clip` and downloading the result | Helios |
| 07 | [`07_frame_metadata.py`](07_frame_metadata.py) | The trailer on each incoming frame: id, sender timestamp, `user_data` | Helios |
| 08 | [`08_snapshot_and_rewind.py`](08_snapshot_and_rewind.py) | `send_command`'s resolved reply, read and used — not fire-and-forgotten | Helios |

Every example shares one spine — connect, wait for ready, give the model the
minimum it needs, receive frames — and adds one new call on top. The diff
against 01 is the lesson.

"The minimum it needs" is per model and not optional: Helios stays silent until
`set_prompt` and then `start`; X2 needs a prompt too, but no `start` — it edits
the live track as soon as it has both. Each example spells its own out at the
top, from the model's published schema — the first place to look when nothing
arrives.

Tracks are asked for by name, `reactor.track("main_video")`, the way an app that
knows its model does. `reactor.tracks` lists what a session declared, for
discovering them instead.

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

## Before the first run

The SDK calls into a native library, `libreactor_ffi`, that this repository
builds. A fresh checkout has no copy of it, and every example fails at import
until one exists. Two ways to get there.

**From the published wheel** — the library ships inside it, nothing to build:

```bash
pip install reactor-sdk
```

**From this checkout** — build the library once, then install the SDK from the
working tree:

```bash
mise trust && mise install            # the pinned toolchain: Rust, uv, ruff, …
cargo build -p reactor-ffi --release  # fetches the prebuilt libwebrtc for your target
cd sdks/python && uv sync             # the SDK, resolving against that build
```

The `cargo build` is the slow step, once: `build.rs` downloads a matching
prebuilt libwebrtc and the C++ glue compiles against it. `mise install` needs
mise 2026.8.8 or newer — the toolchain lock names entries an older mise cannot
read — so `mise self-update` first if yours predates it.

At import the library is looked for in `REACTOR_FFI_LIB`, then beside the
installed package, then in `target/release/` of an enclosing checkout. The third
is why building in the checkout is enough: nothing has to be copied anywhere.

Rebuild after pulling changes under `crates/`. A signature that moved in the FFI
but not in your build fails at the call rather than at load, which reads as a
hang rather than as a version error.

The rest of the setup — the Intel-Mac caveat, git hooks, the full task list —
is in [CONTRIBUTING.md](../../../CONTRIBUTING.md#getting-set-up), and how the
library is resolved is in [the SDK README](../README.md#the-native-library).

## Running them

Needs an API key from [reactor.inc](https://www.reactor.inc/account/api-keys)
and the steps above.

```bash
cd sdks/python
export REACTOR_API_KEY=rk_...

uv run python examples/01_connect_and_receive.py
uv run python examples/02_upload_image.py ref.png
uv run python examples/06_record_clip.py 5 clip.mp4
```

04 needs a model with an input track, so it defaults to X2; the rest default to
Helios. Against a local runtime, same files:

```bash
REACTOR_LOCAL=1 REACTOR_MODEL=my-model uv run python examples/01_connect_and_receive.py
```

Model names are `owner/name`, and the examples spell them out: `reactor/helios`,
`xmax/x2`. A bare name resolves under `reactor/`, so dropping the prefix works
only for models that owner publishes and answers 403 for anyone else's.

Pointing one at another model means editing the constants at the top — which is
the point: they are in the file you are already reading.

## Seeing the frames

A frame count proves something arrived, not that it was the right something.
`REACTOR_SHOW=1` puts the stream in a window:

```bash
pip install pygame-ce
REACTOR_SHOW=1 uv run python examples/01_connect_and_receive.py
```

04 and 05 show two tiles: what you push beside what comes back, and the creator's
stream beside the joiner's. In 03 the paused phase is a frozen frame. Closing the
window ends the run.

## Configuration

Environment only — each example reads what it needs at the top of the file:

| Variable | |
|---|---|
| `REACTOR_API_KEY` | required, unless `REACTOR_LOCAL=1` |
| `REACTOR_JWT` | a token to use as-is instead |
| `REACTOR_MODEL` | overrides the model the example defaults to |
| `REACTOR_API_URL` | coordinator base URL |
| `REACTOR_LOCAL` | `1` for a local runtime |
| `REACTOR_SECONDS` | how long to hold the session |
| `REACTOR_SHOW` | `1` for the window |

Two examples take an argument: `02 <image>` and `06 [seconds] [out.mp4]`.

Frames are read with `on_raw_frame` — decoded BGRA bytes, no numpy, which is also
what the window draws. `on_frame` gives the same frames as numpy arrays.

## What is shared

`display.py`, and nothing else. It is ~120 lines of pygame that teach nothing
about the SDK, identical in every example, and needed only by someone who asked
to see the frames.

Everything else is in the example: which command the model needs, which track it
emits on, which prompt goes out, how the client is built. A helper that answered
those would be a helper that hid the lesson — and an example you have to read two
files to understand is one file too many.
