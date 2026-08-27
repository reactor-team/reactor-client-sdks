# Reactor JS SDK examples

Minimal examples, one per capability — the same set (minus frame metadata,
still to come) as the Python and C++ SDKs, plus one JS-only addition (07)
on the request/reply side of messaging.

## The set

| # | Example | Teaches | Model |
|---|---|---|---|
| 01 | [`01-connect-and-receive`](01-connect-and-receive) | Connect, send the model's first command, render the reply | Helios |
| 02 | [`02-upload-image`](02-upload-image) | `uploadFile()`, then passing the `FileRef` into a command | Helios |
| 03 | [`03-pause-and-resume`](03-pause-and-resume) | `pauseTrack()` / `resumeTrack()` — nothing generated while paused | Helios |
| 04 | [`04-publish-track`](04-publish-track) | `publishTrack()` with a real `MediaStreamTrack`, watching it get edited | X2 (`xmax/x2`) |
| 05 | [`05-multi-connection`](05-multi-connection) | Two clients on one session: `connect(jwt, { sessionId })` | Helios |
| 06 | [`06-record-clip`](06-record-clip) | `requestClip()` and `downloadClipAsFile()` | Helios |
| 07 | [`07-command-replies`](07-command-replies) | `sendCommand()`'s resolved reply, read and used — not fire-and-forgotten | Helios |

Every example shares one spine — connect, wait for `"ready"`, give the model
the minimum it needs, receive frames — and adds one new call on top. The
diff against 01 is the lesson.

"The minimum it needs" is per model and not optional: Helios stays silent
until `set_prompt` and then `start`; X2 needs a prompt too, but no `start` —
it edits the live track as soon as it has both. Each example's own comment
block says where that's written down: the model's published schema.

## Running one

Each example is its own Vite app (`@reactor-team/js-sdk` linked in from the
SDK's working tree, `file:../..`), not a shared harness. The SDK itself has
to be built first — `file:` links to the package's `dist/`, which isn't
checked in and isn't produced by a plain `npm install`:

```bash
mise run build:wasm            # from the repo root, once
mise run build:js              # tsup -> sdks/js/dist/

cd sdks/js/examples/01-connect-and-receive   # or any other example
export REACTOR_API_KEY=...                   # https://www.reactor.inc/account/api-keys
npm install
npm run dev
```

Open the printed local URL. Every example runs against **production** —
`vite.config.ts` mints a short-lived JWT for `REACTOR_API_KEY` server-side
(`GET /api/token`) the same way a real app's own backend would, so the key
itself never reaches the browser.

## Auth

The `/api/token` route is deliberate, not incidental complexity. It's the
same "Server-side proxy" shape [Authentication](https://docs.reactor.inc/authentication)
documents for real apps, and what the Python and C++ examples get for free
by taking `api_key` directly (server/native-side, never a browser). The key
authorizes the whole account — create/delete models, billing, key
management — while the JWT it mints is scoped to one model and expires in
an hour; putting the key behind a route instead of a plain input on the page
is what keeps that account-wide credential off the browser entirely.

Each `main.ts` calls `fetchToken()` once per `connect()` and hands the
result to it as a plain string, not as `fetchToken` itself. Passed as a
resolver, the SDK would call it again on later hops (session create, the
poll-until-ready GET, ...) and mint a *different* token each time — reading
a session back requires the *same* token that created it, so a second,
independently minted token with identical scope 403s. A plain string
sidesteps that: it's the same value on every call by construction, no cache
needed. [`05-multi-connection`](05-multi-connection) is the one place this
spans two clients instead of just two hops — see its own comment.

## What's shared, and what isn't

`log()` and `fetchToken()` live in `shared/` — pure boilerplate, byte-identical
across all seven, and reading them teaches nothing about the SDK. Everything
else stays local: each `src/main.ts` is still the whole lesson, and the
`vite.config.ts` token-minting middleware is duplicated across all of them on
purpose — it's dev-server plumbing keyed to that example's own model scope
(`authorization_details.resources.models.match`), not SDK usage, and folding
it into a shared helper would hide a real per-example difference behind one
more file to read.

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
