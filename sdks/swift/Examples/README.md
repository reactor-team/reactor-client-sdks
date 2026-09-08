# The seven examples

The same seven every Reactor SDK ships, numbered as in
[`sdks/python/examples/`](../../python/examples). They are **parity
requirements rather than documentation**: an example missing from a binding is a
code path that binding has never run.

```bash
export REACTOR_API_KEY=rk_...          # https://www.reactor.inc/account/api-keys
mise run build:ffi                     # the native library the examples link

scripts/swift.sh run 01_connect_and_receive
```

`scripts/swift.sh run` rather than `swift run`, and for a reason worth knowing:
an example is an executable, so it links `libreactor_ffi` — and plain
`swift run` fails with a page of undefined `_reactor_*` symbols because nothing
tells the linker where that library is. A consumer never meets this, because a
consumer gets the library from the XCFramework. This is the development loop.

| Variable | |
|---|---|
| `REACTOR_API_KEY` | required against the cloud. **The environment and nowhere else** — not a file, not a commit, not an argument that lands in shell history |
| `REACTOR_SHOW=1` | write PNG snapshots of the frames that arrive |
| `REACTOR_SECONDS` | how long an example watches |
| `REACTOR_MODEL` | run against a different model |
| `REACTOR_LOCAL=1` | a coordinator running locally instead of the cloud |
| `REACTOR_API_URL` | a coordinator somewhere else |

## What each one teaches

| # | Teaches | Model |
|---|---|---|
| 01 | connect, send the model's first command, read the reply, count frames | `reactor/helios` |
| 02 | upload a file, pass the `FileRef` into a command | `reactor/helios` |
| 03 | pause and resume a track | `reactor/helios` |
| 04 | publish a track and push tagged frames into it | `xmax/x2` |
| 05 | two clients on one session, the second adopting it by id | `reactor/helios` |
| 06 | request a clip and download it | `reactor/helios` |
| 07 | read the per-frame trailer | `reactor/helios` |

The models are not interchangeable. `reactor/helios` emits nothing until
`start`, and `start` refuses without a prompt. `xmax/x2` declares an input track
(`source`, video, sendonly), needs a prompt, and takes **no** `start` — which is
why example 04 uses it and nothing else does. **That minimum is per model**, and
it is the first thing to check when nothing arrives.

A model name is `owner/name`. A bare name resolves under `reactor/`, so it works
by luck of ownership and answers 403 for anyone else's model.

## Three production behaviours that look like bugs and are not

- **A clip is clamped to the media the model has actually generated.** These
  models run slower than real time, so a ten-second clip of a short session is
  shorter than ten seconds. Example 06 generates for a while before asking.
- **No published model attaches a per-frame trailer**, so example 07 prints
  zeros. That is the example working. Example 04 pushes tagged frames, which is
  the other side of the same field.
- **A session's own reason comes before your code.** Billing enforcement can
  close a session mid-run, and every symptom then wears a disguise: a clip that
  never becomes ready, a track that reports itself unpublished, a peer connection
  that only says `disconnected`. Read the session's reason before changing
  anything.

## Seeing the frames, not just counting them

A frame count proves something arrived, not that it was the right something. With
`REACTOR_SHOW=1` each example writes the first frame and then one a second into a
temporary directory and prints the paths:

```
first frame: 1280x768
snapshot: /var/folders/…/reactor-01/frame-0001.png
frames: 302 (1280x768)
```

PNGs rather than a window, deliberately: they work over ssh and in CI, and they
are files somebody can look at afterwards. The pixel order the library speaks is
BGRA, and getting that backwards produces a picture that looks plausible and is
wrong — which is exactly what a snapshot is for.

The drawing lives in [`Support/ExampleSupport.swift`](Support/ExampleSupport.swift)
and nothing else does. Everything about *using the SDK* is spelled out in the
example itself: a reader who has to open two files to understand one example is
reading one file too many.
