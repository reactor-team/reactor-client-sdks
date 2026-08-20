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

Construction never touches WebAssembly — `reactor-wasm` is fetched and
instantiated lazily on the first `connect()`/`reconnect()` call, and cached
after that, so building a `Reactor` you never connect costs nothing.

## `disconnect()` and disposal

`disconnect(recoverable = false)` matches v2's signature:

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

Matches v2's names. `reactor-wasm`'s own binding uses the terser `status()` /
`sessionId()` internally — not exposed here yet, kept simple until there's a
real reason to add them alongside `getStatus()`/`getSessionId()`.

## Events

`on(event, handler)` / `off(event, handler)` / `once(event, handler)`, over:

| Event | Payload |
| --- | --- |
| `statusChanged` | `ReactorStatus` |
| `sessionIdChanged` | `string \| undefined` |
| `error` | `ReactorError` (`code`, `message`, `recoverable`, `status?`, `operation?`, `retry_after_ms?`, `timestamp_ms`) |

A rejected call throws the same `ReactorError` shape as an `Error` with
`name === "ReactorError"` — codes are open-ended (the platform can send its
own), so match on the ones you can act on rather than trying to enumerate
them all:

```ts
try {
  await reactor.connect();
} catch (error) {
  if (error instanceof Error && error.name === "ReactorError") {
    // error as unknown as import("@reactor-team/js-sdk").ReactorError
  }
  throw error;
}
```

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
