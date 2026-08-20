import { AwaitQueue } from 'awaitqueue';
import { Emitter } from './internal/emitter';
import { extractFileRefs, toPublicFileRef } from './internal/file-ref';
import type { ReactorClient } from './internal/reactor-wasm.types';
import { loadReactorWasm } from './internal/wasm';
import type {
  ConnectOptions,
  FileRef,
  JwtSource,
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
  private pendingJwt: JwtSource | null | undefined;
  private client: ReactorClient | undefined;
  private clientPromise: Promise<ReactorClient> | undefined;
  private disposed = false;
  private schema: unknown;
  /** Bumped on every `refreshSchema()` call — lets a call detect it's been
   *  superseded by a newer one even when `client` itself hasn't changed
   *  (e.g. two "ready" transitions on the same reused client). */
  private schemaRefreshId = 0;

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
    this.pendingJwt = jwt ?? null;
  }

  // ── Lifecycle ───────────────────────────────────────────────────────────

  async connect(options?: ConnectOptions): Promise<void> {
    this.assertNotDisposed();
    await this.queue.push(async () => {
      const client = await this.getOrCreateClient();
      await client.connect(options);
    }, 'connect');
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
    await this.queue.push(async () => {
      if (this.client) {
        await this.client.disconnect();
      }
      if (!recoverable) {
        this.freeClient();
      }
    }, 'disconnect');
  }

  async reconnect(): Promise<void> {
    this.assertNotDisposed();
    await this.queue.push(async () => {
      const client = await this.getOrCreateClient();
      await client.reconnect();
    }, 'reconnect');
  }

  // ── Messaging ───────────────────────────────────────────────────────────

  /**
   * Sends a command to the model and resolves with its correlated reply.
   * Rejects, same as `connect`/`disconnect`, if the session isn't `"ready"`.
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
   */
  async sendCommand(
    command: string,
    data?: Record<string, unknown>,
  ): Promise<ReactorMessage | undefined> {
    this.assertNotDisposed();
    const client = await this.getOrCreateClient();
    const extracted = extractFileRefs(data);
    const reply = await client.sendCommand(command, extracted.data, extracted.uploads);
    return reply ?? undefined;
  }

  /** Requests the model's command schema directly. Most callers don't need
   *  this — it's already fetched once and cached as soon as the session
   *  reaches `"ready"`; use `getSchema()` for that. */
  async requestSchema(): Promise<unknown> {
    this.assertNotDisposed();
    const client = await this.getOrCreateClient();
    return client.requestSchema();
  }

  /** The model's command schema (an OpenAPI document), cached from the
   *  `requestSchema()` call fired automatically on `"ready"`. `undefined`
   *  until that reply has landed. */
  getSchema(): unknown {
    return this.schema;
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
    await this.queue.push(async () => {
      const client = await this.getOrCreateClient();
      await client.publishTrack(name, track);
    }, 'publishTrack');
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
      this.emitter.emit('error', cause as Parameters<ReactorEventMap['error']>[0]);
    }
  }

  /**
   * Stops a received track: the receiver goes inactive and the runtime stops
   * producing it. Awaitable, and queued behind connect()/disconnect()/
   * reconnect() for the same reason as `publishTrack()`.
   */
  async pauseTrack(name: string): Promise<void> {
    this.assertNotDisposed();
    await this.queue.push(async () => {
      const client = await this.getOrCreateClient();
      await client.pauseTrack(name);
    }, 'pauseTrack');
  }

  /** Resumes a track previously stopped with `pauseTrack()`. */
  async resumeTrack(name: string): Promise<void> {
    this.assertNotDisposed();
    await this.queue.push(async () => {
      const client = await this.getOrCreateClient();
      await client.resumeTrack(name);
    }, 'resumeTrack');
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
    const wireFileRef = await this.queue.push(async () => {
      const client = await this.getOrCreateClient();
      return client.uploadFile(file, options?.name);
    }, 'uploadFile');
    return toPublicFileRef(wireFileRef);
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

  setJwt(jwt?: JwtSource | null): Promise<void> {
    this.assertNotDisposed();
    this.pendingJwt = jwt ?? null;
    this.client?.setJwt(this.pendingJwt);
    // The wasm binding's setJwt is synchronous; this stays `Promise<void>`
    // to match connect/disconnect/reconnect and leave room for a future
    // binding change without breaking callers who already `await` it.
    return Promise.resolve();
  }

  /**
   * Supports `using reactor = new Reactor(...)`: releases the wasm resource
   * graph and drops every registered event handler for good. Unlike a plain
   * `disconnect()`, this instance is unusable afterward — construct a new
   * `Reactor` instead of trying to `connect()` again.
   */
  [Symbol.dispose](): void {
    if (this.disposed) return;
    this.disposed = true;
    const client = this.client;
    this.client = undefined;
    this.clientPromise = undefined;
    this.schema = undefined;
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
    const client = new WasmReactorClient(this.clientOptions, this.pendingJwt);
    client.onStatusChanged((status) => {
      this.emitter.emit('statusChanged', status);
      if (status === 'ready') void this.refreshSchema(client);
    });
    client.onSessionIdChanged((sessionId) => this.emitter.emit('sessionIdChanged', sessionId));
    client.onError((error) => this.emitter.emit('error', error));
    // DATA channel — the model's own application traffic.
    client.onMessage((message) => this.emitter.emit('message', message));
    // CONTROL channel — platform traffic (moderation, clip/recording lifecycle).
    client.onRuntimeMessage((message) => this.emitter.emit('runtimeMessage', message));
    client.onTrackReceived((name, mid) => {
      const track = client.getTrackByName(name);
      const stream = client.getStreamByName(name);
      // Structurally shouldn't happen — `reactor-core` only dispatches this
      // event once the track is already resolvable — but if it ever does
      // (e.g. a teardown racing the dispatch), skip rather than emitting a
      // lie about the (non-optional) type.
      if (!track || !stream) return;
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
      const schema = await client.requestSchema();
      if (this.client !== client || refreshId !== this.schemaRefreshId) return;
      this.schema = schema;
      this.emitter.emit('schemaReceived', this.schema);
    } catch (cause) {
      if (this.client !== client || refreshId !== this.schemaRefreshId) return;
      this.emitter.emit('error', cause as Parameters<ReactorEventMap['error']>[0]);
    }
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
  }

  private assertNotDisposed(): void {
    if (this.disposed) {
      throw new Error("This Reactor was disposed and can't be used again — construct a new one.");
    }
  }
}
