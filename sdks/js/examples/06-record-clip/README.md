# 06 — Record a Clip

Capture what just happened, and download it. All of it is in
[`src/main.ts`](src/main.ts) — one file, no framework.

## Running it

```bash
export REACTOR_API_KEY=...   # https://www.reactor.inc/account/api-keys
npm install
npm run dev
```

Open the printed local URL and click **Connect**. Once frames arrive, pick
how many seconds back to grab and click **Record & download**.

This runs against **production**, the same way as [example 01](../01-connect-and-receive) —
`REACTOR_API_KEY` never reaches the browser; `vite.config.ts` mints a
short-lived JWT for it server-side, and the same token is handed to
`downloadClipAsFile()` for the clip manifest fetch.

## What it teaches

- `reactor.requestClip(seconds)` returns as soon as the platform accepts
  the request — accepted is not ready. `downloadClipAsFile()` is what
  waits, polling past `clip.predictedReadyAtMs` until the manifest is
  actually fetchable.
- Readiness is in *media* time, not wall clock: a clip's window ends at
  the recording's own "now", so the boundary chunk is always the one
  still open — waiting before asking only moves that boundary further out.
- `downloadClipAsFile()` fetches the HLS chunks the manifest references
  and remuxes them into one flat, playable MP4, triggering a normal
  browser download. `reactor.requestRecording()` is the same idea for the
  whole session.
