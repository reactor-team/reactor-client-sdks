---
name: js-sdk-migration
description: >
  Migrate a JavaScript/TypeScript codebase off `@reactor-team/js-sdk` 2.x (browser-native
  `RTCPeerConnection`, last release in the `reactor-team/js-sdk` repo) onto this repo's
  `@reactor-team/js-sdk` 3.0.0 (`sdks/js`, wasm-bindgen over `reactor-core`). Use this whenever
  the user asks to "migrate to the new js-sdk / reactor SDK", "upgrade to js-sdk 3.0", "port off
  the old Reactor JS client", mentions a `sendCommand` return-type mismatch, `getState`/
  `getSessionInfo`/`.recording`/`sessionExpirationChanged` not existing, a `Capabilities` shape
  mismatch, or references breaking changes around `ReactorError`, `pauseTrack`/`resumeTrack`, or
  `RecordingClient`. Also use it when ported code fires a command and waits for an
  acknowledgement message that never arrives (the fire-then-listen pattern), when uploads or
  clips start failing with a 403 naming `sessions.bind`, or to review a PR that touches
  `@reactor-team/js-sdk` usage for compatibility with the 3.0.0 API.
---

# JS SDK migration (2.x → 3.0.0)

Migrating off `@reactor-team/js-sdk` 2.x (built directly on `RTCPeerConnection`, from the
`reactor-team/js-sdk` repo) onto this repo's `sdks/js`, 3.0.0 — a wasm-bindgen binding over
`reactor-core`, the same Rust session/protocol core the Python and C++ SDKs share. **Same
package name, same npm scope, major version bump** — unlike the Python migration, there's no
new package to switch to; the trap is code that still compiles and looks right after bumping
the version, because the shapes involved are close enough to pass a shallow read.

Skip straight to the tables below — they're already verified against both codebases' source,
not re-derived by guessing from symbol names.

---

## Confirm it's actually the old 2.x generation

Same package name in both, so check the **installed version** first:

```bash
npm ls @reactor-team/js-sdk 2>/dev/null || grep -A2 '"@reactor-team/js-sdk"' package.json package-lock.json
```

Anything below `3.0.0` is the old generation. If the dependency is a `link:`/`file:`/git
reference (so the version field doesn't tell you anything), grep the target codebase for
symbols that only exist on one side:

```bash
grep -rEn "getState\(\)|\.recording\.(requestClip|requestRecording|downloadClipAsFile)|sessionExpirationChanged|\bAbortError\b|getSessionInfo\(\)\?\.capabilities" --include="*.ts" --include="*.tsx" .
```

A hit confirms 2.x-era code (all five are gone or renamed in 3.0.0 — see below). No hits and a
recent version doesn't prove it's already migrated either — the traps below mostly *don't*
produce a hit on either side, since the symbol names carry over. Read the tables regardless.

---

## Migrating to 3.0.0

### The constructor — good news, this one just works

```ts
new Reactor({ apiUrl?, modelName, local?, modelTracks?, jwt? })
```

3.0.0's `ReactorOptions` is a strict superset of 2.x's constructor fields (`apiUrl`,
`modelName`, `local`, `modelTracks` all carry over unchanged) plus a raft of new optional
tuning knobs (`heartbeatIntervalMs`, `maxSdpAttempts`, `sdpBackoffInitialMs`, `logLevel`, ...)
and an optional `jwt` at construction time (2.x only ever took it at `connect()`). A ported
`new Reactor({ apiUrl, modelName, local })` call needs **no rewrite at all**.

`connect(jwt?: JwtSource, options?: ConnectOptions)` also carries over with the same signature
and the same `ConnectOptions` fields (`maxAttempts`, `autoResumeTracks`) — nothing to change
there either.

### `sendCommand()` — now actually awaited, and it returns something

| 2.x | 3.0.0 |
|---|---|
| `async sendCommand(command, data, scope?): Promise<void>` — fire-and-forget; the body never awaited anything, so the returned promise resolved on the next microtask regardless of whether the model ever replied. | `async sendCommand(command, data?, scope?): Promise<ReactorMessage \| undefined>` — genuinely awaits `reactor-core`'s correlated reply, bounded by a real timeout (`controlRequestTimeoutMs`, not infinite), and resolves with the reply. A local type alias for this signature (a lot of 2.x call sites declared their own instead of importing the SDK's) needs updating to the new return type — TypeScript catches this one immediately as a compile error, unlike the traps below. |
| `await sendCommand(cmd, data)`, or any call site that chains/consumes the returned promise | Now genuinely waits for the round trip (or the timeout) before resolving, instead of resolving almost immediately. A call site that never awaited it in the first place is unaffected — not awaiting a promise means not observing when it settles, not that it settles differently, so fire-and-forget call sites fire and move on exactly as before. |
| `scope: "runtime"` for `requestSchema`/`requestCapabilities`/heartbeat | `"runtime"` still exists (compat shim), but `requestSchema`/`requestCapabilities` sent this way resolve `undefined` always — the actual data now only surfaces through `getSchema()`/`schemaReceived` and `getCapabilities()`/`capabilitiesReceived` (see below). If migrated code still does `await sendCommand("requestCapabilities", {}, "runtime")` expecting a payload back, that's the bug — switch to the typed accessors. |

### `ReactorError` — same handful of class names, mostly different meaning

**Read this even when the class name matches — 2.x had almost no typed error hierarchy, so a
name reused in 3.0.0 is not the same class with more fields, it's a different design.**

| 2.x | 3.0.0 |
|---|---|
| One flat `ReactorError` **interface** (not a class): `code`, `message`, `timestamp`, `recoverable`, `component: "api" \| "gpu"`, `retryAfter?` — the `error` event payload, built from a small hand-picked set of string codes per call site (`NOT_READY`, `CONNECTION_FAILED`, `RECONNECTION_FAILED`, ...). | `ReactorError` is now a real **class** — the base of a 17-member typed hierarchy (`UnauthorizedError`, `NotFoundError`, `RateLimitedError`, `InvalidStateError`, `DisconnectedError`, and more), and both the `error` event payload *and* what a rejected/thrown call carries. Fields are `code`, `message`, `recoverable`, `status`, `operation`, `retry_after_ms`, `timestamp_ms` — no `component` (2.x populated it locally per call site; nothing on the 3.0.0 side ever reported it, so it wasn't preserved). `code` is `reactor-core`'s own canonical value now, not a fixed set the JS layer invented. |
| `class ConflictError extends Error` — a bare `Error` subclass, just `message`, thrown/used ad hoc, no `code`/`recoverable`/etc. | `ConflictError` — **same name, unrelated implementation**: now a `ReactorError` subclass with the full field set above. A `catch (e) { if (e instanceof ConflictError) ... }` block ported unchanged still runs, but on a different condition (`code === 'CONFLICT'` from `reactor-core`) than whatever the original author meant — verify what actually triggers it now before trusting it. |
| `class AbortError extends Error` | Renamed to `AbortedError` (and, like `ConflictError`, now a full `ReactorError` subclass). Catching `AbortError` silently catches nothing in 3.0.0 — there's no class by that name to match; `instanceof` against an undefined import is a compile error, not a silent no-op, so this one surfaces fast. |
| `class SessionLostError extends Error` + a standalone `isSessionLostError()` guard | No direct equivalent, and no import to fail on — this is a "your `catch` block still compiles but the branch it was checking for now never triggers" trap, not a compile error. `SessionTerminalError` and `DisconnectedError` are the closest concepts; check `reactor-core`'s actual reported `code` at the failure you're trying to catch rather than assuming either name is a 1:1 replacement. |

### `getSessionInfo()?.capabilities` vs. `getCapabilities()`/`capabilitiesReceived`

**The sharpest trap in this migration — code that "fixes the type and moves on" compiles fine
without being correct:**

- `getSessionInfo()?.capabilities` still exists in 3.0.0 and still returns the server's raw
  wire shape (snake_case: `protocol_version`, `emission_fps`) — this was never 2.x-specific, 2.x
  was already snake_case here too.
- 3.0.0 *also* introduces a new, separately-named, camelCase-translated `Capabilities` type —
  returned by the new `getCapabilities()` method, pushed via the new `capabilitiesReceived`
  event. It is not what `getSessionInfo()` returns, but it's the type most consumers will reach
  for by name, and TypeScript only catches the mismatch once the two shapes' fields actually
  diverge in a way the code touches (e.g. reading `.protocolVersion`, which is `undefined` on
  the raw shape).
- **The recommended 3.0.0 path is `getCapabilities()`/`capabilitiesReceived`, not patching
  around `getSessionInfo()`.** `capabilities` (like `schema`, like stats) isn't mirrored into a
  React store's reactive state if you're using the `ReactorProvider`/`useReactor` layer — reach
  it off the underlying `Reactor` instance:
  1. Read the current snapshot once with `getCapabilities()`.
  2. Subscribe with `.on('capabilitiesReceived', handler)` for live pushes; `.off(...)` on
     cleanup.
  3. **Also** re-check the snapshot on disconnect/session-end — `capabilitiesReceived` only ever
     fires to announce a value arriving, never to announce it going away. `getCapabilities()`
     does reset to `undefined` internally on disconnect, but nothing pushes that transition.

### Tracks: mostly additions, one real async change

| 2.x | 3.0.0 |
|---|---|
| The React-layer reactive `tracks` state (from the Zustand store, via `useReactor`) is `Record<string, MediaStreamTrack>` — already-resolved, playable media, keyed by name. | **Unchanged** — still `Record<string, MediaStreamTrack>`, populated the same way from `trackReceived`. Nothing to migrate for a React consumer reading `tracks` off the store. |
| The vanilla (non-React) `Reactor` class keeps a *private* `tracks: TrackCapability[]` internally (used for `connect()`/`reconnect()`), with no public accessor for it at all. | `Reactor.tracks(): TrackCapability[]` — the same declarations (`name`, `kind`, `direction`), now public, plus `trackMapping()`, `pausedTracks()`, `getTrackByMid`/`getStreamByMid`/`getTrackByName`/`getStreamByName`. **Pure addition** — there was nothing at this layer to migrate away from, since 2.x never exposed it. Resolve an actual `MediaStreamTrack` via `getTrackByName(name)` (or `getStreamByName(name)` for the `MediaStream`). |
| `pauseTrack(name): void` / `resumeTrack(name): void` — synchronous. | `pauseTrack(name): Promise<void>` / `resumeTrack(name): Promise<void>` — **asynchronous**, queued behind the same control round-trip as other track operations. This is the one real change in this section: a ported call site that didn't `await` these (there was nothing to await in 2.x) will now race the actual pause/resume against whatever runs next — add the `await`. |
| `trackReceived` fires `(name: string, track: MediaStreamTrack, stream: MediaStream)`. | Fires `(name, track, stream, mid)` — one extra trailing argument. Additive; a 2.x-shaped 3-parameter handler still works unchanged. |

### Commands reply now — migrate the fire-then-listen pattern

**This is the largest behavioral migration in app code, and nothing about it
is a compile error.** Verified end-to-end by porting a real 2.x app
(`js-sdk/examples/lingbot-world-2`) against production.

Models on reactor-runtime 3.2+ return their success acknowledgements as the
command's **correlated reply, delivered to the calling connection only** —
the value `sendCommand()` (and every typed-SDK wrapper over it) resolves
with. The model's OpenAPI schema declares this per command: a `200` whose
body `$ref`s a message component means the awaited call resolves with that
message; a bare `202` means the handler returns nothing. Consequences for
ported code:

- **Acknowledgement messages stop arriving as broadcasts.** 2.x app code
  that fires a command and then waits for e.g. a `prompt_accepted` /
  `image_accepted` / `generation_paused` handler on the `message` event
  keeps compiling and keeps listening — the handler just never fires again,
  because those messages now resolve the awaited call instead. Migrate the
  state updates to the call site (`const reply = await model.setPrompt(...)`)
  and shrink the message listener to what the model still broadcasts to
  every connection (shared state snapshots, per-chunk progress, error
  reports). The schema is the authority on which is which: check each
  command's `responses` for `200`-with-`$ref` vs bare `202`.
- **Awaiting a no-reply command is a completion barrier.** The runtime acks
  every correlated command once its handler has run, so a bare-`202`
  command's `await` resolving means the handler finished — not just that
  bytes left the browser. Ported code that sleeps after a command "to give
  the backend time" (`setTimeout` after a reset before sending new inputs)
  can delete the sleep: the resolved await *is* the confirmation.
- **`undefined` has exactly two meanings, and `getLastError()` tells them
  apart.** `sendCommand()` never rejects. It resolves `undefined` when the
  send failed (timeout included — the error lands on `getLastError()` / the
  `error` event) *and* when the handler completed but returned nothing.
  After an unexpected `undefined` from a reply-declaring command: an error
  recorded means it failed; no error means the model acked with no body —
  which, when the schema promises a reply, usually means the session landed
  on a replica still serving an **older model release** whose handlers
  predate returning messages (a fleet mid-rollout serves both). Check which
  release the session's pod runs before debugging the client.
- Working vanilla reference: [`sdks/js/examples/07-command-replies`](../../../sdks/js/examples/07-command-replies)
  — reads `save_snapshot`/`list_snapshots`/`rewind` replies off the await
  and shows the `message` event carrying only unprompted traffic.
- Debugging aid: development builds (`NODE_ENV === "development"`, or
  Vite's `import.meta.env.DEV`) log every data/control channel message via
  `console.debug`, so you can watch replies and broadcasts arrive while
  migrating.

### JWT resolvers: the token must stay stable for a session's whole life

3.0.0 invokes a `JwtSource` resolver on **every authenticated request** (by
design — short-lived tokens refresh without reconnecting). That interacts
with session-scoped tokens (`POST /tokens` with `authorization_details`) in
a way 2.x-era token plumbing gets wrong: a scoped token can only operate
sessions **it created**, so every hop of a session — uploads, clip
manifests, ICE refreshes — must present the *same* JWT that created it.

- A resolver that mints (or can mint) a fresh token per call breaks with
  `403 … this token is session-scoped and is not authorized for this
  resource; mint it again with authorization_details.resources.sessions.bind …`
  on the first upload or clip call that gets a different token than the
  session-creating one.
- **Memoize the token inside the resolver until shortly before its real
  expiry** (have the token endpoint return `expires_at` alongside the JWT),
  and fetch it with `cache: "no-store"`. Do not rely on the browser HTTP
  cache to keep the token stable — a `Cache-Control: max-age` scheme breaks
  under DevTools "Disable cache", cache eviction, and sessions created near
  the cached entry's expiry.
- Residual edge to know about: a session created just before the memoized
  token expires is orphaned at the re-mint. Covering it requires re-minting
  with `resources.sessions.bind` naming the live session.

### Recording: no `.recording`, no `RecordingClient`

| 2.x | 3.0.0 |
|---|---|
| `new Reactor(...)` auto-instantiates `reactor.recording`, a `RecordingClient` wired to the same instance — `reactor.recording.requestClip(...)`, `.requestRecording()`, `.downloadClipAsFile()` all worked out of the box. | **No `.recording` property, and no `RecordingClient` class at all** — `requestClip()`/`requestRecording()`/`downloadClipAsFile()` are directly on `Reactor`; call them there instead. Matches the Python SDK's own precedent in this repo, which never kept an equivalent wrapper either. An early 3.0.0 build briefly re-added `RecordingClient` as a delegate-only compatibility class before removing it again — if a checkout somewhere between there and here still has it, it does nothing `Reactor` itself doesn't. |

### `FileRef` — unchanged, plus one new helper

`FileRef` is **still a class** in 3.0.0, with the same constructor fields (`uploadId`, `name`,
`mimeType`, `size`) as 2.x, and `uploadFile()` still returns a real instance of it —
`instanceof FileRef` keeps working exactly as it did. (An earlier draft of this skill claimed
`FileRef` had become a plain object; that was wrong — checked directly against
`sdks/js/src/file-ref.ts`, not against stale notes, after a review comment caught it.) The one
addition is `isFileRef()`, a duck-typing guard for callers who'd rather not rely on `instanceof`
— useful if two copies of the package end up bundled into the same page (a `FileRef` built by
one copy fails `instanceof` against the other copy's class, but still passes `isFileRef()`).
`isFileRef()` checks `instanceof FileRef` first and falls back to structural checks, so it's a
superset of the old check, not a replacement for it — nothing to migrate here either way.

### `getState()` — removed, no single replacement

2.x's `Reactor.getState(): ReactorState` returned `{ status, lastError }` — a small snapshot object.
There is no `getState()` on 3.0.0's `Reactor` class at all. Use the individual getters directly:
`getStatus()` and `getLastError()`. (This name is also reused for something unrelated: 3.0.0's
*public* `ReactorState` type is the React store's state shape for `useReactor()` — not a return
type on `Reactor` itself. Don't assume it's what `getState()` used to return.)

### `sessionExpirationChanged` — gone, and there's genuinely nothing behind it to migrate

2.x has a `sessionExpirationChanged` event and a `sessionExpiration` field, and 3.0.0 has no
equivalent anywhere — but this one's safe to drop, not an open gap. Read 2.x's own
implementation, not just its type signature: `sessionExpiration` starts `undefined`, and its
*only* call site in the entire 2.x codebase sets it to `undefined` again (on a non-recoverable
`disconnect()`) — the setter's own no-op guard (`if (this.sessionExpiration !==
newSessionExpiration)`) means `undefined !== undefined` is `false`, so the event never actually
fires there either. No server response 2.x reads carries an expiration field that could
have fed it a real value. In other words: 2.x's `sessionExpirationChanged` never emitted anything
in practice, in any deployment — it's dead code that happened to still be in the public type
union, not a real capability 3.0.0 dropped. (`crates/reactor-wasm/README.md`, written by whoever
built the wasm binding, independently reaches the same conclusion: "v2 only ever fired it with
`undefined` on teardown.") If a migration's old code has an `on("sessionExpirationChanged", ...)`
handler, it's dead code on the 2.x side too — delete it, don't look for a replacement.

### React layer (`ReactorProvider`, `useReactor`, `ReactorView`, `WebcamStream`, `ClipPlayer`, ...)

Mostly at parity: `ReactorProviderProps` (`apiUrl`, `modelName`, `local`, `jwtToken`,
`connectOptions` including `autoConnect`), `ReactorView`, `WebcamStream`, and the hooks carry
the same names and shapes 2.x had — with one rename:

- **2.x's `getJwt` provider prop is gone.** 2.x took a static token as `jwtToken` and a
  resolver as a separate `getJwt` prop. 3.0.0's `jwtToken` is a `JwtSource` — a string *or* a
  resolver — so `<Provider getJwt={fetchToken}>` becomes `<Provider jwtToken={fetchToken}>`.
  TypeScript catches it (`Property 'getJwt' does not exist`); the fix is the rename, nothing
  else changes about the resolver contract (but read the JWT-resolver section above for what
  the resolver must now guarantee). `ClipPlayer` fixed a real 2.x/early-3.0
bug along the way (it used to pick native browser HLS over `hls.js` via `canPlayType()`, which
answers `"maybe"` in Chrome/Safari and then fails to actually play) — nothing for a migrating
consumer to change, but if 2.x app code ever special-cased "the preview doesn't work in Chrome,
tell people to download instead," that workaround is no longer needed.

---

## Known traps — worth calling out explicitly, not just fixing symbols

- **Same class/type name, different shape, no compile error.** `ConflictError`, `Capabilities`,
  `ReactorState`, and `getState()`'s old return shape are all cases where 3.0.0 reused a name for
  something that isn't the old thing — the two clearest, `ConflictError` and `Capabilities`, are
  detailed above. Don't trust a name match; check the actual fields against 3.0.0's source.
- **`instanceof AbortError` quietly stops matching.** `AbortError` was renamed to `AbortedError`
  — `grep` for `instanceof AbortError` explicitly, since an import of a class that no longer
  exists under that name is a compile error, but a lingering `catch` block checking `error.name
  === "AbortError"` (a string, not the class) or a duck-typed check isn't. `FileRef` has no
  equivalent trap — it's still the same class, see above.
- **`pauseTrack()`/`resumeTrack()` went from synchronous to async — there was no `await` to drop
  in 2.x, because there was no promise.** A 2.x call site relied on the pause/resume being applied
  by the very next line; the migrated call now needs an `await` it never had before, or it races
  the in-flight request against whatever runs next. This is a different trap from `sendCommand()`
  above: `sendCommand()` was already a promise in 2.x, so a 2.x call site that fired it without
  awaiting is unaffected by the round-trip change (see the table above) — `pauseTrack`/
  `resumeTrack` genuinely need the `await` added, since 2.x code had no way to already be doing
  that.
- **Don't confuse the two `tracks`.** The React store's `tracks` (`Record<string,
  MediaStreamTrack>`) is unchanged from 2.x. `Reactor.tracks()` (the vanilla-class method,
  `TrackCapability[]`) is new public API with no 2.x equivalent to migrate from — 2.x kept the
  same declarations privately. If a migration guide (including an earlier draft of this one)
  frames this as "the shape of `tracks` changed," that's overstating it for the common React
  case; check which layer the code in question actually reads from before assuming a change.
- **A message listener that waits for a command's acknowledgement dies silently.** On
  runtime-3.2+ models the success acks are correlated replies, not broadcasts — the ported
  listener compiles, subscribes, and never fires. No compile error, no runtime error; the UI
  just stops updating on those paths. Sweep every `message`-event handler (and typed
  per-message hooks) against the schema's `responses` and move `200`-declared messages to the
  awaited call sites. See "Commands reply now" above.
- **Token plumbing that re-mints per call compiles and then 403s mid-session.** The resolver
  runs on every authenticated request; with session-scoped tokens the same JWT must serve the
  session's whole life. The failure signature is a 403 naming
  `authorization_details.resources.sessions.bind` on uploads/clips. See the JWT-resolver
  section above.

---

## Migration procedure

1. Run the "Confirm it's actually the old 2.x generation" checks above.
2. Work through the tables top to bottom, fixing call sites. Prefer `grep -rn` for each old
   symbol over trying to remember where it's used — `getState()`, `.recording.`,
   `instanceof AbortError`, `sessionExpirationChanged`, and any local type alias for
   `sendCommand`'s signature are all worth a dedicated pass.
3. For every `getSessionInfo()?.capabilities` call site, decide deliberately whether to migrate
   to `getCapabilities()`/`capabilitiesReceived` (recommended) rather than just widening the type
   to compile — see the trap above.
4. Search for `catch`/`instanceof` blocks against `ConflictError` specifically, and re-verify what
   condition each is meant to catch against 3.0.0's actual typed hierarchy.
5. Add `await` to every `pauseTrack()`/`resumeTrack()` call site.
6. Sweep every `message`-event handler against the model's schema: messages a command's `200`
   response declares move to the awaited call site; only genuinely broadcast messages stay in
   listeners. While there, delete post-command settle sleeps — the resolved await already means
   the handler ran ("Commands reply now" above).
7. Audit the JWT resolver: it must return the same token for a session's whole life. Memoize
   until expiry inside the resolver; don't rely on the browser HTTP cache (JWT-resolver section
   above).
8. Run the target codebase's own type-check (`tsc --noEmit`) and test suite. If there's no test
   suite, at minimum exercise connect → send a command → publish/receive a track → request a clip
   → disconnect once against `local: true` or a real key. `tsc --noEmit` alone catches most of
   this skill's items automatically — the ones that don't produce a type error are called out
   explicitly above and need a manual grep pass instead.
9. Pin `@reactor-team/js-sdk@^3.0.0` explicitly in the migrated project's `package.json` — a loose
   pre-3.0 range can otherwise resolve back to a 2.x release.

## Reference

- Current API: [`sdks/js/src/reactor.ts`](../../../sdks/js/src/reactor.ts),
  [`errors.ts`](../../../sdks/js/src/errors.ts), [`types.ts`](../../../sdks/js/src/types.ts),
  [`recording-client.ts`](../../../sdks/js/src/recording-client.ts).
- [`sdks/js/CHANGELOG.md`](../../../sdks/js/CHANGELOG.md) for the two changes 3.0.0 itself calls
  out as breaking.
- The "Commands reply now", JWT-resolver, and `getJwt` → `jwtToken` sections were verified
  2026-08-28 by porting a real 2.x app (`js-sdk/examples/lingbot-world-2`) to 3.0.0 and running
  it against production: the silent death of ack listeners, the settle-sleep deletions, the
  `undefined`-vs-`getLastError()` discrimination (including a session landing on a replica
  serving an older model release), and the session-scoped-token 403 were each hit and fixed in
  that port, not derived from reading source.
- The tables above were verified by reading `reactor-team/js-sdk`'s actual source (local clone,
  not the published package) against this repo's `sdks/js` as of 2026-08-24 — `sendCommand()`'s
  await behavior, the full `ReactorError` hierarchy and `ConflictError`/`AbortedError` naming,
  `Reactor.tracks()` being new public API rather than a changed one (2.x's own vanilla `Reactor`
  keeps the equivalent field private), the React store's `tracks` state being unchanged,
  `pauseTrack`/`resumeTrack` becoming async, `.recording`'s and `RecordingClient`'s removal,
  `getState()`'s removal, and `sessionExpirationChanged`'s absence were all confirmed directly in
  source, not inferred from exports or names alone. `sessionExpirationChanged` specifically was
  traced all the way through 2.x's own implementation (its one call site, its no-op guard, and
  the absence of any server response field that could feed it) to confirm it never fired in practice
  there either, not just that 3.0.0 lacks it — `crates/reactor-wasm/README.md` reaches the same
  conclusion independently.
- **One row was wrong in an earlier draft and got caught by review, not by this skill's own
  process:** it claimed `FileRef` had become a plain object in 3.0.0. It's still a class, unchanged
  from 2.x's shape, and `instanceof FileRef` still works — confirmed by actually reading
  `sdks/js/src/file-ref.ts` after a `codex-review` comment flagged the claim as contradicting the
  source. The lesson generalizes: every row in this skill should be checked against the file it
  names, not against a memory of an earlier draft of the SDK — a design decision considered at one
  point (a plain-object `FileRef` was floated during REA-3282's planning) isn't the same thing as
  what shipped.
