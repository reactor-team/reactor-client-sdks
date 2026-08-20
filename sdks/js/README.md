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

## Errors

There is **one class**, not two: the `error` event payload and a rejected
call's error are the same shape, and the same instance type —
`ReactorError`, carrying `code`, `message`, `recoverable`, `status?`,
`operation?`, `retry_after_ms?` and `timestamp_ms`, plus the compatibility
fields below.

`ReactorError` is the base of a typed hierarchy keyed by `reactor-core`'s own
precise, per-failure-kind classification (shared by every SDK on it) —
`NetworkError`, `UnauthorizedError`, `NotFoundError`, `ConflictError`,
`RateLimitedError`, `BadRequestError`, `ServerError`, `VersionMismatchError`,
`DecodeError`, `InvalidStateError`, `SessionTerminalError`,
`MessageTooLargeError`, `TransportError`, `DisconnectedError`,
`RequestTimeoutError`, `AbortedError`. **Prefer catching a specific one over
matching `error.code`** — `instanceof` always reflects the real, precise
reason; `code` itself does not always (see below):

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

Codes are open-ended — the platform can send its own for a command or
recording it rejects — so an unrecognized one falls back to the base
`ReactorError` rather than throwing.

`getLastError()` returns the most recent `ReactorError`, from either an
`error` event or a rejected call.

Every `ReactorError` also carries three compatibility fields, kept alongside
the canonical ones above: `timestamp` (same value as `timestamp_ms`),
`retryAfter` (same value as `retry_after_ms`), and `component: "api" | "gpu"`
(best-effort — there's no longer a reliable signal for which tier produced a
given failure, so it's derived from the failure itself: any `status` present
means an HTTP response came back from the coordinator, so `"api"`; otherwise
the underlying canonical code decides — the ones that only arise once a
session is already talking to the model over the data channel/transport
(`TRANSPORT_ERROR`, `DISCONNECTED`, `MESSAGE_TOO_LARGE`, `REQUEST_TIMEOUT`,
`SESSION_TERMINAL`, `DECODE_FAILED`) are `"gpu"`, everything else `"api"`).

**`code` itself needs a closer look**, because it isn't always
`reactor-core`'s canonical value. For a failure this package can attribute
to one call (`operation` is `"connect"`, `"reconnect"`, `"publishTrack"`,
`"unpublishTrack"`, or `"sendCommand"`), `code` is the single fixed string
this package already reported for that call before it adopted
`reactor-core`'s richer, shared vocabulary — e.g. every `connect()` failure
reports `code: "CONNECTION_FAILED"` regardless of the underlying reason,
exactly as before, so a caller already matching one of those fixed strings
keeps matching unchanged. An unprompted transport drop (no `operation` at
all) reports `"GPU_CONNECTION_ERROR"`, likewise unconditionally. For every
other call (`pauseTrack`, `resumeTrack`, `uploadFile`, `requestSchema`,
`setJwt`, `disconnect`) — none of which ever had a fixed code of their own —
`code` is `reactor-core`'s canonical value directly, since there's nothing
prior to preserve. Either way, the typed subclass you actually get is always
based on the real, precise reason — only `code`'s *string* is sometimes
collapsed for compatibility, which is exactly why `instanceof` is the
recommended way to branch on a failure, not `code`.

`sendCommand()` is the one exception to "rejects like everything else": it
never throws, even when the session isn't `"ready"` or the send itself
fails — that failure is reported through `getLastError()`/the `error` event
instead, and the call resolves `undefined`. This is a JS-only compatibility
shim for callers that fire-and-forget `sendCommand(...)` without
`await`/`catch` — it is not applied to `publishTrack()`, `uploadFile()`, or
anything else, which throw normally.

## Local demo

```bash
mise run run:js-sdk-local   # or: make run-js-sdk-local
```

One command, run from anywhere in the repo — it builds `reactor-wasm`, builds
this package (which copies the wasm build into `dist/wasm`), then installs
and opens a minimal, framework-free Vite page — no React — that connects and
logs status changes to both the console and the page. The demo depends on
this package via `file:..`, so it always picks up whatever's freshly built
into this package's own `dist/`.

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
