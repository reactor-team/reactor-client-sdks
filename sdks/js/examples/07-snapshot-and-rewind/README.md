# 07 — Snapshot and Rewind

Save a snapshot, list them, rewind to one — and look at what
`sendCommand()` actually hands back each time. All of it is in
[`src/main.ts`](src/main.ts) — one file, no framework.

## Running it

```bash
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
- The `message` event is a different thing — it's the model's *unprompted*
  traffic, not the reply to anything this example sent. Helios fires `state`
  and `chunk_complete` continuously (a snapshot after every command and every
  chunk), which carry nothing this example cares about — they're filtered out
  of the log entirely so they don't drown out the replies above, which are
  the actual point.
- `schemaReceived` fires automatically on every `"ready"` transition; this
  logs its `paths` on connect so the commands below are checked against
  what the connected model actually publishes, not assumed from a doc page.
  A model that doesn't have `save_snapshot`/`list_snapshots`/`rewind` will
  say so there before **Save** ever gets clicked.
