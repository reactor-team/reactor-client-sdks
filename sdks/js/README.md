# Reactor JS SDK

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
| `statsUpdate` | `ConnectionStats` |

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

`sendCommand()` never throws — a failure surfaces through
`getLastError()`/the `error` event instead, and the call resolves
`undefined`. Every other call that can fail (`connect`, `publishTrack`,
`uploadFile`, ...) throws normally.

## React

```tsx
import { ReactorProvider, useReactor } from "@reactor-team/js-sdk";

function App() {
  return (
    <ReactorProvider modelName="my-model" jwt={() => fetchToken()}>
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
version.

`useReactor(selector)` also carries `sessionId`, `lastError`, `lastMessage`,
and action bindings for `connect`/`disconnect`/`reconnect`/`publish`/
`unpublish`/`pauseTrack`/`resumeTrack`. For anything else — tracks, stats,
the raw event emitter — `useReactor((s) => s.internal.reactor)` gets you the
underlying `Reactor` instance directly.

## Development

```bash
mise run install:js   # npm install
mise run lint:js      # eslint + tsc --noEmit
mise run build:js     # tsup -> dist/, then copies reactor-wasm's pkg/ into dist/wasm
```

`build:js` fails fast with a clear message if `crates/reactor-wasm/pkg`
doesn't exist yet — run `mise run build:wasm` first.

See the repo-wide [`CONTRIBUTING.md`](../../CONTRIBUTING.md) for the rest
(DCO, commit conventions, opening a PR).

## Documentation

The [full documentation](https://docs.reactor.inc/sdk-reference/using-the-sdk)
covers platform concepts and the other language SDKs.

## License

Apache-2.0 — see [`LICENSE`](../../LICENSE).
