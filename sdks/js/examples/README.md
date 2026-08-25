# Reactor JS SDK examples

Six minimal examples, one per capability — the same set (minus frame
metadata, still to come) as the Python and C++ SDKs.

## The set

| # | Example | Teaches | Model |
|---|---|---|---|
| 01 | [`01-connect-and-receive`](01-connect-and-receive) | Connect, send the model's first command, render the reply | Helios |
| 02 | [`02-upload-image`](02-upload-image) | `uploadFile()`, then passing the `FileRef` into a command | Helios |
| 03 | [`03-pause-and-resume`](03-pause-and-resume) | `pauseTrack()` / `resumeTrack()` — nothing generated while paused | Helios |
| 04 | [`04-publish-track`](04-publish-track) | `publishTrack()` with a real `MediaStreamTrack`, watching it get edited | X2 (`xmax/x2`) |
| 05 | [`05-multi-connection`](05-multi-connection) | Two clients on one session: `connect(jwt, { sessionId })` | Helios |
| 06 | [`06-record-clip`](06-record-clip) | `requestClip()` and `downloadClipAsFile()` | Helios |

Every example shares one spine — connect, wait for `"ready"`, give the model
the minimum it needs, receive frames — and adds one new call on top. The
diff against 01 is the lesson.

"The minimum it needs" is per model and not optional: Helios stays silent
until `set_prompt` and then `start`; X2 needs a prompt too, but no `start` —
it edits the live track as soon as it has both. Each example's own comment
block says where that's written down: the model's published schema.

## Running one

Each example is its own Vite app (`@reactor-team/js-sdk` linked in from the
SDK's working tree, `file:../..`), not a shared harness:

```bash
cd sdks/js/examples/01-connect-and-receive   # or any other example
export REACTOR_API_KEY=...                   # https://www.reactor.inc/account/api-keys
npm install
npm run dev
```

Open the printed local URL. Every example runs against **production** —
`vite.config.ts` mints a short-lived JWT for `REACTOR_API_KEY` server-side
(`GET /api/token`) the same way a real app's own backend would, so the key
itself never reaches the browser.

## What's shared, and what isn't

Nothing is imported between examples — each `src/main.ts` is the whole
lesson, self-contained. The `vite.config.ts` token-minting middleware is
duplicated across all of them on purpose, same as 01: it's dev-server
plumbing, not SDK usage, and a shared helper would be one more file to read
to understand any single example.

Tracks are asked for by name — `reactor.on('trackReceived', (name, track) => ...)`
filtered on `name === 'main_video'` — the way an app that knows its model
does. `reactor.tracks()` lists what a session declared, for discovering them
instead.

## Docs

| Page | |
|---|---|
| [Using the SDK](https://docs.reactor.inc/sdk-reference/using-the-sdk) | the Python and JS guides |
| [Sessions](https://docs.reactor.inc/concepts/sessions) | lifecycle, multiple connections, adoption |
| [Tracks](https://docs.reactor.inc/concepts/tracks) | input and output tracks, publishing, pausing |
| [Commands & messages](https://docs.reactor.inc/concepts/commands-and-messages) | what you send, what the model sends back |
| [File uploads](https://docs.reactor.inc/concepts/file-uploads) | `uploadFile()` and passing a `FileRef` in a command |
| [Recordings](https://docs.reactor.inc/concepts/recordings) | clips, full-session recordings, playlists |
| [Model API reference](https://docs.reactor.inc/model-api-reference/overview) | per-model tracks, commands and messages |

A model's own reference page is the thing to check when a command is
rejected or no frame arrives — [Helios' schema](https://docs.reactor.inc/model-api-reference/helios/schema),
for instance, is where `start` requiring a prompt is written down.

Model names are `owner/name`, and every example spells them out in full:
`reactor/helios`, `xmax/x2`. A bare name resolves under `reactor/`, so it
works by luck of ownership and answers 403 for anyone else's model.
