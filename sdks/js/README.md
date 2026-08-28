# Reactor JS SDK

[![npm version](https://img.shields.io/npm/v/@reactor-team/js-sdk)](https://www.npmjs.com/package/@reactor-team/js-sdk)
[![build](https://img.shields.io/github/actions/workflow/status/reactor-team/reactor-client-sdks/ci.yml?branch=main)](https://github.com/reactor-team/reactor-client-sdks/actions)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](https://github.com/reactor-team/reactor-client-sdks/blob/main/LICENSE)

The JavaScript/TypeScript SDK for [Reactor](https://reactor.inc), the developer platform
for real-time world models.

In a few lines of code you can connect a browser app to a Reactor session over WebRTC,
render live model video at 30–60 FPS, send typed commands to steer what generates, and
receive structured messages back.

The SDK ships a React API (`ReactorProvider`,
`ReactorView`, `useReactor`, …) for browser apps, and an imperative `Reactor` class for
everything else running in a browser context — Electron, a game engine's webview, and so on.

Full reference and guides live at **[docs.reactor.inc](https://docs.reactor.inc)**.

## Install

```bash
npm install @reactor-team/js-sdk
```

## Quickstart

```ts
import { Reactor } from "@reactor-team/js-sdk";

const reactor = new Reactor({
  modelName: "my-model",
  jwt: () => fetchToken(), // or a plain string; omit for an unauthenticated local runtime
});

reactor.on("statusChanged", (status) => {
  if (status === "ready") {
    // commands and media are live
  }
});
reactor.on("error", (error) => console.error(error.code, error.message));

await reactor.connect();
await reactor.disconnect();
```

The token can also be passed directly to `connect(jwt, options?)` instead of
the constructor.

## Authentication

`fetchToken()` above is your own function — mint the JWT server-side so your API key never
reaches the browser. The `authorization_details` block scopes the token: it can create a
bounded number of sessions for one model and act only on the sessions it created, so a
leaked token exposes nothing else on the account:

```ts
// e.g. a Next.js route handler
const result = await fetch("https://api.reactor.inc/tokens", {
  method: "POST",
  headers: {
    "Reactor-API-Key": process.env.REACTOR_API_KEY!,
    "Content-Type": "application/json",
  },
  body: JSON.stringify({
    authorization_details: [
      {
        type: "session",
        resources: { models: { match: ["my-model"] } },
        constraints: { max_sessions: 5 },
      },
    ],
  }),
});
const { jwt } = await result.json();
```

See [Authentication](https://docs.reactor.inc/authentication) for the full request shape,
including `max_session_duration_seconds` and other constraints.

## `disconnect()` and disposal

`disconnect(recoverable = false)`:

- **`disconnect()`** (the default) ends the session server-side and releases
  the connection's underlying resources in one step. The `Reactor` instance
  itself is still usable: a later `connect()`/`reconnect()` sets up a fresh
  connection automatically.
- **`disconnect(true)`** ends the session but keeps those resources warm, so
  a later `connect()`/`reconnect()` reconnects faster instead of
  reinitializing from scratch.
- **`using reactor = new Reactor(...)`** (or calling `reactor[Symbol.dispose]()`
  directly) tears the instance down for good — same resource release as a
  plain `disconnect()`, plus dropping every registered event handler. Do this
  when you're done with the object entirely, not on every disconnect.

## Status and session id

```ts
reactor.getStatus();     // ReactorStatus: "disconnected" | "connecting" | "waiting" | "ready"
reactor.getSessionId();  // string | undefined
```

## Events

`on(event, handler)` / `off(event, handler)` / `once(event, handler)`. The ones you'll reach
for most:

| Event | Payload |
| --- | --- |
| `statusChanged` | `ReactorStatus` |
| `error` | `ReactorError` |
| `message` | `ReactorMessage` from the model |
| `trackReceived` | a new media track from the model |

Full list — including `schemaReceived`, `capabilitiesReceived`, and `statsUpdate` — in
[Events](https://docs.reactor.inc/sdk-reference/events).

## Model schema and capabilities

```ts
reactor.getSchema();        // the model's command/webhook schema
reactor.getCapabilities();  // negotiated tracks and command set
```

Both arrive automatically after `connect()` — no request needed — via the getters above or the
matching `schemaReceived`/`capabilitiesReceived` events. See
[`getSchema()`](https://docs.reactor.inc/sdk-reference/reactor-class#getschema) and
[`Capabilities`](https://docs.reactor.inc/sdk-reference/types#capabilities) for the full shape.

## Recording and clips

```ts
const clip = await reactor.requestClip(10); // last 10 seconds
await reactor.downloadClipAsFile(clip, "clip.mp4");
```

`requestClip()`/`requestRecording()`/`downloadClipAsFile()` live directly on `Reactor`, and the
same three are bound on the React store. For a preview or download button instead, drop in
`<ClipPlayer>`/`<ClipDownloadButton>`:

```tsx
import { ClipPlayer, ClipDownloadButton } from "@reactor-team/js-sdk";

<ClipPlayer clip={clip} getJwt={() => fetchToken()} />
<ClipDownloadButton clip={clip} getJwt={() => fetchToken()} filename="clip.mp4" />
```

Full API, playback details, and the `useClipDownload` hook: [Recordings](https://docs.reactor.inc/concepts/recordings).

## Stats and timings

```ts
reactor.on("statsUpdate", (stats) => console.log(stats.rtt, stats.packetLossRatio));
```

`getStats()`/`getConnectionTimings()` give you the same data on demand. Field list (RTT,
bitrate, jitter, handshake timing, ...) in [Types](https://docs.reactor.inc/sdk-reference/types#connectionstats).

## Errors

One class, `ReactorError`, for both the `error` event payload and a rejected call's error —
carries `code`, `message`, and `recoverable`. It's the base of a typed hierarchy, one subclass
per failure kind (`UnauthorizedError`, `RateLimitedError`, `NetworkError`, ...) — full list in
[Types](https://docs.reactor.inc/sdk-reference/types#reactorerror). `instanceof` and matching
`error.code` are equivalent:

```ts
import { ReactorError, UnauthorizedError } from "@reactor-team/js-sdk";

try {
  await reactor.connect();
} catch (error) {
  if (error instanceof UnauthorizedError) {
    await refreshToken();
  } else if (error instanceof ReactorError && error.recoverable) {
    await reactor.reconnect();
  }
  throw error;
}
```

## React

```tsx
import { useCallback } from "react";
import { ReactorProvider, ReactorView, useReactor } from "@reactor-team/js-sdk";

function App() {
  // Stable across renders — see the note on ReactorProvider below for why
  // this matters.
  const jwt = useCallback(() => fetchToken(), []);

  return (
    <ReactorProvider modelName="my-model" jwtToken={jwt}>
      <Status />
    </ReactorProvider>
  );
}

function Status() {
  const { status, sendCommand } = useReactor((s) => ({
    status: s.status,
    sendCommand: s.sendCommand,
  }));

  return (
    <div>
      <ReactorView className="w-full aspect-video" videoObjectFit="cover" />
      {status}
      <button onClick={() => sendCommand("set_image", { url: "..." })}>Send</button>
    </div>
  );
}
```

`react` is a peer dependency — install it yourself, matching your app's own version.

`ReactorProvider`'s props are live: changing `jwtToken`, `modelName`, or `connectOptions` tears
down the current connection and builds a fresh one. Pass a stable `jwtToken` (`useCallback`, or
hoist it outside the component) so an unrelated re-render doesn't reconnect you.

`useReactor(selector)` exposes the same surface as `Reactor` itself — status, errors, messages,
and every method as a bound action — so most components never need to touch the instance
directly. When one does, `useReactor((s) => s.internal.reactor)` gets it. Full field list:
[React hooks](https://docs.reactor.inc/sdk-reference/react-hooks).

## Typed model SDKs

For models with a published typed SDK, prefer [`@reactor-models/<name>`](https://www.npmjs.com/org/reactor-models).
It re-exports everything here and adds typed commands, messages, and hooks for one
specific model. Use this base SDK when the model doesn't have a typed package yet, or when
you want to stay model-agnostic. See [Typed model SDKs](https://docs.reactor.inc/sdk-reference/typed-model-sdk).

## API surface

| Surface | Where it lives |
| --- | --- |
| `Reactor` | [Reactor class](https://docs.reactor.inc/sdk-reference/reactor-class) |
| `<ReactorProvider>`, `<ReactorView>`, `<WebcamStream>` | [React components](https://docs.reactor.inc/sdk-reference/react-components) |
| `useReactor`, `useReactorMessage`, `useReactorInternalMessage`, `useStats` | [React hooks](https://docs.reactor.inc/sdk-reference/react-hooks) |
| `<ClipPlayer>`, `<ClipDownloadButton>`, `useClipDownload` | [Recordings](https://docs.reactor.inc/concepts/recordings) |
| `ReactorError` and its subclasses, `Clip`, `Capabilities`, `ConnectionStats`, ... | [Types](https://docs.reactor.inc/sdk-reference/types) |

## Development

```bash
mise run install:js   # npm install
mise run lint:js      # eslint + tsc --noEmit
mise run build:js     # tsup -> dist/, then copies reactor-wasm's pkg/ into dist/wasm
mise run test:js      # vitest — pure-JS unit tests against a mocked ReactorClient, no wasm build needed
```

`build:js` fails fast with a clear message if `crates/reactor-wasm/pkg`
doesn't exist yet — run `mise run build:wasm` first.

See the repo-wide [`CONTRIBUTING.md`](../../CONTRIBUTING.md) for the rest
(DCO, commit conventions, opening a PR).

## Documentation

The [full documentation](https://docs.reactor.inc/sdk-reference/using-the-sdk)
covers platform concepts and the other language SDKs. See
[`CHANGELOG.md`](./CHANGELOG.md) for what changed release to release,
including 3.0.0's breaking changes.

## License

Apache-2.0 — see [`LICENSE`](../../LICENSE).
