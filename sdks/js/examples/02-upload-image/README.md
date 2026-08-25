# 02 — Upload an Image

Upload a file, then pass the reference into a command. All of it is in
[`src/main.ts`](src/main.ts) — one file, no framework.

## Running it

```bash
export REACTOR_API_KEY=...   # https://www.reactor.inc/account/api-keys
npm install
npm run dev
```

Open the printed local URL, choose an image, then click **Connect & Send**.

This runs against **production**, the same way as [example 01](../01-connect-and-receive) —
`REACTOR_API_KEY` never reaches the browser; `vite.config.ts` mints a
short-lived JWT for it server-side.

## What it teaches

- `reactor.uploadFile(file)` — the bytes cross the wire once, resolving with
  a `FileRef` (`uploadId`, `name`, `mimeType`, `size`).
- Passing that `FileRef` back as a top-level value in a command's `data`:
  `sendCommand('set_conditioning', { prompt, image: uploaded })`. Helios
  takes the prompt and the image together, so `start` never sees a session
  with only one of the two set.
- A refused upload (e.g. by moderation) arrives as a `message` event, not
  as a rejected `uploadFile()` call.
