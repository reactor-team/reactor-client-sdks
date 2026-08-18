# reactor-wasm

The browser binding for `reactor-core`. Implements the three host traits with
browser APIs and exposes one `wasm-bindgen` class, `ReactorClient`, for a
TypeScript SDK to wrap.

Native SDKs reach the core through `reactor-ffi`'s C ABI. A browser has no FFI,
and its media engine is `RTCPeerConnection` rather than libwebrtc, so the web
gets its own binding — and the same deal every other SDK gets: session
lifecycle, signaling, correlation, reconnection and error semantics live in the
core, once, and the binding stays thin.

```text
                       JavaScript / TypeScript
              ┌───────────────────────────────────────┐
              │  ReactorClient  (#[wasm_bindgen])     │
              └──────┬────────────────────────────────┘
                     │ owns
     ┌───────────────┼─────────────────┬───────────────────┐
     │               │                 │                   │
 Arc<Reactor>  WasmPeerTransport  WasmAuthProvider   WasmHttpClient
 (reactor-core)   RTCPeerConnection   JS token source      fetch
     │               │
     │  PeerEvent    │  unbounded channel
 pump task ◄─────────┘
     │
 ReactorEvent stream ──► registered JS listeners
```

## Building

```bash
mise run build:wasm     # → crates/reactor-wasm/pkg (ES module + .d.ts + .wasm)
mise run clippy:wasm    # lint the real code (see "Two targets" below)
```

`pkg/` is generated and not committed. The JS SDK depends on it.

## The interface

`pkg/reactor_wasm.d.ts` is the contract, generated from this crate. Its object
shapes are declared in [`src/types.rs`](src/types.rs), so the boundary is typed
rather than `any`:

| Area | Calls |
| --- | --- |
| Lifecycle | `connect(options?)`, `disconnect()`, `reconnect()` |
| Configuration | `setJwt(jwt)`, `setSdpTransform(fn)` |
| Messaging | `sendCommand(command, data?, uploads?)`, `requestSchema()` |
| Tracks | `publishTrack(name, track)`, `unpublishTrack(name)`, `pauseTrack(name)`, `resumeTrack(name)`, `tracks()`, `trackMapping()`, `pausedTracks()` |
| Recording | `requestClip(seconds)`, `requestRecording()` |
| Uploads | `uploadFile(file, name?)` |
| Introspection | `status()`, `sessionId()`, `sessionInfo()`, `capabilities()`, `lastError()` |
| Browser handles | `getPeerConnection()`, `getTrackByMid(mid)`, `getStreamByMid(mid)`, `getTrackByName(name)`, `getStreamByName(name)` |
| Events | `onStatusChanged`, `onSessionIdChanged`, `onMessage`, `onRuntimeMessage`, `onTrackReceived`, `onError`, `onCapabilitiesReceived` |

Payloads that come out of the core keep their wire names (`protocol_version`,
`retry_after_ms`, `playlist_url`); the options this crate defines itself are
camelCase, because nothing on the wire constrains them.

```js
import init, { ReactorClient } from "./pkg/reactor_wasm.js";

await init();
const client = new ReactorClient({ modelName: "my-model" }, () => getToken());

client.onStatusChanged((status) => console.log(status));
client.onTrackReceived((name, mid) => {
  if (name === "output") video.srcObject = client.getStreamByMid(mid);
});

await client.connect();
await client.sendCommand("set_prompt", { prompt: "a cat" });
```

## Mapping the existing JS SDK onto it

The next `@reactor-team/js-sdk` has to be a drop-in for 2.x, so every v2
affordance is reachable from here:

| `@reactor-team/js-sdk` 2.x | reactor-wasm |
| --- | --- |
| `new Reactor({apiUrl, modelName, local})` | `new ReactorClient(options, jwt?)` |
| `connect(jwt, {sessionId, connectionId, autoResumeTracks, maxAttempts})` | `setJwt(jwt)` then `connect(options)` |
| `disconnect(recoverable)` | `disconnect()` (recoverable teardown is `reconnect()`) |
| `sendCommand(cmd, data)` with `FileRef` values | `sendCommand(cmd, data, uploads)` — the SDK splits the two |
| `uploadFile(File \| Blob, {name})` | `uploadFile(file, name?)` |
| `publishTrack` / `unpublishTrack` / `pauseTrack` / `resumeTrack` | same |
| `requestClip` / `requestRecording` | same |
| `getStatus` / `getSessionId` / `getLastError` | `status()` / `sessionId()` / `lastError()` |
| `getSessionInfo()` | `sessionInfo()` |
| `getSchema()` + `schemaReceived` | `requestSchema()`, which the SDK calls on ready |
| `trackReceived(name, track, stream)` | `onTrackReceived(name, mid)` + `getTrackByMid` / `getStreamByMid` |
| `getStats()` + `statsUpdate` | computed in TS from `getPeerConnection()` |
| `sessionExpirationChanged` | emitted by the SDK; v2 only ever fired it with `undefined` on teardown |

`scope: "runtime"` commands have no equivalent and need none: what used to be a
runtime-scoped data-channel command is now a typed control-channel request, so
clip and recording requests are the methods above.

## Design notes

**Two targets.** Off a wasm target this crate compiles to nothing — every module
is `#[cfg(target_family = "wasm")]` and the browser dependencies are declared
per-target. So `cargo check --workspace` on Linux or macOS stays fast and green,
and `clippy:wasm` is what actually lints the code. CI runs both.

**No `Send`.** `reactor-core` drops its `Send + Sync` bounds under
`target_family = "wasm"`. Browser handles belong to the agent that made them and
are not `Send`; wasm is single-threaded, so the bound protects nothing there.
Native builds are untouched.

**Binary channels.** The wire is `reactor_wire.v1` protobuf, so both data
channels are opened with `binaryType = "arraybuffer"` — otherwise frames arrive
as `Blob`s and would need an async read before the core could decode them.

**SDP transform.** `setSdpTransform` hands the local offer to JS before it is set
and sent. Browsers need normalizations the native stacks do not (dynamic payload
types inside [96,127], no telephone-event, Chrome-style attribute ordering);
that is data munging rather than session logic, so it stays in the SDK, which
already implements and tests it, instead of being ported into Rust.

**Errors.** A rejected call throws an `Error` with `name === "ReactorError"`
carrying the core's `code`, `recoverable`, `status`, `operation` and
`retry_after_ms` — the same fields, under the same names, as the `onError`
event. Codes are open-ended: the platform can send its own, so unknown values
must be tolerated rather than treated as a parse failure.

**Token sources.** `jwt` takes a string or a `() => string | Promise<string>`
resolver called before every authenticated request, which is what short-lived
tokens need. `""` sends no `Authorization` header.

**Keep-alive.** `connect()` and `reconnect()` spawn the core's heartbeat; it
exits on its own when the connection ends or another connect starts.

**Lifetime.** A JS holder should `disconnect()` and then `free()` the client (or
use `Symbol.dispose`) when it is done. `free()` cancels the pump, the dispatcher
and the heartbeat, and closes the peer connection, which is what lets the whole
graph be collected — the tasks hold the reactor, and the reactor holds the
transport whose sender feeds the pump, so nothing else can break that cycle.
Ending the session stays `disconnect()`'s job: tearing one down because a handle
was collected would be a surprising thing for a `free()` to do over the network.

## What lives above this crate

Stats collection, the React surface, clip playback and download, the v2
compatibility shim, and the store — all TypeScript, all in the SDK. This crate
draws the line at the protocol.
