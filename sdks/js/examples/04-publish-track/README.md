# 04 — Publish a Track

Send your own media into a model and watch it come back edited. All of it
is in [`src/main.ts`](src/main.ts) — one file, no framework.

## Running it

```bash
export REACTOR_API_KEY=...   # https://www.reactor.inc/account/api-keys
npm install
npm run dev
```

Open the printed local URL and click **Connect & Publish** — the browser
will ask for webcam access.

This runs against **production**, the same way as [example 01](../01-connect-and-receive) —
`REACTOR_API_KEY` never reaches the browser; `vite.config.ts` mints a
short-lived JWT for it server-side.

## What it teaches

- `reactor.publishTrack('source', track)` — a real `MediaStreamTrack`
  (here, the webcam) is what an input track takes; there's no separate
  "push a frame" API in the browser SDK the way a headless client needs
  one.
- Publish before the prompt: a prompt with no sender behind the slot buys
  nothing, and pushing media before the track is published raises.
- X2 (`xmax/x2`), unlike Helios, has no `start` — it begins editing as soon
  as it has both a prompt and frames.

Uses `xmax/x2`, not `reactor/helios` — it's the model with an input track to
publish into.
