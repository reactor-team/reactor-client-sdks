# Reactor JS SDK

[![npm version](https://img.shields.io/npm/v/@reactor-team/js-sdk)](https://www.npmjs.com/package/@reactor-team/js-sdk)
[![build](https://img.shields.io/github/actions/workflow/status/reactor-team/reactor-client-sdks/ci.yml?branch=main)](https://github.com/reactor-team/reactor-client-sdks/actions)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](https://github.com/reactor-team/reactor-client-sdks/blob/main/LICENSE)

Connect a browser app to a live [Reactor](https://reactor.inc) model.

## Install

```bash
npm install @reactor-team/js-sdk
```

`dist/` ships plain CJS and ESM builds plus `.d.ts` types — a bundler-free,
non-TypeScript app can `require()` or `import` this package directly, no
build step of its own required.

## Quickstart

```ts
import { Reactor } from "@reactor-team/js-sdk";

const reactor = new Reactor({
  modelName: "my-model",
  jwt: () => fetchToken(), // or a plain string; omit for an unauthenticated local runtime
});

reactor.on("statusChanged", (status) => console.log("status:", status));
reactor.on("error", (error) => console.error(error.code, error.message));

await reactor.connect();
// ... reactor.getStatus() === "ready" once the session is up ...
await reactor.disconnect(); // ends the session AND frees the wasm client
```

The token can also be passed directly to `connect(jwt, options?)` instead of
the constructor.

Construction never touches WebAssembly — `reactor-wasm` is fetched and
instantiated lazily on the first `connect()`/`reconnect()` call, and cached
after that, so building a `Reactor` you never connect costs nothing.

## `disconnect()` and disposal

`disconnect(recoverable = false)`:

- **`disconnect()`** (the default) ends the session server-side and frees the
  underlying wasm resource graph — the pump, dispatcher and heartbeat tasks —
  in one step. The `Reactor` instance itself is still usable: a later
  `connect()`/`reconnect()` lazily builds a fresh wasm client.
- **`disconnect(true)`** ends the session but keeps the wasm client alive, so
  a later `connect()`/`reconnect()` doesn't have to reload wasm and
  reconstruct it from scratch.
- **`using reactor = new Reactor(...)`** (or calling `reactor[Symbol.dispose]()`
  directly) tears the instance down for good — same resource release as a
  plain `disconnect()`, plus dropping every registered event handler. Do this
  when you're done with the object entirely, not on every disconnect.

## Status and session id

```ts
reactor.getStatus();     // ReactorStatus: "disconnected" | "connecting" | "waiting" | "ready"
reactor.getSessionId();  // string | undefined
```

`reactor-wasm`'s own binding uses the terser `status()`/`sessionId()`
internally — not exposed here yet, kept simple until there's a real reason
to add them alongside `getStatus()`/`getSessionId()`.

## Events

`on(event, handler)` / `off(event, handler)` / `once(event, handler)`, over:

| Event | Payload |
| --- | --- |
| `statusChanged` | `ReactorStatus` |
| `sessionIdChanged` | `string \| undefined` |
| `error` | `ReactorError` |
| `message` | `ReactorMessage` — application-scope payload from the model |
| `runtimeMessage` | `ReactorMessage` — platform-scope payload (moderation, clip/recording lifecycle, ...) |
| `schemaReceived` | `ModelSchema` — see `getSchema()` below |
| `capabilitiesReceived` | `Capabilities` — see `getCapabilities()` below |
| `trackReceived` | `(name, track: MediaStreamTrack, stream: MediaStream, mid: string \| undefined)` |
| `statsUpdate` | `ConnectionStats` |

## Model schema and capabilities

```ts
reactor.getSchema();        // ModelSchema | undefined — the model's OpenAPI command/webhook doc
reactor.getCapabilities();  // Capabilities | undefined — negotiated tracks, command set if exposed
reactor.on("schemaReceived", (schema) => ...);
reactor.on("capabilitiesReceived", (capabilities) => ...);
```

Both are pushed automatically once available after `connect()` — no explicit request needed —
and both are `undefined` until their first event fires. Neither is mirrored into the React
store's reactive state; reach them off the `Reactor` instance the same way (`useReactor((s) =>
s.internal.reactor)` — see [React](#react) below).

`getSessionInfo()` also returns a `capabilities` field, but it's the **raw wire shape**
(snake_case), not this translated one — prefer `getCapabilities()`/`capabilitiesReceived` unless
you specifically need the untranslated session resource.

## Recording and clips

```ts
const clip = await reactor.requestClip(10); // last 10 seconds
// or: const clip = await reactor.requestRecording(); // the whole session so far

// downloadClipAsFile() doesn't inherit the Reactor instance's JWT — pass it
// explicitly (omit `jwt` entirely against a local runtime, which is auth-free).
const blob = await reactor.downloadClipAsFile(clip, "clip.mp4", { jwt: await fetchToken() });
```

`requestClip()`/`requestRecording()`/`downloadClipAsFile()` are directly on `Reactor` — there's
no separate recording client to construct. All three are also bound on the React store
(`useReactor((s) => s.requestClip)`, etc.) for the common case; `useClipDownload` below wraps
`downloadClipAsFile` in a progress/error state machine for a custom UI.
`downloadClipAsFile(clip, filename?, options?)` polls the clip's manifest
until ready, remuxes the fragmented chunks into a flat, faststart MP4, and (unless
`filename: null` is passed) triggers the download; pass `options.onProgress` for progress UI.

For a React preview instead of a download, `ClipPlayer`/`ClipDownloadButton`/`useClipDownload`
cover playback and download UI directly. Neither requires a `ReactorProvider`, but each needs a
JWT source outside local-dev mode — either an explicit `getJwt`, as below, or mount them under a
`ReactorProvider` and omit `getJwt` to inherit its resolver:

```tsx
import { ClipPlayer, ClipDownloadButton } from "@reactor-team/js-sdk";

<ClipPlayer clip={clip} getJwt={() => fetchToken()} />
<ClipDownloadButton clip={clip} getJwt={() => fetchToken()} filename="clip.mp4" />
```

`ClipPlayer` streams the clip with `hls.js` wherever Media Source Extensions exist (every
current browser, including iOS Safari 17.1+) and assembles a flat MP4 to play from memory on
the one iOS range that has none. Neither component requires a `ReactorProvider` — both work
directly off a `Clip` value, including clips loaded from fixtures or logs.

## Stats and timings

```ts
reactor.getStats();             // ConnectionStats | undefined
reactor.getConnectionTimings();  // ConnectionTimings | undefined
reactor.on("statsUpdate", (stats) => console.log(stats.rtt, stats.packetLossRatio));
```

While the session is `"ready"`, `getPeerConnection().getStats()` is polled
every two seconds and reduced into a `ConnectionStats` — RTT, ICE candidate
type, incoming/outgoing bitrate (both estimated and real-time), video FPS,
packet loss ratio, and jitter — emitted as `statsUpdate` and readable
directly off `getStats()`. Both are `undefined` before the first sample, and
polling stops (clearing `getStats()`) as soon as the session leaves `"ready"`
for any reason, not just an explicit `disconnect()`.

`getConnectionTimings()` (also folded into every `ConnectionStats.connectionTimings`)
is a millisecond breakdown of the most recent `connect()`/`reconnect()`
handshake: `sessionCreationMs`, `transportConnectingMs`, `totalMs`.

## Errors

One class, `ReactorError`, for both the `error` event payload and a rejected
call's error. It carries `code`, `message`, `recoverable`, `status?`,
`operation?`, `retry_after_ms?`, `timestamp_ms`, and the compatibility
aliases `timestamp`/`retryAfter`.

It's the base of a typed hierarchy keyed by `code` — `reactor-core`'s own
per-failure-kind classification: `NetworkError`, `UnauthorizedError`,
`NotFoundError`, `ConflictError`, `RateLimitedError`, `BadRequestError`,
`ServerError`, `VersionMismatchError`, `DecodeError`, `InvalidStateError`,
`SessionTerminalError`, `MessageTooLargeError`, `TransportError`,
`DisconnectedError`, `RequestTimeoutError`, `AbortedError`. An unrecognized
code falls back to the base class. `instanceof` and matching `error.code`
are equivalent — pick whichever reads better at the call site:

```ts
import { Reactor, ReactorError, UnauthorizedError } from "@reactor-team/js-sdk";

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

`getLastError()` returns the most recent `ReactorError`, from either an
`error` event or a rejected call.

`sendCommand(command, data?, scope?)` awaits the model's correlated reply and
resolves with it (`ReactorMessage | undefined`); it never throws — a failure
surfaces through `getLastError()`/the `error` event instead, and the call
resolves `undefined`. Every other call that can fail (`connect`,
`publishTrack`, `uploadFile`, ...) throws normally.

## React

```tsx
import { useCallback } from "react";
import { ReactorProvider, useReactor } from "@reactor-team/js-sdk";

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
      {status}
      <button onClick={() => sendCommand("set_image", { url: "..." })}>Send</button>
    </div>
  );
}
```

`react` is a peer dependency — install it yourself, matching your app's own
version. `ReactorView` (renders one or two named tracks into a single
`<video>`/`<audio>` element) and `WebcamStream` (captures and publishes the
local camera/mic) cover the common send/receive media UI without hand-rolling
`getTrackByName()`/`publishTrack()` yourself.

`apiUrl`/`modelName`/`local`/`modelTracks`/`jwtToken`/`connectOptions` are live
(including `connectOptions.autoConnect`): changing any of them tears down the
current `Reactor` and builds a fresh one (there's no way to reconnect an
existing instance with a different model or endpoint). Pass a stable
`jwtToken` and stable `modelTracks`/`connectOptions` references
(`useCallback`/`useMemo`, or hoist them outside the component) if you don't
want an unrelated parent re-render to rebuild the connection.

`useReactor(selector)` also carries `sessionId`, `lastError`, `lastMessage`,
and action bindings for `connect`/`disconnect`/`reconnect`/`sendCommand`/
`publish`/`unpublish`/`pauseTrack`/`resumeTrack`/`uploadFile`/`requestClip`/
`requestRecording`/`downloadClipAsFile`. For anything else — tracks, schema,
capabilities, stats, the raw event emitter — `useReactor((s) =>
s.internal.reactor)` gets you the underlying `Reactor` instance directly.

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
including 3.0.0's breaking changes from the legacy 2.x SDK.

## License

Apache-2.0 — see [`LICENSE`](../../LICENSE).
