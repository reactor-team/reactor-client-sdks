import { AwaitQueue } from 'awaitqueue';
import { toPublicCapabilities } from './internal/capabilities';
import { toReactorError, type ReactorError } from './errors';
import { Emitter } from './internal/emitter';
import { extractFileRefs, toPublicFileRef } from './internal/file-ref';
import { toPublicClip } from './internal/recording';
import type { ReactorClient } from './internal/reactor-wasm.types';
import { createRTCStatsExtractor, STATS_INTERVAL_MS } from './internal/stats';
import { loadReactorWasm } from './internal/wasm';
import { downloadClipAsFile as downloadClipAsFileFn, type DownloadClipOptions } from './recording';
import type {
  Capabilities,
  Clip,
  ConnectionStats,
  ConnectionTimings,
  ConnectOptions,
  FileRef,
  JwtSource,
  MessageScope,
  ModelSchema,
  ReactorEventMap,
  ReactorMessage,
  ReactorOptions,
  ReactorStatus,
  TrackCapability,
  TrackMappingEntry,
} from './types';

/**
 * A live connection to a Reactor model.
 */
export class Reactor implements Disposable {
  private readonly clientOptions: Omit<ReactorOptions, 'jwt'>;
  private jwt: JwtSource | null | undefined;
  private client: ReactorClient | undefined;
  private clientPromise: Promise<ReactorClient> | undefined;
  private disposed = false;
  private _lastError: ReactorError | undefined;
  private schema: ModelSchema | undefined;
  /** Bumped on every `refreshSchema()` call — lets a call detect it's been
   *  superseded by a newer one even when `client` itself hasn't changed
   *  (e.g. two "ready" transitions on the same reused client). */
  private schemaRefreshId = 0;
  private capabilities: Capabilities | undefined;

  private stats: ConnectionStats | undefined;
  private connectionTimings: ConnectionTimings | undefined;
  private statsPollHandle: ReturnType<typeof setInterval> | undefined;
  /** Bumped on every `startStatsPolling()`/`stopStatsPolling()` call — lets an
   *  in-flight `getStats()` recognize it's stale once it resolves, even if
   *  `this.client` hasn't changed in the meantime. */
  private statsPollGeneration = 0;
  /** Set on the "connecting" status transition, cleared once `connectionTimings`
   *  is finalized on "ready" — see `handleStatusChanged()`. */
  private connectStartTime: number | undefined;
  private waitingStartTime: number | undefined;

  private readonly emitter = new Emitter<ReactorEventMap>();
  /** Serializes connect()/reconnect()/disconnect() (and the free() inside
   *  disconnect()/[Symbol.dispose]) against each other. Calling into the
   *  client concurrently with one of these (e.g. a user clicking Disconnect
   *  mid-connect) races its internal state and can throw ("attempted to
   *  take ownership of Rust value while it was borrowed") or corrupt it
   *  outright. */
  private readonly queue = new AwaitQueue();

  constructor(options: ReactorOptions) {
    const { jwt, ...clientOptions } = options;

    this.clientOptions = clientOptions;
    this.jwt = jwt ?? null;
  }

  // ── Lifecycle ───────────────────────────────────────────────────────────

  /**
   * `jwt`, when given, replaces whatever `new Reactor({ jwt })` was given —
   * including on a client that's still around from a recoverable
   * `disconnect(true)`.
   *
   * Throws if already connected or connecting; call `disconnect()` first.
   */
  async connect(jwt?: JwtSource, options?: ConnectOptions): Promise<void> {
    this.assertNotDisposed();
    try {
      if (this.getStatus() !== 'disconnected') {
        throw new Error('Already connected or connecting.');
      }
      if (jwt !== undefined) {
        this.jwt = jwt;
        this.client?.setJwt(jwt);
      }
      await this.queue.push(async () => {
        const client = await this.getOrCreateClient();

        await client.connect(options);
      }, 'connect');
    } catch (cause) {
      throw this.captureError(cause);
    }
  }

  /**
   * Ends the connection. By default (`recoverable = false`) this also frees
   * the wasm resource graph — the pump/dispatcher/heartbeat tasks and the
   * peer connection — in one step. Pass `recoverable: true` to keep the
   * wasm client alive so a later `connect()`/`reconnect()` doesn't have to
   * reload wasm and reconstruct it from scratch.
   *
   * Note this only governs the *local* resource graph — the binding's own
   * `disconnect()` always ends the session server-side regardless of this
   * flag, so `recoverable` isn't a way to keep the session itself alive.
   *
   * Queued behind any in-flight `connect()`/`reconnect()` — see `queue`'s
   * docs for why calling into the client concurrently with one of those is
   * unsafe.
   */
  async disconnect(recoverable = false): Promise<void> {
    this.assertNotDisposed();
    try {
      await this.queue.push(async () => {
        if (this.client) {
          await this.client.disconnect();
        }
        this.resetConnectionState();
        if (!recoverable) {
          this.freeClient();
        }
      }, 'disconnect');
    } catch (cause) {
      throw this.captureError(cause);
    }
  }

  /**
   * Only `options.maxAttempts` has an effect here — the rest of
   * `ConnectOptions` (session adoption, connection id, auto-resume) only
   * makes sense when establishing a session in the first place, same as v2's
   * `reconnect()` always ignored them.
   */
  async reconnect(options?: ConnectOptions): Promise<void> {
    this.assertNotDisposed();
    try {
      await this.queue.push(async () => {
        const client = await this.getOrCreateClient();

        await client.reconnect(options);
      }, 'reconnect');
    } catch (cause) {
      throw this.captureError(cause);
    }
  }

  // ── Messaging ───────────────────────────────────────────────────────────

  /**
   * Sends a command to the model and resolves with its correlated reply.
   *
   * `undefined` means the handler acknowledged the command but sent no
   * reply body. The binding's own typed signature promises `undefined` for
   * that case, but its serializer (serde_wasm_bindgen's `json_compatible`
   * mode) actually hands back `null` — normalized here so callers can rely
   * on the documented type instead of the binding's serialization quirk.
   *
   * A `FileRef` (from `uploadFile`) may be passed as a top-level value in
   * `data` alongside regular parameters — it is extracted and sent as a
   * separate upload reference rather than embedded in the JSON payload.
   *
   * Unlike every other method here, this never rejects — a failure (the
   * session isn't `"ready"`, the send itself fails, …) is reported through
   * `getLastError()`/the `error` event instead, resolving with `undefined`.
   * This is a JS-only compatibility shim, kept because callers routinely
   * fire-and-forget `sendCommand(...)` without `await`/`catch`, and a
   * rejection nobody handles is an unhandled-rejection warning at best. It
   * is deliberately not applied to `publishTrack`/`uploadFile`/etc., which
   * throw normally.
   *
   * `scope` is forwarded to `sendRuntimeScopedCommand()` — see there for
   * what `"runtime"` actually does.
   */
  async sendCommand(
    command: string,
    data?: Record<string, unknown>,
    scope?: MessageScope,
  ): Promise<ReactorMessage | undefined> {
    if (scope === 'runtime') {
      return this.sendRuntimeScopedCommand(command, data);
    }
    try {
      this.assertNotDisposed();
      const client = await this.getOrCreateClient();
      const extracted = extractFileRefs(data);
      const reply = await client.sendCommand(command, extracted.data, extracted.uploads);

      return reply ?? undefined;
    } catch (cause) {
      this.emitError(cause);
      return undefined;
    }
  }

  /**
   * Handles `sendCommand(command, data, "runtime")` — this SDK has no
   * generic runtime-scope channel. `reactor-core`'s `send_command` is a
   * single channel; `requestSchema`/clip requests/heartbeat are each their
   * own dedicated RPC instead of a scoped envelope, so there's nothing
   * generic to route a scope through at the wire level.
   *
   * `requestSchema` and `requestCapabilities` route to their direct
   * equivalents below, neither of which returns a `ReactorMessage` the way a
   * normal `sendCommand()` reply would, so both resolve `undefined`; the
   * actual data surfaces through `getSchema()`/`schemaReceived` and
   * `getCapabilities()`/`capabilitiesReceived` as it already does. Anything
   * else has no runtime-scope destination to route to — rather than silently
   * doing nothing, it falls through as a normal application-scope send, with
   * a console warning.
   */
  private async sendRuntimeScopedCommand(
    command: string,
    data?: Record<string, unknown>,
  ): Promise<ReactorMessage | undefined> {
    switch (command) {
      case 'requestSchema':
        try {
          await this.requestSchema();
        } catch (cause) {
          // requestSchema() already ran this through captureError() before
          // throwing — emit that same instance directly instead of
          // re-capturing, so listeners still get the error event
          // sendCommand's never-rejects contract promises them.
          this.emitter.emit('error', cause as ReactorError);
        }
        return undefined;
      case 'requestCapabilities':
        // No dedicated "request" exists — reactor-core pushes capabilities
        // once negotiated, so getCapabilities()/capabilitiesReceived already
        // reflect the latest value with nothing left to trigger here.
        return undefined;
      default:
        console.warn(
          `[Reactor] sendCommand(${JSON.stringify(command)}, …, "runtime") has no runtime-scope destination in this SDK — sending as a normal application-scope command instead.`,
        );
        return this.sendCommand(command, data);
    }
  }

  /** Requests the model's command schema directly. Most callers don't need
   *  this — it's already fetched once and cached as soon as the session
   *  reaches `"ready"`; use `getSchema()` for that. Resolves `undefined`
   *  when the model doesn't expose a schema — the binding replies with a
   *  wire `null` in that case rather than omitting the field. */
  async requestSchema(): Promise<ModelSchema | undefined> {
    this.assertNotDisposed();
    try {
      const client = await this.getOrCreateClient();

      return this.normalizeSchema(await client.requestSchema());
    } catch (cause) {
      throw this.captureError(cause);
    }
  }

  /** The model's command schema (an OpenAPI document), cached from the
   *  `requestSchema()` call fired automatically on `"ready"`. `undefined`
   *  until that reply has landed. */
  getSchema(): ModelSchema | undefined {
    return this.schema;
  }

  /** The runtime's declared capabilities (negotiated tracks, and the
   *  command set when the model exposes one) — pushed once available, no
   *  explicit request needed. `undefined` until `capabilitiesReceived`
   *  fires. */
  getCapabilities(): Capabilities | undefined {
    return this.capabilities;
  }

  // ── Tracks ──────────────────────────────────────────────────────────────

  /**
   * Publishes a local `MediaStreamTrack` under `name` — the counterpart to a
   * `sendonly` track the model declares. Awaitable. Queued behind
   * connect()/disconnect()/reconnect() (unlike `sendCommand()`): a track
   * operation awaits its own control round-trip, and a concurrent
   * disconnect() freeing the wasm client mid-await would otherwise resume
   * into a freed client — the same class of use-after-free `queue` exists to
   * prevent for connect/disconnect/reconnect themselves.
   */
  async publishTrack(name: string, track: MediaStreamTrack): Promise<void> {
    this.assertNotDisposed();
    try {
      await this.queue.push(async () => {
        const client = await this.getOrCreateClient();

        await client.publishTrack(name, track);
      }, 'publishTrack');
    } catch (cause) {
      throw this.captureError(cause);
    }
  }

  /**
   * Unlike every other track method, this doesn't reject — a failure is
   * reported through the `error` event instead, since this is commonly the
   * last call in a `finally` block, and raising there would replace
   * whatever exception was already propagating.
   */
  async unpublishTrack(name: string): Promise<void> {
    this.assertNotDisposed();
    try {
      await this.queue.push(async () => {
        const client = await this.getOrCreateClient();

        await client.unpublishTrack(name);
      }, 'unpublishTrack');
    } catch (cause) {
      this.emitError(cause);
    }
  }

  /**
   * Stops a received track: the receiver goes inactive and the runtime stops
   * producing it. Awaitable, and queued behind connect()/disconnect()/
   * reconnect() for the same reason as `publishTrack()`.
   */
  async pauseTrack(name: string): Promise<void> {
    this.assertNotDisposed();
    try {
      await this.queue.push(async () => {
        const client = await this.getOrCreateClient();

        await client.pauseTrack(name);
      }, 'pauseTrack');
    } catch (cause) {
      throw this.captureError(cause);
    }
  }

  /** Resumes a track previously stopped with `pauseTrack()`. */
  async resumeTrack(name: string): Promise<void> {
    this.assertNotDisposed();
    try {
      await this.queue.push(async () => {
        const client = await this.getOrCreateClient();

        await client.resumeTrack(name);
      }, 'resumeTrack');
    } catch (cause) {
      throw this.captureError(cause);
    }
  }

  // ── Recording ───────────────────────────────────────────────────────────

  /**
   * Requests a clip covering the last `durationSeconds` of the session.
   * `reactor-core` correlates the reply itself (and enforces `"ready"`), so
   * this is a thin delegation, same as `requestSchema()`. See
   * `downloadClipAsFile()` to turn the result into a file.
   */
  async requestClip(durationSeconds: number): Promise<Clip> {
    this.assertNotDisposed();
    try {
      const client = await this.getOrCreateClient();

      return toPublicClip(await client.requestClip(durationSeconds));
    } catch (cause) {
      throw this.captureError(cause);
    }
  }

  /** Requests a clip covering the entire session up to now. See `requestClip()`. */
  async requestRecording(): Promise<Clip> {
    this.assertNotDisposed();
    try {
      const client = await this.getOrCreateClient();

      return toPublicClip(await client.requestRecording());
    } catch (cause) {
      throw this.captureError(cause);
    }
  }

  /** Thin delegation to the standalone `downloadClipAsFile()` — see its own doc comment. */
  async downloadClipAsFile(
    clip: Clip,
    filename: string | null = 'reactor-clip.mp4',
    options?: DownloadClipOptions,
  ): Promise<Blob> {
    return downloadClipAsFileFn(clip, filename, options);
  }

  // ── Uploads ─────────────────────────────────────────────────────────────

  /**
   * Uploads a file to the session's object store, resolving with a
   * `FileRef` to pass as a top-level value in a `sendCommand()`'s `data`
   * (see `extractFileRefs`). The binding itself takes `(file, name?)`
   * positionally and already applies the right defaulting — a bare
   * `Blob`'s name falls back to `"upload"`, and an empty/missing mime type
   * to `"application/octet-stream"` — so there's nothing to wrap here.
   *
   * Queued behind connect()/disconnect()/reconnect(), same as the track
   * ops: this awaits its own round-trip, and a concurrent disconnect()
   * freeing the wasm client mid-upload would otherwise resume into a freed
   * client — the same use-after-free class of race the queue exists to
   * prevent.
   */
  async uploadFile(file: File | Blob, options?: { name?: string }): Promise<FileRef> {
    this.assertNotDisposed();
    try {
      const wireFileRef = await this.queue.push(async () => {
        const client = await this.getOrCreateClient();

        return client.uploadFile(file, options?.name);
      }, 'uploadFile');

      return toPublicFileRef(wireFileRef);
    } catch (cause) {
      throw this.captureError(cause);
    }
  }

  /** All tracks the model declared, whether or not media has arrived for —
   *  or been published to — them yet. Empty before a client exists. */
  tracks(): TrackCapability[] {
    return this.client?.tracks() ?? [];
  }

  /** Same as `tracks()`, plus each entry's negotiated `mid` — the id
   *  `trackReceived`'s `mid` argument and the `getXByMid` escape hatches key
   *  on. Only populated once SDP negotiation has assigned mids. */
  trackMapping(): TrackMappingEntry[] {
    return this.client?.trackMapping() ?? [];
  }

  pausedTracks(): string[] {
    return this.client?.pausedTracks() ?? [];
  }

  // ── Escape hatches ──────────────────────────────────────────────────────

  /** Drops to the raw `RTCPeerConnection` for anything this class doesn't
   *  wrap directly. `undefined` before a client exists. */
  getPeerConnection(): RTCPeerConnection | undefined {
    return this.client?.getPeerConnection();
  }

  getTrackByMid(mid: string): MediaStreamTrack | undefined {
    return this.client?.getTrackByMid(mid);
  }

  getStreamByMid(mid: string): MediaStream | undefined {
    return this.client?.getStreamByMid(mid);
  }

  getTrackByName(name: string): MediaStreamTrack | undefined {
    return this.client?.getTrackByName(name);
  }

  getStreamByName(name: string): MediaStream | undefined {
    return this.client?.getStreamByName(name);
  }

  /**
   * Supports `using reactor = new Reactor(...)`: releases the wasm resource
   * graph and drops every registered event handler for good. Unlike a plain
   * `disconnect()`, this instance is unusable afterward — construct a new
   * `Reactor` instead of trying to `connect()` again.
   */
  [Symbol.dispose](): void {
    if (this.disposed) {
      return;
    }
    this.disposed = true;
    const client = this.client;

    this.client = undefined;
    this.clientPromise = undefined;
    this.schema = undefined;
    this.capabilities = undefined;
    this.resetConnectionState();
    this.emitter.clear();
    if (client) {
      // Queued behind any in-flight connect()/reconnect()/disconnect(), same
      // as freeClient() — this can't await that itself, since [Symbol.dispose]
      // is synchronous per the `using` contract.
      void this.queue.push(() => client.free(), 'dispose').catch(() => {});
    }
  }

  // ── Introspection ───────────────────────────────────────────────────────

  /** The wasm binding's own terser `status()` name can be added alongside
   *  this later if it turns out to be worth it. */
  getStatus(): ReactorStatus {
    return this.client?.status() ?? 'disconnected';
  }

  /** See `getStatus()`. */
  getSessionId(): string | undefined {
    return this.client?.sessionId();
  }

  /** The `jwt` this instance was constructed (or last `connect()`ed) with —
   *  lets a component composed inside a `ReactorProvider` (e.g. `ClipPlayer`)
   *  authenticate its own requests without the caller repeating the resolver. */
  getJwtResolver(): JwtSource | undefined {
    return this.jwt ?? undefined;
  }

  /** The most recent `ReactorError`, from either an `error` event or a
   *  rejected call — whichever landed last. `undefined` until the first
   *  failure. */
  getLastError(): ReactorError | undefined {
    return this._lastError;
  }

  /** The most recent WebRTC connection stats, polled every `STATS_INTERVAL_MS`
   *  while "ready" — see `statsUpdate`. `undefined` before the first sample. */
  getStats(): ConnectionStats | undefined {
    return this.stats;
  }

  /** Timing breakdown from the most recent `connect()`/`reconnect()`
   *  handshake — see `ConnectionTimings`. */
  getConnectionTimings(): ConnectionTimings | undefined {
    return this.connectionTimings;
  }

  // ── Events ──────────────────────────────────────────────────────────────

  on<Name extends keyof ReactorEventMap>(event: Name, handler: ReactorEventMap[Name]): void {
    this.emitter.on(event, handler);
  }

  off<Name extends keyof ReactorEventMap>(event: Name, handler: ReactorEventMap[Name]): void {
    this.emitter.off(event, handler);
  }

  once<Name extends keyof ReactorEventMap>(event: Name, handler: ReactorEventMap[Name]): void {
    this.emitter.once(event, handler);
  }

  // ── Internal ────────────────────────────────────────────────────────────

  private getOrCreateClient(): Promise<ReactorClient> {
    if (!this.clientPromise) {
      this.clientPromise = this.createClient().catch((cause) => {
        // A failed first connect shouldn't wedge every later attempt behind
        // a client that never came up.
        this.clientPromise = undefined;
        throw cause;
      });
    }
    return this.clientPromise;
  }

  private async createClient(): Promise<ReactorClient> {
    const { ReactorClient: WasmReactorClient } = await loadReactorWasm();

    // [Symbol.dispose] may have run while the wasm module was loading above;
    // bail out before constructing a client that would otherwise outlive it
    // and never get freed.
    if (this.disposed) {
      throw new Error('Reactor was disposed while connecting.');
    }
    const client = new WasmReactorClient(this.clientOptions, this.jwt);

    client.onStatusChanged((status) => {
      this.emitter.emit('statusChanged', status);
      this.handleStatusChanged(client, status);
    });
    client.onSessionIdChanged((sessionId) => this.emitter.emit('sessionIdChanged', sessionId));
    client.onError((error) => this.emitError(error));
    // DATA channel — the model's own application traffic.
    client.onMessage((message) => this.emitter.emit('message', message));
    // CONTROL channel — platform traffic (moderation, clip/recording lifecycle).
    client.onRuntimeMessage((message) => this.emitter.emit('runtimeMessage', message));
    client.onCapabilitiesReceived((capabilities) => {
      this.capabilities = toPublicCapabilities(capabilities);
      this.emitter.emit('capabilitiesReceived', this.capabilities);
    });
    client.onTrackReceived((name, mid) => {
      const track = client.getTrackByName(name);
      const stream = client.getStreamByName(name);

      // Structurally shouldn't happen — `reactor-core` only dispatches this
      // event once the track is already resolvable — but if it ever does
      // (e.g. a teardown racing the dispatch), skip rather than emitting a
      // lie about the (non-optional) type.
      if (!track || !stream) {
        return;
      }
      this.emitter.emit('trackReceived', name, track, stream, mid);
    });
    this.client = client;
    return client;
  }

  /** Fired once per `"ready"` transition — see `getSchema()`. `getSchema()`
   *  isn't guaranteed populated by the time a `statusChanged` "ready" handler
   *  runs (this fetch is async and dispatched separately), so callers that
   *  need the schema as soon as it lands should listen for `schemaReceived`
   *  instead of reading `getSchema()` synchronously off `statusChanged`.
   *
   *  Guards against a stale reply: if `disconnect()`/a later `connect()`
   *  replaces `client` before this resolves, or a newer `refreshSchema()`
   *  call for the same client wins the race, the result is discarded rather
   *  than clobbering `this.schema` (or emitting `schema`) with old data.
   *
   *  A rejection here (e.g. the session drops mid-request) surfaces like any
   *  other binding failure, through the same `error` event as `onError`. */
  private async refreshSchema(client: ReactorClient): Promise<void> {
    const refreshId = ++this.schemaRefreshId;

    try {
      const schema = this.normalizeSchema(await client.requestSchema());

      if (this.client !== client || refreshId !== this.schemaRefreshId) {
        return;
      }
      // No schema for this model (wire `null`): leave the cache/event alone
      // rather than surfacing an empty document.
      if (schema === undefined) {
        return;
      }
      this.schema = schema;
      this.emitter.emit('schemaReceived', this.schema);
    } catch (cause) {
      if (this.client !== client || refreshId !== this.schemaRefreshId) {
        return;
      }
      this.emitError(cause);
    }
  }

  /** The binding replies with a wire `null` (not an omitted field) when the
   *  model doesn't expose a schema — normalized to `undefined` so callers
   *  get the same "no schema" sentinel `getSchema()` already uses instead
   *  of a document they could otherwise dereference. */
  private normalizeSchema(schema: unknown): ModelSchema | undefined {
    return (schema ?? undefined) as ModelSchema | undefined;
  }

  /** Frees the wasm resource graph, if one exists, and clears the cached
   *  schema. Reusable — unlike `[Symbol.dispose]`, this doesn't set the
   *  permanent `disposed` flag, so a later `connect()`/`reconnect()` lazily
   *  builds a fresh client.
   *
   *  Only called from within `disconnect()`'s queued task, so `client.free()`
   *  is safe to call directly here — the queue already guarantees no other
   *  operation is running concurrently against it. */
  private freeClient(): void {
    this.client?.free();
    this.client = undefined;
    this.clientPromise = undefined;
    this.schema = undefined;
    this.capabilities = undefined;
  }

  /** Tracks `connectionTimings` off the binding's own "connecting" → "waiting"
   *  → "ready" status sequence (see `ReactorStatus`), and starts/stops stats
   *  polling around the "ready" window. */
  private handleStatusChanged(client: ReactorClient, status: ReactorStatus): void {
    switch (status) {
      case 'connecting':
        this.connectStartTime = performance.now();
        this.waitingStartTime = undefined;
        break;
      case 'waiting':
        this.waitingStartTime = performance.now();
        break;
      case 'ready': {
        const readyTime = performance.now();

        if (this.connectStartTime != null) {
          const waitingStartTime = this.waitingStartTime ?? readyTime;

          this.connectionTimings = {
            sessionCreationMs: waitingStartTime - this.connectStartTime,
            transportConnectingMs: readyTime - waitingStartTime,
            totalMs: readyTime - this.connectStartTime,
          };
        }
        this.startStatsPolling(client);
        void this.refreshSchema(client);
        break;
      }
      default:
        this.stopStatsPolling();
    }
  }

  private startStatsPolling(client: ReactorClient): void {
    this.stopStatsPolling();
    const generation = ++this.statsPollGeneration;
    const extractStats = createRTCStatsExtractor();

    this.statsPollHandle = setInterval(() => {
      const peerConnection = client.getPeerConnection();

      if (!peerConnection) {
        return;
      }
      peerConnection
        .getStats()
        .then((report) => {
          // `stopStatsPolling()` only clears the interval — it can't cancel
          // a `getStats()` call already in flight. A recoverable disconnect
          // or a status flicker back to "ready" can leave `this.client`
          // pointing at this same `client`, so that identity alone can't
          // tell a stale sample from a live one; the generation bumped by
          // every `startStatsPolling()`/`stopStatsPolling()` call can.
          if (generation !== this.statsPollGeneration) {
            return;
          }
          this.stats = { ...extractStats(report), connectionTimings: this.connectionTimings };
          this.emitter.emit('statsUpdate', this.stats);
        })
        .catch(() => {
          // Connection may be closing.
        });
    }, STATS_INTERVAL_MS);
  }

  private stopStatsPolling(): void {
    this.statsPollGeneration += 1;
    if (this.statsPollHandle !== undefined) {
      clearInterval(this.statsPollHandle);
      this.statsPollHandle = undefined;
    }
    this.stats = undefined;
  }

  /** Called on every `disconnect()` and on `[Symbol.dispose]`. Leaves
   *  `client`/`schema` alone — that's `freeClient()`'s job, run separately
   *  when `disconnect()` isn't recoverable. `capabilities` is cleared here
   *  regardless, though: unlike the schema, it isn't re-fetched on demand,
   *  so a recoverable disconnect that skips `freeClient()` would otherwise
   *  leave `getCapabilities()` returning the previous session's stale
   *  tracks/commands until a new `capabilitiesReceived` lands. */
  private resetConnectionState(): void {
    this.stopStatsPolling();
    this.connectionTimings = undefined;
    this.connectStartTime = undefined;
    this.waitingStartTime = undefined;
    this.capabilities = undefined;
  }

  /** Wraps `cause` and records it as the most recent failure, for a call
   *  that's about to throw rather than emit — see `getLastError()`'s doc
   *  comment. */
  private captureError(cause: unknown): ReactorError {
    const error = toReactorError(cause);

    this._lastError = error;
    return error;
  }

  /** Wraps `cause`, records it as the most recent failure, and fires the
   *  `error` event — the one place recording and emitting happen together,
   *  so `getLastError()` never drifts from what listeners were told. */
  private emitError(cause: unknown): void {
    this.emitter.emit('error', this.captureError(cause));
  }

  private assertNotDisposed(): void {
    if (this.disposed) {
      throw new Error("This Reactor was disposed and can't be used again — construct a new one.");
    }
  }
}
