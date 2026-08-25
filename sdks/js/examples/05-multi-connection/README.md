# 05 — Multi-connection

Two clients, one session. All of it is in [`src/main.ts`](src/main.ts) — one
file, no framework.

## Running it

```bash
export REACTOR_API_KEY=...   # https://www.reactor.inc/account/api-keys
npm install
npm run dev
```

Open the printed local URL and click **Connect both**.

This runs against **production**, the same way as [example 01](../01-connect-and-receive) —
`REACTOR_API_KEY` never reaches the browser; `vite.config.ts` mints a
short-lived JWT for it server-side. Both `Reactor` instances on the page
resolve their JWT through the same endpoint — that's fine, they don't need
distinct tokens.

## What it teaches

- Session adoption: `joiner.connect(undefined, { sessionId: creator.getSessionId() })`.
  The id is the whole handoff — no second session, no coordination beyond
  it.
- Who owns the session: only the creator's `disconnect()` ends it
  server-side, which is what makes joining safe from a tab that may close
  at any moment.
- Teardown order — the joiner disconnects first, then the creator. A
  creator that disappears without disconnecting leaves the session
  orphaned.
