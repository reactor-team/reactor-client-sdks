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
| Configuration | `setJwt(jwt)` |
| Messaging | `sendCommand(command, data?, uploads?)`, `requestSchema()` |
| Tracks | `publishTrack(name, track?)`, `unpublishTrack(name)`, `pauseTrack(name)`, `resumeTrack(name)`, `tracks()`, `trackMapping()`, `pausedTracks()` |
| Stepped generation | `step(state?, commands?)`, `pushVideoFrame(name, data, width, height, format?, metadata?, captureTimeUs?)`, `pushEncodedVideoFrame(name, codec, data, width, height, metadata?, captureTimeUs?)`, `pushAudioFrame(name, samples, sampleRate, channels)`, `clearStepInputs()` |
| Recording | `requestClip(seconds)`, `requestRecording()` |
| Uploads | `uploadFile(file, name?)` |
| Introspection | `status()`, `sessionId()`, `sessionInfo()`, `capabilities()`, `lastError()` |
| Browser handles | `getPeerConnection()`, `getTrackByMid(mid)`, `getStreamByMid(mid)`, `getTrackByName(name)`, `getStreamByName(name)` |
| Events | `onStatusChanged`, `onSessionIdChanged`, `onMessage`, `onRuntimeMessage`, `onTrackReceived`, `onError`, `onCapabilitiesReceived` |

Payloads that come out of the core keep their wire names (`protocol_version`,
`retry_after_ms`, `playlist_url`); the options this crate defines itself are
camelCase, because nothing on the wire constrains them. So is a step's result:
it is built by hand rather than serialized, because its frame data are typed
arrays (`Uint8Array` video, `Int16Array` audio) and serde has no word for one.

A client created with `generation: "stepped"` sends no input media over RTP.
`publishTrack(name)` only claims an input track (a `MediaStreamTrack` passed
there is ignored, with a console warning), `pushVideoFrame` / `pushAudioFrame`
buffer frames in the core, and the next `step()` carries them inside its one
`RequestStep`. Both of `step()`'s arguments are optional: an empty step runs on
the model's current state. Video arrives as RGBA, RGB or BGRA, and the core
converts it to the RGB a step sends in one pass. `pushEncodedVideoFrame` buffers
an H.264 access unit (Annex B) as it is, with the size it decodes to; the runtime
decodes a track's encoded frames of one step, in push order and starting at a
keyframe, before the step runs.

The buffer holds at most one data-channel message's worth of frames: pushing
faster than steps run drops the oldest frames, across tracks, and the next step
logs how many. Pushed frames belong to the step that takes them: when that
step's request cannot be sent — `MESSAGE_TOO_LARGE` among the reasons — the
step rejects and its frames are dropped with it, so the next step starts from
what is pushed after. `clearStepInputs()` drops whatever is buffered now.

`step()` resolves with `{ step, tracks, elapsed, files, error? }`. Each track
is `{ track, kind, rate, frames }`, `rate` being the rate its frames play at as
the runtime reported it (frames per second for video, samples per second for
audio; 0 when it did not say). `files`
describes the files the model kept with the step (`name`, `contentType`,
`size`); their bytes are served over HTTP from the session's step results.
`error` is set when the step ran and the model reported a failure — such a
step still resolves. A step that did not run rejects with `step_refused`
(nothing it carried was applied), `step_not_ready` (the session cannot take a
step yet) or `step_command_failed` (a state key or command failed; the calls
before it stay applied). Both of those carry `entry`, the part of the request
at fault, and `step_command_failed` also `command_code`, its handler's code. One whose result was too large for a message
rejects with `step_result_too_large`, and one whose session ended before the
result came back with `step_session_ended`.

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

**No SDP munging.** The offer goes out as the browser wrote it. The JS SDK
rewrites its own offer today — payload types remapped into [96,127],
telephone-event stripped, attributes reordered — but that exists for a GStreamer
runtime that mishandled H265 on payload type 45, which is not the runtime this
client talks to. Rewriting an offer that needs no rewriting is a way to break
codec negotiation, so the new SDK should drop `utils/sdp.ts` rather than port
it. If some future runtime needs it back, it belongs behind a hook here, not as
an unconditional transform.

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
