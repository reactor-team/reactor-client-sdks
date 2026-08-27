# 01 — Connect and Receive

Connect, send the model's first command, and render the frames it sends
back. The spine every other `sdks/js` example builds on. All of it is in
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

Open the printed local URL and click **Connect**.

This runs against **production**. `REACTOR_API_KEY` never reaches the
browser: `vite.config.ts` mints a short-lived JWT for it server-side
(`GET /api/token`) the same way a real app's backend would, and hands only
that token to `new Reactor(...)`.

## What it teaches

- The connection state machine: `disconnected` → `connecting` → `waiting` →
  `ready`, printed as it happens.
- Helios' own minimum to start generating: `set_prompt` before `start` — its
  schema is the first place to look when nothing arrives:
  https://docs.reactor.inc/model-api-reference/helios/schema
- Rendering a received track by handing its `MediaStreamTrack` to a
  `<video>` element's `srcObject`.
