# 07 — Command Replies

`sendCommand()` resolves with the model's correlated reply — a `{ type,
data }` message, not just an ack. Helios's `save_snapshot`/`list_snapshots`/
`rewind` are just this example's vehicle for it: the reply-reading pattern
applies to any command, on any model. All of it is in
[`src/main.ts`](src/main.ts) — one file, no framework.

## Running it

```bash
# from the repo root, once:
mise run build:wasm
mise run build:js            # tsup -> sdks/js/dist/

# from this directory:
export REACTOR_API_KEY=...   # https://www.reactor.inc/account/api-keys
npm install
npm run dev
```

Open the printed local URL and click **Connect**. Once frames arrive, click
**Save** a couple of times (an optional label goes with each one) — each
Save re-lists the snapshots and renders them below, and **Rewind** on any
row jumps back to it.

This runs against **production**, the same way as [example 01](../01-connect-and-receive) —
`REACTOR_API_KEY` never reaches the browser; `vite.config.ts` mints a
short-lived JWT for it server-side.

## What it teaches

- `sendCommand()` resolves with the model's correlated reply — a
  `{ type, data }` message, not just an ack. Every earlier example either
  fire-and-forgets the call or reads only `type`; this one reads `data`:
  `save_snapshot` replies with the assigned index, `list_snapshots` with the
  full array, `rewind` with where it landed. **Save** triggers `list_snapshots`
  itself right after saving, so the row list is always current without a
  separate button for it.
- The `message` event carries both. A reply is *addressed* to the connection
  that sent the command, and on that connection it lands on the `message`
  event too — so `snapshot_saved` shows up in the log below as well as on the
  await. What only ever arrives on the event is the model's **unprompted**
  traffic: Helios fires `state` and `chunk_complete` continuously (a snapshot
  after every command and every chunk), which carry nothing this example
  cares about and are filtered out of the log so they don't drown out the
  replies. Prefer the await regardless: it is tied to one call, so it says
  *which* command was answered — and it is the only surface a second client
  on the same session would not silently miss.
- `schemaReceived` fires automatically on every `"ready"` transition; this
  logs its `paths` on connect so the commands below are checked against
  what the connected model actually publishes, not assumed from a doc page.
  A model that doesn't have `save_snapshot`/`list_snapshots`/`rewind` will
  say so there before **Save** ever gets clicked.
