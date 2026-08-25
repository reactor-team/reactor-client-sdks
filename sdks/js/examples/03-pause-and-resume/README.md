# 03 — Pause and Resume

Stop a track, then start it again. All of it is in
[`src/main.ts`](src/main.ts) — one file, no framework.

## Running it

```bash
export REACTOR_API_KEY=...   # https://www.reactor.inc/account/api-keys
npm install
npm run dev
```

Open the printed local URL and click **Connect**. Once frames arrive, try
**Pause** and **Resume**.

This runs against **production**, the same way as [example 01](../01-connect-and-receive) —
`REACTOR_API_KEY` never reaches the browser; `vite.config.ts` mints a
short-lived JWT for it server-side.

## What it teaches

- `reactor.pauseTrack('main_video')` / `reactor.resumeTrack('main_video')` —
  transport-level, and separate from any `pause`-like command a model might
  expose of its own. Pausing tells the runtime to stop *producing* the
  track; it's not a local mute.
- The frame-rate readout is the point: it drops to `0 fps` while paused —
  proof nothing was generated, not just nothing rendered — and comes back
  once resumed.
